package com.najib.carwelcome.car

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import androidx.car.app.CarContext
import com.najib.carwelcome.Prefs
import com.najib.carwelcome.drive.DriveUi
import com.najib.carwelcome.drive.Trip
import com.najib.carwelcome.drive.TripDb
import com.najib.carwelcome.drive.TripPrefs
import com.najib.carwelcome.drive.TripRecorder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Live stats for the current trip, made to be read at a glance: a big ride timer
 * with the traffic share under it, and four large numbers (average, top speed,
 * slowest moving speed, time spent waiting). No dial, so it doesn't look like the
 * speedometer. Landscape puts the timer on the left and the numbers on the right;
 * a narrow split-screen panel stacks them.
 */
class TripStatsRenderer(carContext: CarContext) : CanvasLayer(carContext) {

    private val prefs = Prefs(carContext)

    /** Slowest 15-second average while moving, read from the saved speed points now and then. */
    private var slowTripId = -1L
    private var slowReadAt = 0L
    private var slowest = 0f

    override fun frameKey(): String {
        val t = TripRecorder.current
        return listOf(
            t?.id, t?.let { (System.currentTimeMillis() - it.startAt) / 1000 }, t?.distanceM?.div(100)?.toInt(),
            t?.topKmh?.toInt(), t?.avgKmh?.toInt(), t?.waitMs?.div(1000), (t?.waitShare ?: 0f).times(100).toInt(),
            slowest.toInt(), TripPrefs(carContext).enabled, prefs.use24Hour,
        ).joinToString("|")
    }

    override fun beforeFrame() {
        val t = TripRecorder.current ?: run { slowest = 0f; slowTripId = -1L; return }
        val now = System.currentTimeMillis()
        if (t.id == slowTripId && now - slowReadAt < 15_000) return
        slowTripId = t.id
        slowReadAt = now
        // Under 5 km/h counts as standing still, not as driving slowly.
        slowest = runCatching {
            TripDb.get(carContext).points(t.id).filter { it.kmh >= 5f }.minOfOrNull { it.kmh }
        }.getOrNull() ?: 0f
    }

    override fun render(canvas: Canvas, area: Rect) {
        val narrow = area.width() < area.height() * 1.25f || area.width() / density < 480
        val k = if (narrow) area.width() / 46f else min(area.width() / 92f, area.height() / 52f)
        val padX = (if (narrow) 2.2f else 3.2f) * k
        val padY = (if (narrow) 2.6f else 3f) * k
        val r = RectF(area.left + padX, area.top + padY, area.right - padX, area.bottom - padY)
        val trip = TripRecorder.current

        // Title row: "Trip" and a live dot with the start time. Android Auto's back button sits top-right.
        val titleH = 5.4f * k
        text.textAlign = Paint.Align.LEFT
        text.typeface = look.sora500
        text.letterSpacing = -0.01f
        text.textSize = (if (narrow) 3f else 3.4f) * k
        text.color = look.ink
        val base = r.top + titleH / 2 + text.textSize * 0.36f
        canvas.drawText("Trip", r.left, base, text)
        val titleW = text.measureText("Trip")
        text.letterSpacing = 0f
        text.typeface = look.manrope600
        text.textSize = 2f * k
        val x = r.left + titleW + 1.8f * k
        val rr = 0.55f * k
        paint.color = if (trip != null) look.red else look.ink3
        canvas.drawCircle(x + rr, base - text.textSize * 0.35f, rr, paint)
        text.color = look.ink2
        val status = if (trip != null) "Live" else "Not recording"
        line(canvas, status, x + rr * 2 + 0.7f * k, base, r.width() * 0.6f - titleW)

        val body = RectF(r.left, r.top + titleH + 2.2f * k, r.right, r.bottom)
        if (body.height() < 40 * density) return
        val gap = (if (narrow) 1.2f else 1.6f) * k
        if (narrow) {
            val hero = RectF(body.left, body.top, body.right, body.top + (body.height() - gap) * 0.46f)
            drawHero(canvas, hero, trip, k)
            drawGrid(canvas, RectF(body.left, hero.bottom + gap, body.right, body.bottom), trip, gap, k)
        } else {
            val hero = RectF(body.left, body.top, body.left + (body.width() - gap) * 0.47f, body.bottom)
            drawHero(canvas, hero, trip, k)
            drawGrid(canvas, RectF(hero.right + gap, body.top, body.right, body.bottom), trip, gap, k)
        }
    }

