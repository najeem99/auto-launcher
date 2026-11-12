package com.najib.carwelcome

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.car.app.connection.CarConnection
import com.najib.carwelcome.drive.DriveUi
import com.najib.carwelcome.drive.DriveUi.dp
import com.najib.carwelcome.drive.Fuel
import com.najib.carwelcome.drive.FuelSection
import com.najib.carwelcome.drive.KmChart
import com.najib.carwelcome.drive.TimeBar
import com.najib.carwelcome.drive.Trip
import com.najib.carwelcome.drive.TripActivity
import com.najib.carwelcome.drive.TripBackup
import com.najib.carwelcome.drive.TripDb
import com.najib.carwelcome.drive.TripPrefs
import com.najib.carwelcome.drive.TripRecorder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The phone app's front page: a second screen for the car. Audio controls and
 * Office/Home at the top, then the live trip, and driving stats for today, this
 * week, month and year, with every trip listed below.
 */
class HomeActivity : AppCompatActivity() {

    private enum class Period(val title: String) { DAY("Day"), WEEK("Week"), MONTH("Month"), YEAR("Year") }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var tripPrefs: TripPrefs
    private var period = Period.WEEK
    /** Any moment inside the period on screen. */
    private var anchor = System.currentTimeMillis()
    private var carType = CarConnection.CONNECTION_TYPE_NOT_CONNECTED
    private var wasRecording = false

    private lateinit var greeting: TextView
    private lateinit var statusPill: TextView
    private lateinit var duaButton: TextView
    private lateinit var liveCard: LinearLayout
    private lateinit var liveSpeed: TextView
    private lateinit var liveSince: TextView
    private lateinit var liveGrid: LinearLayout
    private lateinit var liveBar: TimeBar
    private lateinit var liveWait: TextView
    private lateinit var chips: LinearLayout
    private lateinit var backupBanner: View
    private lateinit var backupStatus: TextView

