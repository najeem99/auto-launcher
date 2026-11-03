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
import androidx.appcompat.app.AppCompatActivity
import androidx.car.app.connection.CarConnection
import com.najib.carwelcome.drive.DriveUi
import com.najib.carwelcome.drive.DriveUi.dp
import com.najib.carwelcome.drive.Fuel
import com.najib.carwelcome.drive.TimeBar
import com.najib.carwelcome.drive.Trip
import com.najib.carwelcome.drive.TripActivity
import com.najib.carwelcome.drive.TripDb
import com.najib.carwelcome.drive.TripPrefs
import com.najib.carwelcome.drive.TripRecorder
import java.util.Calendar
import java.util.concurrent.Executors

/**
 * The phone app's front page: a second screen for the car. Audio controls and
 * Office/Home at the top, then the live trip, and driving stats for today, this
 * week, month and year, with every trip listed below.
 */
class HomeActivity : AppCompatActivity() {

    private enum class Period(val title: String) { TODAY("Today"), WEEK("Week"), MONTH("Month"), YEAR("Year") }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var tripPrefs: TripPrefs
    private var period = Period.WEEK
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
    private lateinit var summary: LinearLayout
    private lateinit var tripList: LinearLayout

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
        column.addView(statsHeader())
        summary = DriveUi.card(this)
        column.addView(summary)
        column.addView(DriveUi.label(this, "Trips").apply { setPadding(dp(context, 4f), dp(context, 22f), 0, 0) })
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
        TripRecorder.addListener(onTrip)
        main.post(clock)
        refreshLive()
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
        addView(DriveUi.label(context, "Driving stats").apply { setPadding(dp(context, 4f), 0, 0, 0) })
        chips = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        addView(chips, rowParams(10f))
        drawChips()
    }

    private fun drawChips() {
        chips.removeAllViews()
        Period.values().forEachIndexed { i, p ->
            val on = p == period
            val chip = pillButton(p.title, if (on) DriveUi.ink else DriveUi.card2, if (on) DriveUi.night1 else DriveUi.ink2) {
                period = p; drawChips(); loadStats()
            }
            chips.addView(chip, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i > 0) marginStart = dp(this@HomeActivity, 8f)
            })
        }
    }

    private fun periodStart(p: Period): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        when (p) {
            Period.TODAY -> {}
            // Weeks start on Monday (as in the UAE).
            Period.WEEK -> add(Calendar.DAY_OF_YEAR, -((get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY + 7) % 7))
            Period.MONTH -> set(Calendar.DAY_OF_MONTH, 1)
            Period.YEAR -> set(Calendar.DAY_OF_YEAR, 1)
        }
    }.timeInMillis

    private fun loadStats() {
        val p = period
        val from = periodStart(p)
        val db = TripDb.get(this)
        io.execute {
            val trips = runCatching { db.tripsSince(from) }.getOrDefault(emptyList())
            main.post {
                if (isFinishing || p != period) return@post
                // The running trip in memory is fresher than its saved copy.
                val live = TripRecorder.current
                val list = trips.map { if (live != null && it.id == live.id) live.copy() else it }
                showSummary(p, list)
                showTrips(list)
            }
        }
    }

    private fun showSummary(p: Period, trips: List<Trip>) {
        summary.removeAllViews()
        val dist = trips.sumOf { it.distanceM }
        val moving = trips.sumOf { it.movingMs }
        val slow = trips.sumOf { it.slowMs }
        val idle = trips.sumOf { it.idleMs }
        val tracked = moving + slow + idle
        val total = trips.sumOf { it.durationMs }
        val waitShare = if (tracked > 0) (slow + idle).toFloat() / tracked else 0f
        val wheelsMs = moving + slow
        val avg = if (wheelsMs > 30_000) (dist / (wheelsMs / 1000.0) * 3.6).toFloat() else 0f
        val top = trips.maxOfOrNull { it.topKmh } ?: 0f
        val rated = tripPrefs.ratedKmpl
        val litres = trips.sumOf { Fuel.litres(it, rated) }
        val real = Fuel.kmpl(dist, litres)

        val heading = when (p) { Period.TODAY -> "Today"; Period.WEEK -> "This week"; Period.MONTH -> "This month"; Period.YEAR -> "This year" }
        summary.addView(DriveUi.text(this, heading, 14f, DriveUi.ink2, "medium"))
        val big = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        big.addView(DriveUi.text(this, "%.1f".format(dist / 1000), 44f, DriveUi.ink, "display"))
        big.addView(DriveUi.text(this, " km · ${trips.size} ${if (trips.size == 1) "trip" else "trips"} · ${DriveUi.duration(total)}", 14f, DriveUi.ink2, "medium")
            .apply { setPadding(0, 0, 0, dp(context, 8f)) })
        summary.addView(big, rowParams(6f))
        if (trips.isEmpty()) {
            summary.addView(DriveUi.text(this, if (tripPrefs.enabled) "No drives yet. A trip starts by itself when Android Auto connects and ends when it disconnects."
            else "Trip log is off. Turn it on below to record your drives.", 13f, DriveUi.ink3).apply { setPadding(0, dp(context, 8f), 0, 0) })
            return
        }
        val bar = TimeBar(this).apply { set(moving, slow, idle) }
        summary.addView(bar, rowParams(14f))
        summary.addView(DriveUi.legend(this, moving, slow, idle))
        summary.addView(DriveUi.grid(this, listOf(
            DriveUi.stat(this, "Driving", DriveUi.duration(moving), DriveUi.teal, "20 km/h and up"),
            DriveUi.stat(this, "Waiting in traffic", DriveUi.duration(slow + idle), DriveUi.waitColor(waitShare),
                "${(waitShare * 100).toInt()}% · ${DriveUi.waitWord(waitShare)}"),
            DriveUi.stat(this, "Average speed", DriveUi.kmh(avg), sub = "while moving"),
            DriveUi.stat(this, "Top speed", DriveUi.kmh(top)),
            DriveUi.stat(this, "Petrol (rough)", "%.1f L".format(litres), DriveUi.sand, "≈ AED %.0f".format(litres * tripPrefs.fuelPrice)),
            DriveUi.stat(this, "Real economy", if (real > 0) "%.1f km/L".format(real) else "–", sub = "rated %.0f km/L".format(rated)),
        )), rowParams(14f))
    }

    private fun showTrips(trips: List<Trip>) {
        tripList.removeAllViews()
        if (trips.isEmpty()) {
            tripList.addView(DriveUi.text(this, "Nothing in this period.", 13f, DriveUi.ink3).apply { setPadding(dp(context, 4f), dp(context, 10f), 0, 0) })
            return
        }
        trips.take(100).forEach { tripList.addView(tripRow(it)) }
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
        body.addView(DriveUi.text(context, "${DriveUi.day(t.startAt)} · ${DriveUi.time(context, t.startAt)} → $end", 15f, DriveUi.ink, "bold"))
        body.addView(DriveUi.text(context, "${DriveUi.duration(t.durationMs)} · ${DriveUi.km(t.distanceM)} · avg ${DriveUi.kmh(t.avgMovingKmh)}", 13f, DriveUi.ink2)
            .apply { setPadding(0, dp(context, 6f), 0, 0) })
        body.addView(TimeBar(context).apply { set(t.movingMs, t.slowMs, t.idleMs) }, rowParams(10f))
        addView(body, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val pill = DriveUi.text(context, "Wait\n${(t.waitShare * 100).toInt()}%", 12f, waitC, "bold").apply {
            gravity = Gravity.CENTER
            setPadding(dp(context, 10f), dp(context, 8f), dp(context, 10f), dp(context, 8f))
            background = DriveUi.rounded(DriveUi.card2, dp(context, 12f).toFloat(), waitC, dp(context, 1f))
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
        addView(numberField("Petrol price (AED per litre)", tripPrefs.fuelPrice) { tripPrefs.fuelPrice = it; loadStats() }, rowParams(10f))
        addView(DriveUi.text(context, "Petrol is a rough estimate: real driving gets about 85% of the rated figure, slow traffic about 55%, and idling burns about 0.6 L an hour.", 12f, DriveUi.ink3)
            .apply { setPadding(0, dp(context, 8f), 0, 0) })
        addView(pillButton("Delete all trips", DriveUi.card2, DriveUi.red) {
            AlertDialog.Builder(this@HomeActivity)
                .setTitle("Delete all trips?")
                .setMessage("This removes every saved drive from this phone.")
                .setPositiveButton("Delete") { _, _ -> io.execute { TripDb.get(this@HomeActivity).deleteAll(); main.post { loadStats() } } }
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

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
