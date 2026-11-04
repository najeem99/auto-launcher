package com.najib.carwelcome.drive

import android.content.Context
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.car.app.CarContext
import androidx.car.app.connection.CarConnection
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer

/**
 * Records a trip from the moment Android Auto connects (the dashboard opens on the
 * car, or the phone app sees the car) until it disconnects.
 *
 * Once a second it reads the shared speed ([DriveSensors]: the car's speed when the
 * head unit shares it, otherwise GPS) and adds the second to one of: moving (20 km/h
 * and up), slow traffic (5-20), standing (under 5) or no signal. Distance comes from
 * the car's speed when available, otherwise from GPS fixes, with drift while standing
 * and impossible jumps filtered out. The running trip is saved every 30 seconds, so a
 * crash or a killed app loses at most that much.
 *
 * Everything runs on the main thread.
 */
object TripRecorder {
    private const val IDLE_KMH = 5f
    private const val SLOW_KMH = 20f
    private const val TICK_MS = 1_000L
    private const val SAVE_EVERY_MS = 30_000L
    private const val POINT_EVERY_MS = 15_000L
    /** Reconnecting within this time (a loose cable, a quick fuel stop) continues the same trip. */
    private const val MERGE_MS = 5 * 60_000L
    /**
     * After Android Auto disconnects, the trip waits this long for it to come back
     * (unplug and replug, Android Auto restarting) before it ends. If it doesn't come
     * back, the trip ends at the moment it disconnected and the wait isn't counted.
     */
    const val DISCONNECT_MS = 5 * 60_000L
    /** If the car screen is gone and the phone hasn't moved for this long, the drive is over. */
    private const val STALE_IDLE_MS = 30 * 60_000L
    /** Keep the last reading through short gaps in the signal. */
    private const val HOLD_MS = 20_000L

    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null

    /** The trip being recorded, or null. Read it, don't change it. */
    @Volatile var current: Trip? = null
        private set
    val isRecording: Boolean get() = current != null

    /** Latest speed in km/h, or null without a reading. */
    @Volatile var speedKmh: Float? = null
        private set

