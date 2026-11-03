package com.najib.carwelcome.drive

import android.app.AlertDialog
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.najib.carwelcome.drive.DriveUi.dp
import kotlin.concurrent.thread

/** One trip in detail: times, speeds, waiting, petrol and a speed graph. */
class TripActivity : AppCompatActivity() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var column: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        window.statusBarColor = DriveUi.night1
        window.navigationBarColor = DriveUi.night2
        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 16f), dp(context, 20f), dp(context, 16f), dp(context, 32f))
        }
        setContentView(ScrollView(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(DriveUi.night1, DriveUi.night2))
            isFillViewport = true
            addView(column)
        })
        val id = intent.getLongExtra(EXTRA_ID, 0L)
        val db = TripDb.get(this)
        thread {
            val trip = runCatching { db.trip(id) }.getOrNull()
            val points = runCatching { db.points(id) }.getOrDefault(emptyList())
            main.post {
                if (isFinishing) return@post
                val live = TripRecorder.current?.takeIf { it.id == id }?.copy()
                val t = live ?: trip
                if (t == null) { finish(); return@post }
                show(t, points)
            }
        }
    }

    private fun show(t: Trip, points: List<SpeedPoint>) {
        val back = DriveUi.text(this, "‹  Trips", 15f, DriveUi.ink2, "bold").apply {
            setPadding(0, dp(context, 4f), dp(context, 16f), dp(context, 12f))
            setOnClickListener { finish() }
        }
        column.addView(back)
        column.addView(DriveUi.text(this, DriveUi.day(t.startAt), 26f, DriveUi.ink, "display"))
        val end = t.endAt?.let { DriveUi.time(this, it) } ?: "still driving"
        column.addView(DriveUi.text(this, "Connected ${DriveUi.time(this, t.startAt)} · disconnected $end", 14f, DriveUi.ink2)
            .apply { setPadding(0, dp(context, 8f), 0, 0) })

        val waitC = DriveUi.waitColor(t.waitShare)
        val head = DriveUi.card(this)
        val big = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        big.addView(DriveUi.text(this, "%.1f".format(t.distanceM / 1000), 44f, DriveUi.ink, "display"))
        big.addView(DriveUi.text(this, " km in ${DriveUi.duration(t.durationMs)}", 15f, DriveUi.ink2, "medium").apply {
            setPadding(0, 0, 0, dp(context, 8f))
        })
        head.addView(big)
        head.addView(TimeBar(this).apply { set(t.movingMs, t.slowMs, t.idleMs) }, params(14f))
        head.addView(DriveUi.legend(this, t.movingMs, t.slowMs, t.idleMs))
        head.addView(DriveUi.text(this, "Waiting ${DriveUi.duration(t.waitMs)} · ${(t.waitShare * 100).toInt()}% of the trip · ${DriveUi.waitWord(t.waitShare)}", 14f, waitC, "bold")
            .apply { setPadding(0, dp(context, 12f), 0, 0) })
        column.addView(head)

        val prefs = TripPrefs(this)
        val litres = Fuel.litres(t, prefs.ratedKmpl)
        val real = Fuel.kmpl(t.distanceM, litres)
        val stats = DriveUi.card(this)
        stats.addView(DriveUi.label(this, "Numbers"))
        stats.addView(DriveUi.grid(this, listOf(
            DriveUi.stat(this, "Average speed", DriveUi.kmh(t.avgMovingKmh), sub = "while moving"),
            DriveUi.stat(this, "Overall average", DriveUi.kmh(t.avgKmh), sub = "stops included"),
            DriveUi.stat(this, "Top speed", DriveUi.kmh(t.topKmh)),
            DriveUi.stat(this, "Stops", t.stops.toString(), sub = "10 s or longer"),
            DriveUi.stat(this, "Driving", DriveUi.duration(t.movingMs), DriveUi.teal, "20 km/h and up"),
            DriveUi.stat(this, "Slow traffic", DriveUi.duration(t.slowMs), DriveUi.orange, "5–20 km/h"),
            DriveUi.stat(this, "Standing", DriveUi.duration(t.idleMs), DriveUi.red, "under 5 km/h"),
            DriveUi.stat(this, "No signal", DriveUi.duration(t.noSignalMs), sub = "tunnels, GPS lost"),
            DriveUi.stat(this, "Petrol (rough)", "%.2f L".format(litres), DriveUi.sand, "≈ AED %.2f".format(litres * prefs.fuelPrice)),
            DriveUi.stat(this, "Real economy", if (real > 0) "%.1f km/L".format(real) else "–", sub = "rated %.0f km/L".format(prefs.ratedKmpl)),
        )), params(12f))
        stats.addView(DriveUi.text(this, if (t.carSpeed) "Speed from the car's own speedometer." else "Speed from the phone's GPS.", 12f, DriveUi.ink3)
            .apply { setPadding(0, dp(context, 12f), 0, 0) })
        column.addView(stats)

        val graph = DriveUi.card(this)
        graph.addView(DriveUi.label(this, "Speed"))
        graph.addView(SpeedGraph(this).apply { set(points) }, params(12f))
        column.addView(graph)

        if (t.endAt != null) {
            column.addView(TextView(this).apply {
                text = "Delete this trip"
                textSize = 14f
                setTextColor(DriveUi.red)
                typeface = DriveUi.font(context, "bold")
                gravity = Gravity.CENTER
                setPadding(0, dp(context, 14f), 0, dp(context, 14f))
                background = DriveUi.rounded(DriveUi.card2, dp(context, 14f).toFloat())
                setOnClickListener {
                    AlertDialog.Builder(this@TripActivity)
                        .setTitle("Delete this trip?")
                        .setPositiveButton("Delete") { _, _ ->
                            thread { TripDb.get(this@TripActivity).delete(t.id); main.post { finish() } }
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }, params(20f))
        }
    }

    private fun params(topDp: Float) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .apply { topMargin = dp(this@TripActivity, topDp) }

    companion object {
        const val EXTRA_ID = "trip_id"
    }
}