    /** The folder picker for the backup file; picking a folder also restores what's in it. */
    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree == null) return@registerForActivityResult
        TripBackup.setFolder(this, tree)
        backupStatus.text = "Checking the folder…"
        TripBackup.restoreSoon(this) { added ->
            TripBackup.backupSoon(this) { saved ->
                main.post {
                    when {
                        added == null -> toast("Couldn't read that folder. Try Documents.")
                        added > 0 -> toast("Restored $added ${if (added == 1) "trip" else "trips"} from the backup")
                        saved != null -> toast("Backup saved: $saved ${if (saved == 1) "trip" else "trips"}")
                    }
                    refreshBackup(); loadStats()
                }
            }
        }
    }
    private lateinit var periodTitle: TextView
    private lateinit var nextButton: TextView
    private lateinit var summary: LinearLayout
    private lateinit var tripList: LinearLayout
    private lateinit var fuel: FuelSection

    private val onTrip: () -> Unit = { main.post { refreshLive() } }
    private val clock = object : Runnable {
        override fun run() {
            refreshAudio()
            greeting.text = Welcome.greetingLine(Prefs(this@HomeActivity).name)
            main.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tripPrefs = TripPrefs(this)
        Welcome.attach(this)
        supportActionBar?.hide()
        window.statusBarColor = DriveUi.night1
        window.navigationBarColor = DriveUi.night2
        askPermissions()

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 16f), dp(context, 20f), dp(context, 16f), dp(context, 32f))
        }
        column.addView(header())
        column.addView(controlsCard())
        column.addView(liveCardView())
        backupBanner = backupBannerView()
        column.addView(backupBanner)
        column.addView(statsHeader())
        summary = DriveUi.card(this)
        column.addView(summary)
        fuel = FuelSection(this) { loadStats() }
        column.addView(fuel.view)
        column.addView(DriveUi.label(this, "Trips · tap one for the details").apply { setPadding(dp(context, 4f), dp(context, 22f), 0, 0) })
        tripList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(tripList)
        column.addView(settingsCard())

        setContentView(ScrollView(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(DriveUi.night1, DriveUi.night2))
            isFillViewport = true
            addView(column)
        })

        // Seeing Android Auto connected here starts a trip too (in case the car screen
        // shows another app).
        CarConnection(this).type.observe(this) { type ->
            carType = type
            if (type != CarConnection.CONNECTION_TYPE_NOT_CONNECTED) TripRecorder.onCarSeen(this)
            refreshLive()
        }
    }

    override fun onResume() {
        super.onResume()
        TripRecorder.recover(this)
        TripBackup.backupIfStale(this, 12 * 60 * 60_000L)
        refreshBackup()
        TripRecorder.addListener(onTrip)
        main.post(clock)
        refreshLive()
        fuel.onResume()
        loadStats()
    }

    override fun onPause() {
        super.onPause()
        TripRecorder.removeListener(onTrip)
        main.removeCallbacks(clock)
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
    }

    private fun askPermissions() {
        val want = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            want += listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, "com.google.android.gms.permission.CAR_SPEED")
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            want += Manifest.permission.POST_NOTIFICATIONS
        }
        if (want.isNotEmpty()) requestPermissions(want.toTypedArray(), 1)
    }

    // ---- Header and controls -------------------------------------------------------

    private fun header(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val left = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        greeting = DriveUi.text(context, Welcome.greetingLine(Prefs(context).name), 24f, DriveUi.ink, "display")
        left.addView(greeting)
        statusPill = DriveUi.text(context, "", 13f, DriveUi.ink2, "bold").apply {
            setPadding(dp(context, 12f), dp(context, 6f), dp(context, 12f), dp(context, 6f))
        }
        left.addView(statusPill, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(context, 10f)
        })
        addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(pillButton("Settings", DriveUi.card2, DriveUi.ink2) {
            startActivity(Intent(context, MainActivity::class.java))
        })
    }

    private fun controlsCard(): View = DriveUi.card(this).apply {
        addView(DriveUi.label(context, "Controls"))
        duaButton = pillButton("Play dua", DriveUi.teal, DriveUi.night1) {
            if (!Welcome.pressDua(this@HomeActivity)) toast("Couldn't play Dua al-Safar")
            refreshAudio()
        }
        addView(row(
            duaButton,
            pillButton("Test welcome", DriveUi.card2, DriveUi.ink) { Welcome.run(this@HomeActivity) },
            pillButton("Stop all", DriveUi.red, DriveUi.night1) { Welcome.stopAll(); refreshAudio() },
        ), rowParams(12f))
        addView(DriveUi.label(context, "Navigate").apply { setPadding(0, dp(context, 18f), 0, 0) })
        addView(row(
            pillButton("Office", DriveUi.sand, DriveUi.night1) { navigate("Work") },
            pillButton("Home", DriveUi.sand, DriveUi.night1) { navigate("Home") },
        ), rowParams(12f))
        addView(DriveUi.text(context, "Opens Google Maps with the route started. It shows on the car screen too.", 12f, DriveUi.ink3).apply {
            setPadding(0, dp(context, 10f), 0, 0)
        })
        addView(DriveUi.label(context, "Android Auto").apply { setPadding(0, dp(context, 18f), 0, 0) })
        addView(row(
            pillButton("Show in Android Auto", DriveUi.teal, DriveUi.night1) { showInAndroidAuto() },
        ), rowParams(12f))
        addView(DriveUi.text(context, "Not on the car screen? This opens Android Auto's settings. Tap \"Customize launcher\" and tick Najeem Drive.", 12f, DriveUi.ink3).apply {
            setPadding(0, dp(context, 10f), 0, 0)
        })
    }

    /**
     * Opens Android Auto's own settings, where its launcher list decides which apps the
     * car shows. An app can't add itself to that list; only Android Auto can.
     */
    private fun showInAndroidAuto() {
        val gearhead = "com.google.android.projection.gearhead"
        val tries = listOfNotNull(
            packageManager.getLaunchIntentForPackage(gearhead),
            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$gearhead")),
        )
        for (i in tries) {
            try {
                startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                toast("Tap \"Customize launcher\" and tick Najeem Drive")
                return
            } catch (_: Exception) {}
        }
        toast("Android Auto isn't installed on this phone")
    }

    private fun navigate(place: String) {
        if (!Directions.startNow(this, place)) toast("Install Google Maps first")
    }

    private fun refreshAudio() {
        if (!::duaButton.isInitialized) return
        duaButton.text = when {
            Welcome.isGreeting || DuaPlayer.isPlaying -> "Pause dua"
            DuaPlayer.isPaused -> "Resume dua"
            else -> "Play dua"
        }
    }

    // ---- Live trip --------------------------------------------------------------------

    private fun liveCardView(): View {
        liveCard = DriveUi.card(this).apply {
            background = DriveUi.rounded(DriveUi.card, dp(context, 20f).toFloat(), DriveUi.teal, dp(context, 1f))
        }
        liveCard.addView(DriveUi.label(this, "Trip in progress"))
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        liveSpeed = DriveUi.text(this, "–", 56f, DriveUi.ink, "display")
        top.addView(liveSpeed)
        top.addView(DriveUi.text(this, " km/h", 16f, DriveUi.ink2, "medium").apply { setPadding(0, 0, 0, dp(context, 10f)) })
        liveCard.addView(top, rowParams(8f))
        liveSince = DriveUi.text(this, "", 13f, DriveUi.ink2)
        liveCard.addView(liveSince)
        liveGrid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        liveCard.addView(liveGrid, rowParams(12f))
        liveBar = TimeBar(this)
        liveCard.addView(liveBar, rowParams(14f))
        liveWait = DriveUi.text(this, "", 13f, DriveUi.ink2, "bold")
        liveCard.addView(liveWait, rowParams(8f))
        return liveCard
    }

    private fun refreshLive() {
        if (!::liveCard.isInitialized) return
        val t = TripRecorder.current
        statusPill.text = when {
            !tripPrefs.enabled -> "Trip log is off"
            t != null && TripRecorder.disconnectedSince != 0L -> {
                val left = (TripRecorder.DISCONNECT_MS - (System.currentTimeMillis() - TripRecorder.disconnectedSince)).coerceAtLeast(0) / 1000
                "Disconnected · trip kept open %d:%02d".format(left / 60, left % 60)
            }
            t != null -> "●  In the car · recording"
            carType != CarConnection.CONNECTION_TYPE_NOT_CONNECTED -> "●  In the car"
            else -> "Not connected to the car"
        }
        val pillColor = if (t != null) DriveUi.teal else DriveUi.ink3
        statusPill.setTextColor(pillColor)
        statusPill.background = DriveUi.rounded(DriveUi.card2, dp(this, 20f).toFloat(), pillColor, dp(this, 1f))

        if (wasRecording && t == null) loadStats() // a trip just ended
        wasRecording = t != null
        if (t == null) { liveCard.visibility = View.GONE; return }
        liveCard.visibility = View.VISIBLE
        liveSpeed.text = TripRecorder.speedKmh?.let { "%.0f".format(it) } ?: "–"
        liveSince.text = "Started ${DriveUi.time(this, t.startAt)} · ${DriveUi.duration(t.durationMs)}" +
            if (t.carSpeed) " · car speed" else " · GPS"
        liveGrid.removeAllViews()
        liveGrid.addView(DriveUi.grid(this, listOf(
            DriveUi.stat(this, "Distance", DriveUi.km(t.distanceM)),
            DriveUi.stat(this, "Average", DriveUi.kmh(t.avgMovingKmh)),
            DriveUi.stat(this, "Top", DriveUi.kmh(t.topKmh)),
        ), cols = 3))
        liveBar.set(t.movingMs, t.slowMs, t.idleMs)
        liveWait.text = "Waiting ${DriveUi.duration(t.waitMs)} (${(t.waitShare * 100).toInt()}%) · ${DriveUi.waitWord(t.waitShare)}"
        liveWait.setTextColor(DriveUi.waitColor(t.waitShare))
    }

    // ---- Stats ------------------------------------------------------------------------

    private fun statsHeader(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(context, 22f), 0, 0)
        addView(DriveUi.label(context, "Trips dashboard").apply { setPadding(dp(context, 4f), 0, 0, 0) })
        chips = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        addView(chips, rowParams(10f))
        // ‹ This week ›: step back and forward through days, weeks, months, years.
        val nav = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        nav.addView(pillButton("‹", DriveUi.card2, DriveUi.ink) { shift(-1) }, LinearLayout.LayoutParams(dp(context, 52f), ViewGroup.LayoutParams.WRAP_CONTENT))
        periodTitle = DriveUi.text(context, "", 17f, DriveUi.ink, "display").apply { gravity = Gravity.CENTER }
        nav.addView(periodTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        nextButton = pillButton("›", DriveUi.card2, DriveUi.ink) { shift(1) }
        nav.addView(nextButton, LinearLayout.LayoutParams(dp(context, 52f), ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(nav, rowParams(10f))
        drawChips()
    }

    private fun drawChips() {
        chips.removeAllViews()
        Period.values().forEachIndexed { i, p ->
            val on = p == period
            val chip = pillButton(p.title, if (on) DriveUi.ink else DriveUi.card2, if (on) DriveUi.night1 else DriveUi.ink2) {
                period = p; anchor = System.currentTimeMillis(); drawChips(); loadStats()
            }
            chips.addView(chip, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i > 0) marginStart = dp(this@HomeActivity, 8f)
            })
        }
    }

    private fun field(p: Period) = when (p) {
        Period.DAY -> Calendar.DAY_OF_YEAR
        Period.WEEK -> Calendar.WEEK_OF_YEAR
        Period.MONTH -> Calendar.MONTH
        Period.YEAR -> Calendar.YEAR
    }

    /** Start of the period that contains [at]. */
    private fun periodStart(p: Period, at: Long): Long = Calendar.getInstance().apply {
        timeInMillis = at
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        when (p) {
            Period.DAY -> {}
            // Weeks start on Monday (as in the UAE).
            Period.WEEK -> add(Calendar.DAY_OF_YEAR, -((get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY + 7) % 7))
            Period.MONTH -> set(Calendar.DAY_OF_MONTH, 1)
            Period.YEAR -> set(Calendar.DAY_OF_YEAR, 1)
        }
    }.timeInMillis

    private fun periodEnd(p: Period, start: Long): Long = Calendar.getInstance().apply {
        timeInMillis = start
        add(if (p == Period.WEEK) Calendar.DAY_OF_YEAR else field(p), if (p == Period.WEEK) 7 else 1)
    }.timeInMillis

    private fun shift(dir: Int) {
        val start = periodStart(period, anchor)
        val moved = Calendar.getInstance().apply {
            timeInMillis = start
            if (period == Period.WEEK) add(Calendar.DAY_OF_YEAR, 7 * dir) else add(field(period), dir)
        }.timeInMillis
        if (moved > System.currentTimeMillis()) return // nothing recorded in the future
        anchor = moved
        loadStats()
    }

    private fun periodName(p: Period, start: Long, end: Long): String {
        val now = System.currentTimeMillis()
        val current = now in start until end
        fun fmt(pattern: String, at: Long) = SimpleDateFormat(pattern, Locale.getDefault()).format(Date(at))
        return when (p) {
            Period.DAY -> DriveUi.day(start).let { if (it == "Today" || it == "Yesterday") "$it · ${fmt("EEE d MMM", start)}" else it }
            Period.WEEK -> if (current) "This week" else "${fmt("d MMM", start)} – ${fmt("d MMM", end - 1)}"
            Period.MONTH -> if (current) "This month" else fmt("MMMM yyyy", start)
            Period.YEAR -> if (current) "This year" else fmt("yyyy", start)
        }
    }

    /** The bars of the chart: hours of a day, days of a week or month, months of a year. */
    private fun buckets(p: Period, start: Long, end: Long): List<Pair<Long, String>> {
        val out = mutableListOf<Pair<Long, String>>()
        val c = Calendar.getInstance().apply { timeInMillis = start }
        val step = when (p) { Period.DAY -> Calendar.HOUR_OF_DAY; Period.YEAR -> Calendar.MONTH; else -> Calendar.DAY_OF_YEAR }
        var i = 0
        while (c.timeInMillis < end && i < 40) {
            val at = c.timeInMillis
            val label = when (p) {
                Period.DAY -> if (i % 6 == 0) "%d".format(i) else ""
                Period.WEEK -> SimpleDateFormat("EEE", Locale.getDefault()).format(Date(at)).take(2)
                Period.MONTH -> c.get(Calendar.DAY_OF_MONTH).let { if (it == 1 || it % 5 == 0) "$it" else "" }
                Period.YEAR -> SimpleDateFormat("MMM", Locale.getDefault()).format(Date(at)).take(1)
            }
            out += at to label
            c.add(step, 1)
            i++
        }
        return out
    }

    private fun loadStats() {
        val p = period
        val start = periodStart(p, anchor)
        val end = periodEnd(p, start)
        periodTitle.text = periodName(p, start, end)
        nextButton.alpha = if (end > System.currentTimeMillis()) 0.3f else 1f
        val db = TripDb.get(this)
        io.execute {
            val trips = runCatching { db.tripsBetween(start, end) }.getOrDefault(emptyList())
            main.post {
                if (isFinishing || p != period || periodStart(p, anchor) != start) return@post
                // The running trip in memory is fresher than its saved copy.
                val live = TripRecorder.current
                val list = trips.map { if (live != null && it.id == live.id) live.copy() else it }
                showSummary(p, start, end, list)
                fuel.show(periodName(p, start, end), start, end, list)
                showTrips(p, list)
            }
        }
    }

    private class Totals(trips: List<Trip>) {
        val dist = trips.sumOf { it.distanceM }
        val moving = trips.sumOf { it.movingMs }
        val slow = trips.sumOf { it.slowMs }
        val idle = trips.sumOf { it.idleMs }
        val total = trips.sumOf { it.durationMs }
        val tracked = moving + slow + idle
        val waitShare = if (tracked > 0) (slow + idle).toFloat() / tracked else 0f
    }

    private fun showSummary(p: Period, start: Long, end: Long, trips: List<Trip>) {
        summary.removeAllViews()
        val tot = Totals(trips)
        val wheelsMs = tot.moving + tot.slow
        val avg = if (wheelsMs > 30_000) (tot.dist / (wheelsMs / 1000.0) * 3.6).toFloat() else 0f
        val top = trips.maxOfOrNull { it.topKmh } ?: 0f
        val rated = tripPrefs.ratedKmpl
        val litres = trips.sumOf { Fuel.litres(it, rated) }
        val real = Fuel.kmpl(tot.dist, litres)

        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(DriveUi.text(this, "Total distance", 13f, DriveUi.ink3, "medium"))
        val big = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        big.addView(DriveUi.text(this, "%.1f".format(tot.dist / 1000), 44f, DriveUi.ink, "display"))
        big.addView(DriveUi.text(this, " km", 16f, DriveUi.ink2, "medium").apply { setPadding(0, 0, 0, dp(context, 8f)) })
        left.addView(big, rowParams(4f))
        left.addView(DriveUi.text(this, "${trips.size} ${if (trips.size == 1) "trip" else "trips"} · ${DriveUi.duration(tot.total)} in the car", 13f, DriveUi.ink2)
            .apply { setPadding(0, dp(context, 4f), 0, 0) })
        head.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (trips.isNotEmpty()) {
            val mood = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
            mood.addView(DriveUi.text(this, DriveUi.emoji(tot.waitShare), 40f))
            mood.addView(DriveUi.text(this, DriveUi.waitWord(tot.waitShare), 12f, DriveUi.waitColor(tot.waitShare), "bold")
                .apply { setPadding(0, dp(context, 4f), 0, 0) })
            head.addView(mood)
        }
        summary.addView(head)

        // Kilometres per hour / day / month as bars, coloured by how much of it was waiting.
        val bks = buckets(p, start, end)
        val km = FloatArray(bks.size)
        val colors = IntArray(bks.size) { DriveUi.line }
        val groups = bks.indices.map { i ->
            val from = bks[i].first
            val to = if (i + 1 < bks.size) bks[i + 1].first else end
            trips.filter { it.startAt in from until to }
        }
        groups.forEachIndexed { i, g ->
            val t = Totals(g)
            km[i] = (t.dist / 1000).toFloat()
            if (g.isNotEmpty()) colors[i] = DriveUi.waitColor(t.waitShare)
        }
        summary.addView(DriveUi.text(this, when (p) {
            Period.DAY -> "Kilometres by hour"
            Period.YEAR -> "Kilometres by month"
            else -> "Kilometres by day"
        } + (if (p == Period.WEEK || p == Period.MONTH || p == Period.YEAR) " · tap a bar" else ""), 12f, DriveUi.ink3, "medium")
            .apply { setPadding(0, dp(context, 16f), 0, 0) })
        summary.addView(KmChart(this).apply {
            set(km, colors, bks.map { it.second })
            onBar = { i ->
                when (p) {
                    Period.WEEK, Period.MONTH -> { period = Period.DAY; anchor = bks[i].first; drawChips(); loadStats() }
                    Period.YEAR -> { period = Period.MONTH; anchor = bks[i].first; drawChips(); loadStats() }
                    Period.DAY -> {}
                }
            }
        }, rowParams(8f))

        if (trips.isEmpty()) {
            summary.addView(DriveUi.text(this, if (tripPrefs.enabled) "No drives here. A trip starts by itself when Android Auto connects and ends when it disconnects."
            else "Trip log is off. Turn it on below to record your drives.", 13f, DriveUi.ink3).apply { setPadding(0, dp(context, 10f), 0, 0) })
            return
        }
        summary.addView(TimeBar(this).apply { set(tot.moving, tot.slow, tot.idle) }, rowParams(16f))
        summary.addView(DriveUi.legend(this, tot.moving, tot.slow, tot.idle))
        summary.addView(DriveUi.grid(this, listOf(
            DriveUi.stat(this, "Driving", DriveUi.duration(tot.moving), DriveUi.teal, "20 km/h and up"),
            DriveUi.stat(this, "Waiting in traffic", DriveUi.duration(tot.slow + tot.idle), DriveUi.waitColor(tot.waitShare),
                "${DriveUi.emoji(tot.waitShare)} ${(tot.waitShare * 100).toInt()}% · ${DriveUi.waitWord(tot.waitShare)}"),
            DriveUi.stat(this, "Average speed", DriveUi.kmh(avg), sub = "while moving"),
            DriveUi.stat(this, "Top speed", DriveUi.kmh(top)),
            DriveUi.stat(this, "Petrol (rough)", "%.1f L".format(litres), DriveUi.sand, "≈ AED %.0f".format(trips.sumOf { Fuel.cost(this, it, rated) })),
            DriveUi.stat(this, "Real economy", if (real > 0) "%.1f km/L".format(real) else "–", sub = "rated %.0f km/L".format(rated)),
        )), rowParams(14f))
    }

    /** Trips, grouped under a header for each day with that day's totals. */
    private fun showTrips(p: Period, trips: List<Trip>) {
        tripList.removeAllViews()
        if (trips.isEmpty()) {
            tripList.addView(DriveUi.text(this, "Nothing in this period.", 13f, DriveUi.ink3).apply { setPadding(dp(context, 4f), dp(context, 10f), 0, 0) })
            return
        }
        val shown = trips.take(300)
        val byDay = shown.groupBy { periodStart(Period.DAY, it.startAt) }
        byDay.forEach { (day, list) ->
            if (p != Period.DAY) tripList.addView(dayHeader(day, list))
            list.forEach { tripList.addView(tripRow(it)) }
        }
        if (trips.size > shown.size) {
            tripList.addView(DriveUi.text(this, "Showing the latest ${shown.size} trips.", 12f, DriveUi.ink3).apply { setPadding(dp(context, 4f), dp(context, 10f), 0, 0) })
        }
    }

    private fun dayHeader(day: Long, list: List<Trip>): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(context, 4f), dp(context, 18f), dp(context, 4f), 0)
        val t = Totals(list)
        addView(DriveUi.text(context, DriveUi.day(day), 15f, DriveUi.ink, "display"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(DriveUi.text(context, "${DriveUi.km(t.dist)} · ${DriveUi.duration(t.total)}  ${DriveUi.emoji(t.waitShare)}", 13f, DriveUi.ink2, "medium"))
        isClickable = true
        setOnClickListener { period = Period.DAY; anchor = day; drawChips(); loadStats() }
    }

    private fun tripRow(t: Trip): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        val waitC = DriveUi.waitColor(t.waitShare)
        background = DriveUi.rounded(DriveUi.card, dp(context, 16f).toFloat(), DriveUi.line, dp(context, 1f))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(context, 10f)
        }
        isClickable = true
        setOnClickListener { startActivity(Intent(context, TripActivity::class.java).putExtra(TripActivity.EXTRA_ID, t.id)) }

        addView(View(context).apply { background = DriveUi.rounded(waitC, dp(context, 3f).toFloat()) },
            LinearLayout.LayoutParams(dp(context, 5f), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                setMargins(dp(context, 10f), dp(context, 12f), 0, dp(context, 12f))
            })
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 12f), dp(context, 12f), dp(context, 12f), dp(context, 12f))
        }
        val end = t.endAt?.let { DriveUi.time(context, it) } ?: "now"
        body.addView(DriveUi.text(context, "${DriveUi.time(context, t.startAt)} → $end", 15f, DriveUi.ink, "bold"))
        body.addView(DriveUi.text(context, "${DriveUi.duration(t.durationMs)} · ${DriveUi.km(t.distanceM)} · avg ${DriveUi.kmh(t.avgMovingKmh)}", 13f, DriveUi.ink2)
            .apply { setPadding(0, dp(context, 6f), 0, 0) })
        body.addView(DriveUi.text(context, "Waiting ${DriveUi.duration(t.waitMs)} · traffic ${DriveUi.duration(t.slowMs)} · standing ${DriveUi.duration(t.idleMs)}", 12f, DriveUi.ink3)
            .apply { setPadding(0, dp(context, 4f), 0, 0) })
        body.addView(TimeBar(context).apply { set(t.movingMs, t.slowMs, t.idleMs) }, rowParams(10f))
        addView(body, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val pill = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(context, 10f), dp(context, 8f), dp(context, 10f), dp(context, 8f))
            background = DriveUi.rounded(DriveUi.card2, dp(context, 12f).toFloat(), waitC, dp(context, 1f))
            addView(DriveUi.text(context, DriveUi.emoji(t.waitShare), 24f))
            addView(DriveUi.text(context, "${(t.waitShare * 100).toInt()}%", 12f, waitC, "bold").apply { setPadding(0, dp(context, 4f), 0, 0) })
        }
        addView(pill, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_VERTICAL
            marginEnd = dp(context, 12f)
        })
    }

    // ---- Settings ---------------------------------------------------------------------

    private fun settingsCard(): View = DriveUi.card(this).apply {
        (layoutParams as LinearLayout.LayoutParams).topMargin = dp(context, 24f)
        addView(DriveUi.label(context, "Trip log settings"))
        val sw = Switch(context).apply {
            text = "Record my drives"
            textSize = 15f
            setTextColor(DriveUi.ink)
            typeface = DriveUi.font(context, "medium")
            isChecked = tripPrefs.enabled
            setOnCheckedChangeListener { _, on ->
                TripRecorder.setEnabled(this@HomeActivity, on)
                if (on && carType != CarConnection.CONNECTION_TYPE_NOT_CONNECTED) TripRecorder.onCarSeen(this@HomeActivity)
                refreshLive(); loadStats()
            }
        }
        addView(sw, rowParams(10f))
        addView(DriveUi.text(context, "Uses GPS once a second only while Android Auto is connected (the phone is usually charging then). Turn it off if the phone runs hot or slow; your saved trips stay.", 12f, DriveUi.ink3)
            .apply { setPadding(0, dp(context, 6f), 0, 0) })
        addView(numberField("Rated fuel economy (km per litre)", tripPrefs.ratedKmpl) { tripPrefs.ratedKmpl = it; loadStats() }, rowParams(14f))
        addView(DriveUi.text(context, "Petrol is a rough estimate: real driving gets about 85% of the rated figure, slow traffic about 55%, and idling burns about 0.6 L an hour. Each trip is priced with that month's UAE price for your grade (see Fuel above).", 12f, DriveUi.ink3)
            .apply { setPadding(0, dp(context, 8f), 0, 0) })
        addView(DriveUi.label(context, "Backup").apply { setPadding(0, dp(context, 22f), 0, 0) })
        backupStatus = DriveUi.text(context, "", 13f, DriveUi.ink2)
        addView(backupStatus, rowParams(8f))
        addView(row(
            pillButton("Choose folder", DriveUi.card2, DriveUi.ink) { chooseFolder() },
            pillButton("Back up now", DriveUi.card2, DriveUi.ink) {
                if (TripBackup.folder(this@HomeActivity) == null) chooseFolder()
                else TripBackup.backupSoon(this@HomeActivity) { n ->
                    main.post { toast(if (n == null) "Backup failed. Choose the folder again." else "Backup saved: $n trips"); refreshBackup() }
                }
            },
        ), rowParams(10f))
        addView(DriveUi.text(context, "The backup is one file, $BACKUP_HINT. It stays on the phone even if you uninstall the app. After reinstalling, tap Choose folder and pick the same folder to get your trips back. Your trips are also included in your Google account backup if that's on.", 12f, DriveUi.ink3)
            .apply { setPadding(0, dp(context, 8f), 0, 0) })
        addView(pillButton("Delete all trips", DriveUi.card2, DriveUi.red) {
            AlertDialog.Builder(this@HomeActivity)
                .setTitle("Delete all trips?")
                .setMessage("This removes every saved drive from this phone.")
                .setPositiveButton("Delete") { _, _ ->
                    io.execute { TripDb.get(this@HomeActivity).deleteAll(); TripBackup.backupSoon(this@HomeActivity) { main.post { refreshBackup() } }; main.post { loadStats() } }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }, rowParams(16f))
    }

    private fun numberField(title: String, value: Float, save: (Float) -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(DriveUi.text(context, title, 14f, DriveUi.ink2), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(EditText(context).apply {
            setText(if (value % 1f == 0f) value.toInt().toString() else "%.2f".format(value))
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setTextColor(DriveUi.ink)
            textSize = 16f
            gravity = Gravity.END
            minEms = 3
            setOnFocusChangeListener { _, has ->
                if (!has) text.toString().toFloatOrNull()?.takeIf { it > 0f && it < 1000f }?.let(save)
            }
            setOnEditorActionListener { v, _, _ ->
                v.text.toString().toFloatOrNull()?.takeIf { it > 0f && it < 1000f }?.let(save); false
            }
        })
    }

    // ---- Backup ---------------------------------------------------------------------------

    private fun chooseFolder() {
        val documents = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Documents")
        try { pickFolder.launch(documents) } catch (_: Exception) { toast("No file manager found to pick a folder") }
    }

    /** Shown near the top until a backup folder is set, so the trips aren't lost on uninstall. */
    private fun backupBannerView(): View = DriveUi.card(this).apply {
        background = DriveUi.rounded(DriveUi.card, dp(context, 20f).toFloat(), DriveUi.sand, dp(context, 1f))
        addView(DriveUi.text(context, "Keep your trips safe", 16f, DriveUi.sand, "display"))
        addView(DriveUi.text(context, "Pick a folder for a backup file (Documents is best). It survives uninstalling. Reinstalled the app? Pick the same folder to bring your trips back.", 13f, DriveUi.ink2)
            .apply { setPadding(0, dp(context, 8f), 0, 0) })
        addView(pillButton("Choose backup folder", DriveUi.sand, DriveUi.night1) { chooseFolder() }, rowParams(12f))
    }

    private fun refreshBackup() {
        if (!::backupStatus.isInitialized) return
        val set = TripBackup.folder(this) != null
        backupBanner.visibility = if (set) View.GONE else View.VISIBLE
        val at = TripBackup.lastBackupAt(this)
        backupStatus.text = when {
            !set -> "Not set up. Without it, uninstalling the app deletes your trips."
            at == 0L -> "Saving to ${TripBackup.folderName(this)}…"
            else -> "Saved to ${TripBackup.folderName(this)} · ${TripBackup.lastCount(this)} trips · ${DriveUi.day(at)} ${DriveUi.time(this, at)}"
        }
        backupStatus.setTextColor(if (set) DriveUi.ink2 else DriveUi.orange)
    }

    // ---- Helpers ------------------------------------------------------------------------

    private fun pillButton(title: String, bg: Int, fg: Int, onClick: () -> Unit): TextView =
        DriveUi.text(this, title, 14f, fg, "bold").apply {
            gravity = Gravity.CENTER
            setPadding(dp(context, 14f), dp(context, 12f), dp(context, 14f), dp(context, 12f))
            background = DriveUi.rounded(bg, dp(context, 14f).toFloat())
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    private fun row(vararg views: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEachIndexed { i, v ->
            addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i > 0) marginStart = dp(this@HomeActivity, 8f)
            })
        }
    }

    private fun rowParams(topDp: Float) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .apply { topMargin = dp(this@HomeActivity, topDp) }

    private val BACKUP_HINT = "\"${TripBackup.FILE_NAME}\""

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
