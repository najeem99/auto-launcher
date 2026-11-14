package com.najib.carwelcome.drive

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
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
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.najib.carwelcome.drive.DriveUi.dp
import java.util.Locale
import kotlin.concurrent.thread

/**
 * "Where your time goes": the signals and spots where the car keeps standing, the roads
 * the time is spent on, how long it takes to get to each place (home, office…) and how
 * long you stay there. Worked out on the phone from the positions saved with each trip.
 */
class PlacesActivity : AppCompatActivity() {

    private enum class Range(val title: String, val days: Int) {
        WEEK("7 days", 7), MONTH("30 days", 30), QUARTER("90 days", 90), ALL("All", 0)
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var names: PlaceNames
    private lateinit var chips: LinearLayout
    private lateinit var body: LinearLayout
    private var range = Range.MONTH
    private var report: PlaceStats.Report? = null
    /** Bumped on every reload, so an old background job stops. */
    @Volatile private var generation = 0
    @Volatile private var naming = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        window.statusBarColor = DriveUi.night1
        window.navigationBarColor = DriveUi.night2
        names = PlaceNames(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 16f), dp(context, 20f), dp(context, 16f), dp(context, 32f))
        }
        column.addView(DriveUi.text(this, "‹  Home", 15f, DriveUi.ink2, "bold").apply {
            setPadding(0, dp(context, 4f), dp(context, 16f), dp(context, 12f))
            setOnClickListener { finish() }
        })
        column.addView(DriveUi.text(this, "Where your time goes", 26f, DriveUi.ink, "display"))
        column.addView(DriveUi.text(this, "Signals and spots you wait at, the roads you spend time on, how long it takes to get places and how long you stay.", 14f, DriveUi.ink2)
            .apply { setPadding(0, dp(context, 8f), 0, 0) })
        chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        column.addView(chips, params(16f))
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(body)
        setContentView(ScrollView(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(DriveUi.night1, DriveUi.night2))
            isFillViewport = true
            addView(column)
        })
        drawChips()
        load()
    }

    override fun onDestroy() {
        generation++
        super.onDestroy()
    }

    private fun drawChips() {
        chips.removeAllViews()
        Range.values().forEachIndexed { i, r ->
            val on = r == range
            chips.addView(PlacesUi.pill(this, r.title, if (on) DriveUi.ink else DriveUi.card2, if (on) DriveUi.night1 else DriveUi.ink2) {
                range = r; drawChips(); load()
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = dp(this@PlacesActivity, 8f) })
        }
    }

    private fun load() {
        val gen = ++generation
        body.removeAllViews()
        body.addView(DriveUi.text(this, "Working it out…", 14f, DriveUi.ink3).apply { setPadding(dp(context, 4f), dp(context, 20f), 0, 0) })
        val from = if (range.days == 0) 0L else System.currentTimeMillis() - range.days * 86_400_000L
        val ctx = applicationContext
        thread {
            val db = TripDb.get(ctx)
            val r = runCatching { PlaceStats.analyse(db.tripsSince(from), db.geoPoints(from)) }.getOrNull()
            main.post {
                if (gen != generation || isFinishing) return@post
                report = r
                render()
                if (r != null) nameThings(gen, r)
            }
        }
    }

    /** Looks up street names for what's on screen (most time first), refreshing as they come in. */
    private fun nameThings(gen: Int, r: PlaceStats.Report) {
        if (!names.canLookUp) return
        val todo = mutableListOf<Triple<String, Double, Double>>()
        r.places.take(30).forEach { todo += Triple(names.spotKey(it.lat, it.lon), it.lat, it.lon) }
        r.spots.take(15).forEach { todo += Triple(names.spotKey(it.lat, it.lon), it.lat, it.lon) }
        r.cells.sortedByDescending { it.ms }.take(MAX_CELLS).forEach { todo += Triple(it.key, it.lat, it.lon) }
        val missing = todo.distinctBy { it.first }.filter { !names.isKnown(it.first) }
        if (missing.isEmpty()) return
        naming = missing.size
        render()
        thread {
            var sinceDraw = 0
            var failures = 0
            for ((key, lat, lon) in missing) {
                if (gen != generation) return@thread
                if (names.lookUp(key, lat, lon)) failures = 0 else if (++failures >= 5) break // offline
                naming--
                if (++sinceDraw >= 12) { sinceDraw = 0; main.post { if (gen == generation) render() } }
            }
            naming = 0
            main.post { if (gen == generation) render() }
        }
    }

    private fun render() {
        val r = report
        body.removeAllViews()
        if (r == null) {
            body.addView(note("Couldn't read the trip log."))
            return
        }
        if (r.tripsWithPlaces == 0) {
            body.addView(DriveUi.card(this).apply {
                addView(DriveUi.label(context, "Nothing yet"))
                addView(DriveUi.text(context, "From this version on, each trip also keeps where the car was. Drive a few trips with Najeem Drive connected and this page fills in: the signals you wait at, the roads you spend time on, how long it takes to get home, and how long you stay places.", 14f, DriveUi.ink2)
                    .apply { setPadding(0, dp(context, 10f), 0, 0) })
                if (r.trips > 0) addView(DriveUi.text(context, "${r.trips} older trips in this range were recorded before positions were kept.", 12f, DriveUi.ink3)
                    .apply { setPadding(0, dp(context, 10f), 0, 0) })
            })
            return
        }
        body.addView(summary(r))
        body.addView(spotsCard(r))
        body.addView(roadsCard(r))
        body.addView(gettingThereCard(r))
        body.addView(staysCard(r))
        body.addView(note(buildString {
            append("Tap a spot to see it on the map, or a place to give it your own name. ")
            append("Positions stay on this phone; street names come from the phone's address lookup. ")
            if (r.trips > r.tripsWithPlaces) append("${r.trips - r.tripsWithPlaces} older trips have no positions and aren't counted.")
        }))
    }

    private fun summary(r: PlaceStats.Report): View = DriveUi.card(this).apply {
        addView(DriveUi.label(context, "In the last ${if (range.days == 0) "all trips" else range.title}"))
        val share = if (r.trackedMs > 0) r.standMs.toFloat() / r.trackedMs else 0f
        addView(DriveUi.text(context, DriveUi.duration(r.trackedMs) + " on the road", 24f, DriveUi.ink, "display").apply { setPadding(0, dp(context, 10f), 0, 0) })
        addView(DriveUi.text(context, "${DriveUi.duration(r.standMs)} of it standing still (${(share * 100).toInt()}%)", 14f, DriveUi.red, "bold")
            .apply { setPadding(0, dp(context, 6f), 0, 0) })
        addView(DriveUi.grid(context, listOf(
            DriveUi.stat(context, "Trips", r.tripsWithPlaces.toString()),
            DriveUi.stat(context, "Stop spots", r.spots.size.toString(), DriveUi.red),
            DriveUi.stat(context, "Places", r.places.size.toString(), DriveUi.sand),
        ), cols = 3), params(14f))
    }

    private fun spotsCard(r: PlaceStats.Report): View = DriveUi.card(this).apply {
        addView(DriveUi.label(context, "Where you wait"))
        addView(DriveUi.text(context, "Signals, junctions and jams where the car stood still 10 s or more.", 12f, DriveUi.ink3).apply { setPadding(0, dp(context, 6f), 0, 0) })
        val top = r.spots.take(10)
        if (top.isEmpty()) { addView(DriveUi.text(context, "No stops yet.", 14f, DriveUi.ink2).apply { setPadding(0, dp(context, 12f), 0, 0) }); return@apply }
        val max = top.first().totalMs.toFloat()
        top.forEach { s ->
            val sub = "${times(s.count, "stop")} on ${times(s.trips, "trip")} · usually ${DriveUi.duration(s.avgMs)} · longest ${DriveUi.duration(s.longestMs)}"
            addView(PlacesUi.row(context, names.spotLabel(s.lat, s.lon), sub, DriveUi.duration(s.totalMs), DriveUi.red, s.totalMs / max, DriveUi.red) {
                openMap(s.lat, s.lon)
            }, params(10f))
        }
    }

    private fun roadsCard(r: PlaceStats.Report): View = DriveUi.card(this).apply {
        addView(DriveUi.label(context, "Roads"))
        val (roads, unknownMs) = r.roads { names.roadOf(it) }
        val top = roads.take(10)
        if (top.isEmpty()) {
            addView(DriveUi.text(context, if (naming > 0) "Finding the road names…" else if (!names.canLookUp) "This phone can't look up street names." else "No road names found yet (needs internet once).",
                14f, DriveUi.ink2).apply { setPadding(0, dp(context, 12f), 0, 0) })
        } else {
            addView(DriveUi.text(context, "Time on each road; the orange part is waiting (under 20 km/h).", 12f, DriveUi.ink3).apply { setPadding(0, dp(context, 6f), 0, 0) })
            val max = top.first().ms.toFloat()
            top.forEach { road ->
                val sub = "${times(road.trips, "trip")} · ${(road.waitShare * 100).toInt()}% waiting (${DriveUi.duration(road.waitMs)}) · avg ${DriveUi.kmh(road.avgKmh)}"
                addView(PlacesUi.row(context, road.name, sub, DriveUi.duration(road.ms), DriveUi.ink, road.ms / max, DriveUi.teal, road.waitShare), params(10f))
            }
        }
        val extra = when {
            naming > 0 -> "Naming $naming more spots…"
            unknownMs > 60_000 -> "${DriveUi.duration(unknownMs)} on roads without a name yet."
            else -> null
        }
        if (extra != null) addView(DriveUi.text(context, extra, 12f, DriveUi.ink3).apply { setPadding(0, dp(context, 10f), 0, 0) })
    }

    private fun gettingThereCard(r: PlaceStats.Report): View = DriveUi.card(this).apply {
        addView(DriveUi.label(context, "Getting there"))
        addView(DriveUi.text(context, "How long the drive takes to each place, from pulling away to arriving.", 12f, DriveUi.ink3).apply { setPadding(0, dp(context, 6f), 0, 0) })
        val list = r.places.filter { it.arrivals.isNotEmpty() }.sortedByDescending { it.arrivals.size }.take(10)
        if (list.isEmpty()) { addView(DriveUi.text(context, "No finished drives between places yet.", 14f, DriveUi.ink2).apply { setPadding(0, dp(context, 12f), 0, 0) }); return@apply }
        val max = list.maxOf { it.avgDriveMs }.toFloat().coerceAtLeast(1f)
        list.forEach { p ->
            val fast = p.fastest!!.driveMs
            val slow = p.slowest!!.driveMs
            val from = p.arrivals.mapNotNull { it.from }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
            val sub = buildString {
                append(times(p.arrivals.size, "drive"))
                if (p.arrivals.size > 1) append(" · ${DriveUi.duration(fast)} to ${DriveUi.duration(slow)}")
                append(" · waiting ${DriveUi.duration(p.avgWaitMs)} on average")
                if (from != null) append(" · mostly from ${names.placeLabel(from)}")
            }
            val waitShare = if (p.avgDriveMs > 0) p.avgWaitMs.toFloat() / p.avgDriveMs else 0f
            addView(PlacesUi.row(context, "To " + names.placeLabel(p), sub, DriveUi.duration(p.avgDriveMs), DriveUi.sand, p.avgDriveMs / max, DriveUi.teal, waitShare) {
                rename(p)
            }, params(10f))
        }
    }

    private fun staysCard(r: PlaceStats.Report): View = DriveUi.card(this).apply {
        addView(DriveUi.label(context, "Where you stay"))
        addView(DriveUi.text(context, "Time parked at each place between drives.", 12f, DriveUi.ink3).apply { setPadding(0, dp(context, 6f), 0, 0) })
        val list = r.places.filter { it.stays.isNotEmpty() }.sortedByDescending { it.stayMs }.take(10)
        if (list.isEmpty()) { addView(DriveUi.text(context, "Shows up after you drive away from a place you drove to.", 14f, DriveUi.ink2).apply { setPadding(0, dp(context, 12f), 0, 0) }); return@apply }
        val max = list.first().stayMs.toFloat()
        list.forEach { p ->
            val sub = "${times(p.stays.size, "visit")} · ${DriveUi.duration(p.avgStayMs)} each on average"
            addView(PlacesUi.row(context, names.placeLabel(p), sub, PlacesUi.longDuration(p.stayMs), DriveUi.sand, p.stayMs / max, DriveUi.sand) {
                rename(p)
            }, params(10f))
        }
    }

    private fun rename(p: PlaceStats.Place) {
        val input = EditText(this).apply {
            setText(names.customName(p.lat, p.lon) ?: p.guess.orEmpty())
            hint = "e.g. Home, Office, Gym"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setSelection(text.length)
        }
        val box = LinearLayout(this).apply {
            setPadding(dp(context, 20f), dp(context, 8f), dp(context, 20f), 0)
            addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(this)
            .setTitle("Name this place")
            .setMessage(names.spotLabel(p.lat, p.lon))
            .setView(box)
            .setPositiveButton("Save") { _, _ -> names.setCustomName(p.lat, p.lon, input.text.toString()); render() }
            .setNeutralButton("Map") { _, _ -> openMap(p.lat, p.lon) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openMap(lat: Double, lon: Double) {
        val ll = String.format(Locale.ROOT, "%.6f,%.6f", lat, lon)
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$ll?q=$ll"))) }
    }

    private fun note(s: String) = DriveUi.text(this, s, 12f, DriveUi.ink3).apply { setPadding(dp(context, 4f), dp(context, 16f), dp(context, 4f), 0) }

    private fun times(n: Int, word: String) = "$n $word" + if (n == 1) "" else "s"

    private fun params(topDp: Float) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .apply { topMargin = dp(this@PlacesActivity, topDp) }

    private companion object { const val MAX_CELLS = 400 }
}

/** Rows shared by the places page and the trip page. */
object PlacesUi {
    fun pill(context: Context, title: String, bg: Int, fg: Int, onClick: () -> Unit): TextView =
        DriveUi.text(context, title, 14f, fg, "bold").apply {
            gravity = Gravity.CENTER
            setPadding(dp(context, 10f), dp(context, 10f), dp(context, 10f), dp(context, 10f))
            background = DriveUi.rounded(bg, dp(context, 14f).toFloat())
            setOnClickListener { onClick() }
        }

    /**
     * Title and detail on the left, a big value on the right, and a thin bar under it
     * ([frac] of the width, the last [orangeShare] of it orange for waiting).
     */
    fun row(
        context: Context, title: String, sub: String, value: String, valueColor: Int,
        frac: Float, barColor: Int, orangeShare: Float = 0f, onClick: (() -> Unit)? = null,
    ): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val p = dp(context, 12f)
        setPadding(p, p, p, p)
        background = DriveUi.rounded(DriveUi.card2, dp(context, 14f).toFloat())
        val top = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(DriveUi.text(context, title, 15f, DriveUi.ink, "bold").apply { maxLines = 2 },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(DriveUi.text(context, value, 17f, valueColor, "display").apply { setPadding(dp(context, 10f), 0, 0, 0) })
        addView(top)
        addView(DriveUi.text(context, sub, 12f, DriveUi.ink2).apply { setPadding(0, dp(context, 6f), 0, 0) })
        val bar = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val f = frac.coerceIn(0.02f, 1f)
        val o = orangeShare.coerceIn(0f, 1f)
        bar.addView(View(context).apply { background = DriveUi.rounded(barColor, dp(context, 3f).toFloat()) },
            LinearLayout.LayoutParams(0, dp(context, 5f), f * (1 - o)))
        if (o > 0f) bar.addView(View(context).apply { background = DriveUi.rounded(DriveUi.orange, dp(context, 3f).toFloat()) },
            LinearLayout.LayoutParams(0, dp(context, 5f), f * o))
        bar.addView(View(context), LinearLayout.LayoutParams(0, dp(context, 5f), 1 - f))
        addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(context, 8f) })
        if (onClick != null) { isClickable = true; setOnClickListener { onClick() } }
    }

    /** Like [DriveUi.duration] but in days for long stays. */
    fun longDuration(ms: Long): String {
        val h = ms / 3_600_000
        return if (h >= 48) "${h / 24} d ${h % 24} h" else DriveUi.duration(ms)
    }
}