    private val listeners = mutableListOf<() -> Unit>()
    fun addListener(l: () -> Unit) { if (l !in listeners) listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    private fun notifyListeners() = listeners.toList().forEach { runCatching { it() } }

    // Per-trip working state.
    private var connection: LiveData<Int>? = null
    private var sawConnected = false
    private var carScreenOpen = false
    private var disconnectedAt = 0L      // wall clock, 0 = connected
    /** The trip as it stood when Android Auto disconnected; restored if it never reconnects. */
    private var atDisconnect: Trip? = null

    /** When Android Auto disconnected, while the trip waits for it to come back (else 0). */
    val disconnectedSince: Long get() = if (current != null) disconnectedAt else 0L
    private var lastTick = 0L
    private var lastSave = 0L
    private var lastFreshAt = 0L
    private var lastKmh = 0f
    private var lastFromCar = false
    private val recent = ArrayDeque<Float>()
    private var idleRunMs = 0L
    private var idleSinceWall = 0L
    private var movedSinceStop = false
    private var carSpeedMs = 0L
    private var speedSeenMs = 0L
    private var gpsPendingM = 0.0
    private var prevFix: Location? = null
    private var pointStart = 0L
    private var pointSum = 0.0
    private var pointCount = 0

    private val connectionObserver = Observer<Int> { type ->
        if (type == CarConnection.CONNECTION_TYPE_NOT_CONNECTED) {
            if (disconnectedAt == 0L) {
                disconnectedAt = System.currentTimeMillis()
                atDisconnect = current?.copy()
            }
        } else {
            sawConnected = true
            disconnectedAt = 0L
            atDisconnect = null
        }
    }

    private val fixListener: (Location) -> Unit = { onFix(it) }

    private val tick = object : Runnable {
        override fun run() {
            step(checkEnd = true)
            if (current != null) main.postDelayed(this, TICK_MS)
        }
    }

    // ---- Entry points ------------------------------------------------------------

    /** The dashboard opened on the car screen: Android Auto is connected. */
    fun onCarScreenOpened(carContext: CarContext) {
        carScreenOpen = true
        disconnectedAt = 0L
        DriveSensors.attachCar(carContext)
        start(carContext)
    }

    /** The dashboard's car session ended (Android Auto closed it or disconnected). */
    fun onCarScreenClosed(carContext: CarContext) {
        carScreenOpen = false
        DriveSensors.detachCar(carContext)
    }

    /** The phone app noticed Android Auto is connected. */
    fun onCarSeen(context: Context) {
        sawConnected = true
        start(context)
    }

    /** Turns logging on or off; off ends the running trip now. */
    fun setEnabled(context: Context, on: Boolean) {
        TripPrefs(context).enabled = on
        if (!on) stop(System.currentTimeMillis())
    }

    /** Closes a trip left open by a crash or a killed app. Safe to call any time. */
    fun recover(context: Context) {
        if (current != null) return
        val db = TripDb.get(context)
        val open = db.openTrip() ?: return
        if (System.currentTimeMillis() - open.updatedAt > MERGE_MS) finish(db, open, open.updatedAt)
    }

    // ---- Start and stop -------------------------------------------------------------

    private fun start(context: Context) {
        if (current != null) return
        val ctx = context.applicationContext
        app = ctx
        if (!TripPrefs(ctx).enabled) return
        val db = TripDb.get(ctx)
        val now = System.currentTimeMillis()
        val trip = runCatching { pickTrip(db, now) }.getOrElse {
            Trip(startAt = now, updatedAt = now).also { t -> runCatching { db.insert(t) } }
        }
        current = trip

        disconnectedAt = 0L
        atDisconnect = null
        lastTick = SystemClock.elapsedRealtime()
        lastSave = lastTick
        lastFreshAt = 0L
        recent.clear()
        idleRunMs = 0L
        idleSinceWall = 0L
        movedSinceStop = false
        carSpeedMs = 0L
        speedSeenMs = 0L
        gpsPendingM = 0.0
        prevFix = null
        pointStart = now
        pointSum = 0.0
        pointCount = 0

        DriveSensors.acquire(ctx)
        DriveSensors.addFixListener(fixListener)
        connection = CarConnection(ctx).type.also { it.observeForever(connectionObserver) }
        TripService.start(ctx)
        main.removeCallbacks(tick)
        main.postDelayed(tick, TICK_MS)
        notifyListeners()
    }

    /** Continues an open or just-finished trip after a short break, or starts a new one. */
    private fun pickTrip(db: TripDb, now: Long): Trip {
        db.openTrip()?.let { open ->
            if (now - open.updatedAt < MERGE_MS) {
                open.idleMs += (now - open.updatedAt).coerceAtLeast(0)
                open.updatedAt = now
                db.update(open)
                return open
            }
            finish(db, open, open.updatedAt)
        }
        db.lastFinished()?.let { last ->
            val end = last.endAt ?: 0L
            if (now - end in 0 until MERGE_MS) {
                last.idleMs += now - end
                last.endAt = null
                last.updatedAt = now
                db.update(last)
                return last
            }
        }
        return Trip(startAt = now, updatedAt = now).also { db.insert(it) }
    }

    private fun stop(endAt: Long, restore: Trip? = null) {
        val trip = current ?: return
        val ctx = app ?: return
        step(checkEnd = false) // count the last second
        flushPoint(force = true)
        // Ended after waiting for a reconnect: drop the waiting time again.
        if (restore != null && restore.id == trip.id) {
            trip.distanceM = restore.distanceM
            trip.movingMs = restore.movingMs; trip.slowMs = restore.slowMs
            trip.idleMs = restore.idleMs; trip.noSignalMs = restore.noSignalMs
            trip.movingDistM = restore.movingDistM; trip.slowDistM = restore.slowDistM
            trip.topKmh = restore.topKmh; trip.stops = restore.stops
            runCatching { TripDb.get(ctx).deletePointsAfter(trip.id, endAt) }
        }
        atDisconnect = null
        current = null
        speedKmh = null
        main.removeCallbacks(tick)
        connection?.removeObserver(connectionObserver)
        connection = null
        DriveSensors.removeFixListener(fixListener)
        DriveSensors.release()
        runCatching { finish(TripDb.get(ctx), trip, endAt) }
        TripService.stop(ctx)
        notifyListeners()
    }

    private fun finish(db: TripDb, t: Trip, endAt: Long) {
        t.endAt = endAt.coerceAtLeast(t.startAt)
        t.updatedAt = t.endAt!!
        // An accidental connect: under a minute and barely moved.
        if (t.durationMs < 60_000 && t.distanceM < 100) db.delete(t.id) else db.update(t)
    }

    // ---- Measuring ---------------------------------------------------------------------

    private fun onFix(fix: Location) {
        if (current == null || fix.accuracy > 35f) return
        val prev = prevFix
        prevFix = fix
        if (prev == null) return
        val dt = (fix.elapsedRealtimeNanos - prev.elapsedRealtimeNanos) / 1e9
        if (dt <= 0) return
        val d = prev.distanceTo(fix).toDouble()
        if (d / dt > 70) return // over 250 km/h: a bad fix
        // Standing still, GPS wanders a few metres; only count real movement. A long
        // gap (a tunnel) is bridged in one go.
        val moving = (fix.hasSpeed() && fix.speed >= 1.4f) || (dt > 5 && d > 30)
        if (moving) gpsPendingM += d
    }

    private fun step(checkEnd: Boolean) {
        val trip = current ?: return
        val nowEl = SystemClock.elapsedRealtime()
        val nowWall = System.currentTimeMillis()
        val dtMs = (nowEl - lastTick).coerceIn(0, 5_000)
        lastTick = nowEl
        val dt = dtMs / 1000.0

        val s = DriveSensors.sample()
        val kmh: Float? = when {
            s != null -> {
                lastFreshAt = nowEl; lastKmh = s.speedMps * 3.6f; lastFromCar = s.fromCar
                speedSeenMs += dtMs
                if (s.fromCar) carSpeedMs += dtMs
                lastKmh
            }
            lastFreshAt != 0L && nowEl - lastFreshAt < HOLD_MS -> lastKmh
            else -> null
        }
        speedKmh = if (s != null) lastKmh else null

        // Distance this second: the car's own speed when it reports it, else GPS.
        val dist = if (s != null && s.fromCar) s.speedMps * dt else gpsPendingM
        gpsPendingM = 0.0
        trip.distanceM += dist

        when {
            kmh == null -> trip.noSignalMs += dtMs
            kmh < IDLE_KMH -> {
                trip.idleMs += dtMs
                if (idleRunMs == 0L) idleSinceWall = nowWall
                idleRunMs += dtMs
                if (idleRunMs >= 10_000 && movedSinceStop) { trip.stops++; movedSinceStop = false }
            }
            kmh < SLOW_KMH -> { trip.slowMs += dtMs; trip.slowDistM += dist; idleRunMs = 0; movedSinceStop = true }
            else -> { trip.movingMs += dtMs; trip.movingDistM += dist; idleRunMs = 0; movedSinceStop = true }
        }

        // Top speed: the car's reading directly; GPS must hold it for 3 seconds.
        if (s != null) {
            val v = s.speedMps * 3.6f
            if (s.fromCar) trip.topKmh = maxOf(trip.topKmh, v)
            else {
                recent.addLast(v)
                while (recent.size > 3) recent.removeFirst()
                if (recent.size == 3) trip.topKmh = maxOf(trip.topKmh, recent.min())
            }
            pointSum += v; pointCount++
        }
        trip.carSpeed = speedSeenMs > 0 && carSpeedMs * 2 > speedSeenMs
        trip.updatedAt = nowWall
        flushPoint(force = false)

        if (nowEl - lastSave >= SAVE_EVERY_MS) {
            lastSave = nowEl
            app?.let { ctx -> runCatching { TripDb.get(ctx).update(trip) } }
        }
        notifyListeners()

        if (!checkEnd) return
        // Has the drive ended? (Only trust "not connected" once the connection check has
        // shown it works in this app, or the car screen has closed too.)
        val disc = disconnectedAt
        if (disc != 0L && nowWall - disc >= DISCONNECT_MS && (sawConnected || !carScreenOpen)) {
            stop(disc, atDisconnect)
        } else if (!carScreenOpen && idleRunMs >= STALE_IDLE_MS) {
            stop(idleSinceWall)
        }
    }

    private fun flushPoint(force: Boolean) {
        val trip = current ?: return
        val now = System.currentTimeMillis()
        if (!force && now - pointStart < POINT_EVERY_MS) return
        if (pointCount > 0) {
            val p = SpeedPoint(pointStart + (now - pointStart) / 2, (pointSum / pointCount).toFloat())
            app?.let { ctx -> runCatching { TripDb.get(ctx).addPoint(trip.id, p) } }
        }
        pointStart = now
        pointSum = 0.0
        pointCount = 0
    }
}
