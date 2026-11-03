package com.najib.carwelcome.car

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import androidx.car.app.CarContext
import androidx.car.app.hardware.CarHardwareManager
import androidx.car.app.hardware.common.CarValue
import androidx.car.app.hardware.common.OnCarDataAvailableListener
import androidx.car.app.hardware.info.Speed
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.max

/**
 * Live speed for the speedometer, plus simple stats for this drive (top speed,
 * average moving speed, distance). Uses the car's own speed when the head unit
 * shares it, and the phone's GPS otherwise. Runs while Android Auto is connected.
 */
object DriveSpeed {

    enum class Signal { GOOD, WEAK, NONE }

    @Volatile private var carMps = 0f
    @Volatile private var carAt = 0L
    @Volatile private var gpsMps = 0f
    @Volatile private var gpsAt = 0L
    /** Accuracy of the last GPS fix in metres, or -1. */
    @Volatile var accuracyM = -1f
        private set
    @Volatile var maxKmh = 0f
        private set
    @Volatile var distanceM = 0.0
        private set
    private var movingMs = 0L
    private var last: Location? = null
    private var running = false
    private var context: CarContext? = null
    private val listeners = linkedSetOf<() -> Unit>()

    private val speedListener = OnCarDataAvailableListener<Speed> { speed ->
        val v = speed.displaySpeedMetersPerSecond.takeIf { it.status == CarValue.STATUS_SUCCESS && it.value != null }
            ?: speed.rawSpeedMetersPerSecond
        if (v.status == CarValue.STATUS_SUCCESS) {
            v.value?.let { mps ->
                carMps = abs(mps)
                carAt = SystemClock.elapsedRealtime()
                noteSpeed(carMps * 3.6f, trusted = true)
                notifyListeners()
            }
        }
    }

    private val gpsListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            onFix(location)
            notifyListeners()
        }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    /** Starts a new drive: resets the stats and starts listening. */
    @SuppressLint("MissingPermission")
    fun start(carContext: CarContext) {
        stop()
        context = carContext
        running = true
        carAt = 0L; gpsAt = 0L; accuracyM = -1f
        maxKmh = 0f; distanceM = 0.0; movingMs = 0L; last = null
        runCatching {
            carContext.getCarService(CarHardwareManager::class.java).carInfo
                .addSpeedListener(ContextCompat.getMainExecutor(carContext), speedListener)
        }
        if (hasLocationPermission(carContext)) {
            runCatching {
                val lm = carContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, gpsListener, Looper.getMainLooper())
            }
        }
    }

    fun stop() {
        if (!running) return
        running = false
        val c = context ?: return
        runCatching { c.getCarService(CarHardwareManager::class.java).carInfo.removeSpeedListener(speedListener) }
        runCatching { (c.getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(gpsListener) }
        context = null
    }

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    private fun notifyListeners() { listeners.toList().forEach { runCatching { it() } } }

    fun hasLocationPermission(context: Context) =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Current speed in km/h, or null with no fresh reading. Car speed wins over GPS. */
    fun kmh(): Float? {
        val now = SystemClock.elapsedRealtime()
        return when {
            carAt > 0 && now - carAt < FRESH_MS -> carMps * 3.6f
            gpsAt > 0 && now - gpsAt < FRESH_MS -> gpsMps * 3.6f
            else -> null
        }
    }

    /** Average speed while moving, in km/h (0 until there is enough driving). */
    val avgKmh: Float
        get() = if (movingMs < 15_000L) 0f else (distanceM / (movingMs / 1000.0) * 3.6).toFloat()

    fun signal(): Signal {
        val now = SystemClock.elapsedRealtime()
        if (carAt > 0 && now - carAt < FRESH_MS) return Signal.GOOD
        if (gpsAt == 0L || now - gpsAt >= FRESH_MS) return Signal.NONE
        return if (accuracyM in 0f..20f) Signal.GOOD else Signal.WEAK
    }

    private fun onFix(loc: Location) {
        val now = SystemClock.elapsedRealtime()
        accuracyM = if (loc.hasAccuracy()) loc.accuracy else -1f
        if (loc.hasSpeed()) {
            gpsMps = loc.speed
            gpsAt = now
            // GPS speed spikes when the fix is poor; only trust good fixes for the top speed.
            val goodSpeed = !loc.hasSpeedAccuracy() || loc.speedAccuracyMetersPerSecond <= 2.5f
            val carFresh = carAt > 0 && now - carAt < FRESH_MS
            if (!carFresh) noteSpeed(gpsMps * 3.6f, trusted = goodSpeed && accuracyM in 0f..25f)
        }
        if (!loc.hasAccuracy() || loc.accuracy > 25f) return
        val prev = last
        last = loc
        if (prev == null) return
        val dtMs = (loc.elapsedRealtimeNanos - prev.elapsedRealtimeNanos) / 1_000_000L
        if (dtMs <= 0 || dtMs > 120_000L) return
        val d = prev.distanceTo(loc)
        val implied = d / (dtMs / 1000f)
        val moving = if (loc.hasSpeed()) loc.speed > 1f else implied > 1.5f
        // Skip standing-still jitter and impossible jumps.
        if (moving && implied < 70f) {
            distanceM += d
            movingMs += dtMs
        }
    }

    private fun noteSpeed(kmh: Float, trusted: Boolean) {
        if (trusted && kmh < 260f) maxKmh = max(maxKmh, kmh)
        // A manual "parked" switch never survives real driving.
        if (kmh > 25f) ParkedDetector.manualParked = false
    }

    private const val FRESH_MS = 4_000L
}
