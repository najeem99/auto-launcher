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
        val topRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        topRow.addView(big, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        topRow.addView(DriveUi.text(this, DriveUi.emoji(t.waitShare), 44f))
        head.addView(topRow)
        head.addView(TimeBar(this).apply { set(t.movingMs, t.slowMs, t.idleMs) }, params(14f))
        head.addView(DriveUi.legend(this, t.movingMs, t.slowMs, t.idleMs))
        head.addView(DriveUi.text(this, "Waiting ${DriveUi.duration(t.waitMs)} · ${(t.waitShare * 100).toInt()}% of the trip · ${DriveUi.waitWord(t.waitShare)}", 14f, waitC, "bold")
            .apply { setPadding(0, dp(context, 12f), 0, 0) })
        column.addView(head)

        val graph = DriveUi.card(this)
        graph.addView(DriveUi.label(this, "Speed through the ride"))
        graph.addView(SpeedGraph(this).apply { set(points) }, params(12f))
        graph.addView(DriveUi.text(this, "High = fast, low = slow traffic or stopped. Drag across the graph to see the speed at any moment.", 12f, DriveUi.ink3)
            .apply { setPadding(0, dp(context, 10f), 0, 0) })
        column.addView(graph)

        val prefs = TripPrefs(this)
        val litres = Fuel.litres(t, prefs.ratedKmpl)
        val real = Fuel.kmpl(t.distanceM, litres)
        column.addView(calculation(t, prefs, litres))
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
                            thread { TripDb.get(this@TripActivity).delete(t.id); TripBackup.backupSoon(this@TripActivity); main.post { finish() } }
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }, params(20f))
        }
    }

    /** Shows how the waiting time, the mood, the average and the petrol were worked out. */
    private fun calculation(t: Trip, prefs: TripPrefs, litres: Double): LinearLayout {
        val c = DriveUi.card(this)
        c.addView(DriveUi.label(this, "How it's worked out"))
        fun line(name: String, value: String, color: Int = DriveUi.ink, bold: Boolean = false) {
            c.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(context, 10f), 0, 0)
                addView(DriveUi.text(context, name, 14f, DriveUi.ink2), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(DriveUi.text(context, value, 14f, color, if (bold) "bold" else "medium").apply { gravity = Gravity.END })
            })
        }
        fun divider() = c.addView(android.view.View(this).apply { setBackgroundColor(DriveUi.line) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(this, 1f)).apply { topMargin = dp(this@TripActivity, 12f) })
        val d = DriveUi::duration
        line("Time in the car", d(t.durationMs))
        line("Driving (20 km/h and up)", d(t.movingMs), DriveUi.teal)
        line("Slow traffic (5–20 km/h)", d(t.slowMs), DriveUi.orange)
        line("Standing (under 5 km/h)", d(t.idleMs), DriveUi.red)
        if (t.noSignalMs > 0) line("No speed signal", d(t.noSignalMs), DriveUi.ink3)
        divider()
        val waitC = DriveUi.waitColor(t.waitShare)
        line("Waiting = slow traffic + standing", d(t.waitMs), waitC, bold = true)
        line("Waiting ÷ measured time", "${d(t.waitMs)} ÷ ${d(t.trackedMs)} = ${(t.waitShare * 100).toInt()}%", waitC, bold = true)
        line("Result", "${DriveUi.emoji(t.waitShare)}  ${DriveUi.waitWord(t.waitShare)}", waitC, bold = true)
        c.addView(DriveUi.text(this, "\uD83D\uDE04 under 25% waiting   \uD83D\uDE10 25–45%   \uD83D\uDE23 over 45%", 12f, DriveUi.ink3)
            .apply { setPadding(0, dp(context, 8f), 0, 0) })
        divider()
        val wheels = t.movingMs + t.slowMs
        line("Average speed", "${DriveUi.km(t.distanceM)} ÷ ${d(wheels)} moving = ${DriveUi.kmh(t.avgMovingKmh)}")
        val rated = prefs.ratedKmpl
        val otherKm = ((t.distanceM - t.movingDistM - t.slowDistM).coerceAtLeast(0.0) + t.movingDistM) / 1000
        line("Petrol, driving", "%.1f km ÷ (%.0f × 0.85)".format(otherKm, rated))
        line("Petrol, slow traffic", "%.1f km ÷ (%.0f × 0.55)".format(t.slowDistM / 1000, rated))
        line("Petrol, standing", "${d(t.idleMs)} × 0.6 L/h")
        line("Petrol total (rough)", "%.2f L ≈ AED %.2f".format(litres, litres * prefs.fuelPrice), DriveUi.sand, bold = true)
        return c
    }

    private fun params(topDp: Float) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .apply { topMargin = dp(this@TripActivity, topDp) }

    companion object {
        const val EXTRA_ID = "trip_id"
    }
}