    // ---- The ride timer and traffic -------------------------------------------------

    private fun drawHero(canvas: Canvas, r: RectF, trip: Trip?, k: Float) {
        val radius = 2.2f * k
        card(canvas, r, radius, fill = Color.argb(242, 15, 23, 40))
        val share = trip?.waitShare ?: 0f
        val tone = if (trip != null && trip.trackedMs > 0) DriveUi.waitColor(share) else look.teal
        paint.shader = RadialGradient(r.left, r.top, max(r.width(), r.height()) * 0.9f,
            Color.argb(30, Color.red(tone), Color.green(tone), Color.blue(tone)), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(r, radius, radius, paint)
        paint.shader = null

        val pad = min(2.4f * k, min(r.width(), r.height()) * 0.09f)
        val inner = RectF(r.left + pad, r.top + pad, r.right - pad, r.bottom - pad)
        val split = inner.top + inner.height() * 0.56f
        val top = RectF(inner.left, inner.top, inner.right, split)
        val bottom = RectF(inner.left, split, inner.right, inner.bottom)
        val cap = min(1.8f * k, top.height() * 0.13f)

        // Top: RIDE TIME, the ticking timer, and "Started 4:12 PM · 23.4 km".
        caps(canvas, "Ride time", top.left, top.top + cap, top.width(), cap)
        val sub = when {
            trip != null -> "Started ${clock(trip.startAt)} · ${DriveUi.km(trip.distanceM)}"
            !TripPrefs(carContext).enabled -> "Trip log is off on the phone"
            else -> "Starts when Android Auto connects"
        }
        val subSize = cap * 1.15f
        val subBase = top.bottom - subSize * 0.6f
        text.typeface = look.manrope600
        text.textSize = subSize
        text.color = look.ink2
        line(canvas, sub, top.left, subBase, top.width())

        val timer = trip?.let { elapsed(System.currentTimeMillis() - it.startAt) } ?: "0:00"
        val roomTop = top.top + cap * 1.6f
        val roomBottom = subBase - subSize * 1.5f
        text.typeface = look.sora300
        text.letterSpacing = -0.03f
        text.fontFeatureSettings = "tnum"
        fit(timer, min((roomBottom - roomTop) * 1.05f, 14f * k), top.width())
        text.color = if (trip != null) look.ink else look.ink3
        canvas.drawText(timer, top.left - text.textSize * 0.04f, roomBottom - (roomBottom - roomTop - text.textSize * 0.72f) / 2, text)
        text.fontFeatureSettings = null
        text.letterSpacing = 0f

        // Hairline between the halves.
        paint.color = look.line
        canvas.drawRect(inner.left, split, inner.right, split + max(1f, density * 0.75f), paint)

        if (trip == null) {
            drawStartButton(canvas, bottom, cap, k)
            return
        }

        // Bottom: TRAFFIC, "18%  Smooth", then the moving / crawling / standing bar.
        val bTop = bottom.top + cap * 1.3f
        caps(canvas, "Traffic", bottom.left, bTop + cap, bottom.width(), cap)
        val barH = min(1.5f * k, bottom.height() * 0.11f)
        val legend = cap * 1.05f
        val legendBase = bottom.bottom - legend * 0.2f
        val bar = RectF(bottom.left, legendBase - legend * 1.7f - barH, bottom.right, legendBase - legend * 1.7f)
        val pctRoomTop = bTop + cap * 1.6f
        val pctRoomBottom = bar.top - barH
        val pct = if (trip.trackedMs > 0) "${Math.round(share * 100)}%" else "–"
        text.typeface = look.sora500
        text.letterSpacing = -0.02f
        fit(pct, min((pctRoomBottom - pctRoomTop) * 1.1f, 7f * k), bottom.width() * 0.5f)
        val pctBase = pctRoomBottom - (pctRoomBottom - pctRoomTop - text.textSize * 0.72f) / 2
        text.color = tone
        canvas.drawText(pct, bottom.left, pctBase, text)
        val pctW = text.measureText(pct)
        text.letterSpacing = 0f
        if (trip.trackedMs > 0) {
            text.typeface = look.manrope700
            text.textSize = min(text.textSize * 0.42f, 2.6f * k)
            text.color = tone
            line(canvas, DriveUi.waitWord(share), bottom.left + pctW + 1.2f * k, pctBase, bottom.width() - pctW - 1.2f * k)
        }

        // Stacked bar.
        val parts = listOf(trip.movingMs to DriveUi.movingColor, trip.slowMs to DriveUi.slowColor, trip.idleMs to DriveUi.idleColor)
        val total = parts.sumOf { it.first }.toFloat()
        paint.color = Color.argb(31, 255, 255, 255)
        canvas.drawRoundRect(bar, barH / 2, barH / 2, paint)
        if (total > 0) {
            canvas.save()
            val clip = android.graphics.Path().apply { addRoundRect(bar, barH / 2, barH / 2, android.graphics.Path.Direction.CW) }
            canvas.clipPath(clip)
            var bx = bar.left
            parts.forEach { (ms, color) ->
                val w = bar.width() * ms / total
                paint.color = color
                canvas.drawRect(bx, bar.top, bx + w, bar.bottom, paint)
                bx += w
            }
            canvas.restore()
        }
        // Legend: three coloured dots with minutes.
        val items = listOf(
            Triple("Moving", trip.movingMs, DriveUi.movingColor),
            Triple("Crawl", trip.slowMs, DriveUi.slowColor),
            Triple("Stopped", trip.idleMs, DriveUi.idleColor),
        )
        val slot = bottom.width() / 3f
        text.typeface = look.manrope600
        text.textSize = legend
        items.forEachIndexed { i, (name, ms, color) ->
            val lx = bottom.left + i * slot
            val dr = legend * 0.3f
            paint.color = color
            canvas.drawCircle(lx + dr, legendBase - legend * 0.35f, dr, paint)
            text.color = look.ink2
            line(canvas, "$name ${short(ms)}", lx + dr * 2 + legend * 0.4f, legendBase, slot - dr * 2 - legend * 0.8f)
        }
    }

    /** No trip running: a big "Start trip" button (only when the trip log is on). */
    private fun drawStartButton(canvas: Canvas, r: RectF, cap: Float, k: Float) {
        if (!TripPrefs(carContext).enabled) {
            text.typeface = look.manrope600
            text.textSize = cap * 1.15f
            text.color = look.ink3
            line(canvas, "Turn it on in Najeem Drive on your phone", r.left, r.centerY() + cap * 0.4f, r.width())
            return
        }
        val h = min(r.height() * 0.5f, 7f * k)
        val b = RectF(r.left, r.centerY() - h / 2, r.left + min(r.width(), 26f * k), r.centerY() + h / 2)
        paint.color = look.teal
        canvas.drawRoundRect(b, h / 2, h / 2, paint)
        text.typeface = look.manrope700
        text.textAlign = Paint.Align.CENTER
        text.textSize = h * 0.38f
        text.color = Color.parseColor("#0B1220")
        canvas.drawText("Start trip", b.centerX(), b.centerY() + text.textSize * 0.36f, text)
        text.textAlign = Paint.Align.LEFT
        onTap(b) {
            TripRecorder.onCarSeen(carContext)
            kick()
        }
    }

    // ---- The four big numbers -----------------------------------------------------------

    private data class Stat(val label: String, val value: String, val unit: String, val accent: Int)

    private fun stats(t: Trip?): List<Stat> {
        fun speed(v: Float) = if (t == null || v < 1f) "–" else Math.round(v).toString()
        val wait = t?.waitMs ?: 0L
        val (waitValue, waitUnit) = when {
            t == null -> "–" to ""
            wait < 3_600_000 -> ((wait + 30_000) / 60_000).toString() to "min"
            else -> "${wait / 3_600_000}:${"%02d".format(Locale.US, (wait % 3_600_000) / 60_000)}" to "h"
        }
        return listOf(
            Stat("Average", speed(t?.avgKmh ?: 0f), "km/h", look.teal),
            Stat("Top speed", speed(t?.topKmh ?: 0f), "km/h", look.sand),
            Stat("Slowest moving", speed(slowest), "km/h", DriveUi.slowColor),
            Stat("Waiting", waitValue, waitUnit, DriveUi.idleColor),
        )
    }

    private fun drawGrid(canvas: Canvas, r: RectF, t: Trip?, gap: Float, k: Float) {
        val w = (r.width() - gap) / 2
        val h = (r.height() - gap) / 2
        stats(t).forEachIndexed { i, s ->
            val x = r.left + (i % 2) * (w + gap)
            val y = r.top + (i / 2) * (h + gap)
            drawStat(canvas, RectF(x, y, x + w, y + h), s, k)
        }
    }

    private fun drawStat(canvas: Canvas, r: RectF, s: Stat, k: Float) {
        val radius = min(2.2f * k, r.height() * 0.2f)
        card(canvas, r, radius)
        val pad = min(2f * k, min(r.width(), r.height()) * 0.12f)
        // Coloured accent down the left edge.
        val aw = max(0.5f * k, 3f)
        paint.color = s.accent
        canvas.drawRoundRect(RectF(r.left + pad * 0.5f, r.top + pad, r.left + pad * 0.5f + aw, r.bottom - pad), aw / 2, aw / 2, paint)

        val left = r.left + pad * 0.5f + aw + pad * 0.7f
        val textW = r.right - pad - left
        val cap = min(1.7f * k, r.height() * 0.14f)
        caps(canvas, s.label, left, r.top + pad + cap * 0.9f, textW, cap)

        val roomTop = r.top + pad + cap * 1.6f
        val roomBottom = r.bottom - pad
        text.typeface = look.sora300
        text.letterSpacing = -0.03f
        text.fontFeatureSettings = "tnum"
        val unitRoom = if (s.unit.isEmpty()) 0f else textW * 0.3f
        fit(s.value, min((roomBottom - roomTop) * 1.1f, 9f * k), textW - unitRoom)
        val numSize = text.textSize
        val baseline = roomBottom - (roomBottom - roomTop - numSize * 0.72f) / 2
        text.color = if (s.value == "–") look.ink3 else look.ink
        canvas.drawText(s.value, left, baseline, text)
        val vw = text.measureText(s.value)
        text.fontFeatureSettings = null
        text.letterSpacing = 0f
        if (s.unit.isNotEmpty() && s.value != "–") {
            text.typeface = look.manrope600
            text.textSize = min(numSize * 0.3f, 2.2f * k)
            text.color = look.ink2
            line(canvas, s.unit, left + vw + text.textSize * 0.4f, baseline, textW - vw - text.textSize * 0.4f)
        }
    }

    // ---- Helpers ---------------------------------------------------------------------------

    /** Small spaced capitals in the third ink colour. */
    private fun caps(canvas: Canvas, s: String, x: Float, baseline: Float, maxW: Float, size: Float) {
        text.typeface = look.manrope700
        text.textSize = size
        text.color = look.ink3
        text.letterSpacing = 0.1f
        line(canvas, s.uppercase(Locale.getDefault()), x, baseline, maxW)
        text.letterSpacing = 0f
    }

    /** Sets the text size to [size], shrunk so [s] fits in [maxW]. */
    private fun fit(s: String, size: Float, maxW: Float) {
        text.textSize = max(size, 1f)
        val w = text.measureText(s)
        if (w > maxW && maxW > 0f) text.textSize *= maxW / w
    }

    private fun clock(ms: Long): String {
        val fmt = if (prefs.use24Hour) "H:mm" else "h:mm a"
        return SimpleDateFormat(fmt, Locale.getDefault()).format(Date(ms))
    }

    /** 42:05 under an hour, 1:02:05 after. */
    private fun elapsed(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
        else String.format(Locale.US, "%d:%02d", m, sec)
    }

    /** "8m", "1h 5m". */
    private fun short(ms: Long): String {
        val min = (ms + 30_000) / 60_000
        return if (min < 60) "${min}m" else "${min / 60}h ${min % 60}m"
    }
}
