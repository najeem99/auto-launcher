package com.najib.carwelcome.car

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.car.app.CarContext
import kotlin.math.min

/** The speedometer on the whole screen: the dial, with top speed, average and trip beside it. */
class SpeedometerRenderer(carContext: CarContext) : CanvasLayer(carContext) {

    private val needle = Needle()
    private var moving = false

    override fun beforeFrame() { moving = needle.step(DriveSpeed.kmh()) }
    override fun wantsFastFrames() = moving

    override fun frameKey(): String = listOf(
        needle.shown?.let { (it * 2).toInt() }, DriveSpeed.signal(), DriveSpeed.accuracyM.toInt(),
        DriveSpeed.maxKmh.toInt(), DriveSpeed.avgKmh.toInt(), (DriveSpeed.distanceM / 100).toInt(),
    ).joinToString("|")

    override fun render(canvas: Canvas, area: Rect) {
        val narrow = area.width() < area.height() * 1.25f || area.width() / density < 480
        val k = if (narrow) area.width() / 46f else min(area.width() / 92f, area.height() / 52f)
        val padX = (if (narrow) 2.2f else 3.2f) * k
        val padY = (if (narrow) 2.6f else 3f) * k
        val r = RectF(area.left + padX, area.top + padY, area.right - padX, area.bottom - padY)

        val titleH = 5.4f * k
        text.textAlign = Paint.Align.LEFT
        text.typeface = look.sora500
        text.letterSpacing = -0.01f
        text.textSize = (if (narrow) 3f else 3.4f) * k
        text.color = look.ink
        val base = r.top + titleH / 2 + text.textSize * 0.36f
        val title = if (narrow) "Speed" else "Speedometer"
        canvas.drawText(title, r.left, base, text)
        val titleW = text.measureText(title)
        text.letterSpacing = 0f
        if (!narrow) {
            text.typeface = look.manrope600
            text.textSize = 2f * k
            drawGps(canvas, r.left + titleW + 1.8f * k, base, k)
        }

        val body = RectF(r.left, r.top + titleH + 2.2f * k, r.right, r.bottom)
        if (body.height() <= 0) return
        val gap = 2.4f * k
        if (body.width() > body.height() * 1.5f) {
            // Dial on the left, the three stats in a column on the right.
            val statsW = min(21f * k, body.width() * 0.3f)
            val dial = RectF(body.left, body.top, body.right - statsW - gap, body.bottom)
            Gauge.draw(canvas, dial, needle.shown, look, paint, text)
            val cardH = min((body.height() - 2 * 1.4f * k) / 3f, 9.5f * k)
            val top = body.centerY() - (cardH * 3 + 2 * 1.4f * k) / 2
            stats().forEachIndexed { i, s ->
                val y = top + i * (cardH + 1.4f * k)
                drawStat(canvas, RectF(body.right - statsW, y, body.right, y + cardH), s, k)
            }
        } else {
            // Dial on top, the stats in a row underneath.
            val rowH = min(body.height() * 0.3f, 9f * k)
            val dial = RectF(body.left, body.top, body.right, body.bottom - rowH - gap * 0.6f)
            Gauge.draw(canvas, dial, needle.shown, look, paint, text)
            val g = 1.2f * k
            val w = (body.width() - g * 2) / 3f
            stats().forEachIndexed { i, s ->
                val x = body.left + i * (w + g)
                drawStat(canvas, RectF(x, body.bottom - rowH, x + w, body.bottom), s, k)
            }
        }
    }

    private data class Stat(val value: String, val unit: String, val label: String)

    private fun stats() = listOf(
        Stat(Math.round(DriveSpeed.maxKmh).toString(), "km/h", "Top speed"),
        Stat(Math.round(DriveSpeed.avgKmh).toString(), "km/h", "Average"),
        Stat(String.format(java.util.Locale.US, "%.1f", DriveSpeed.distanceM / 1000.0), "km", "Trip"),
    )

    private fun drawStat(canvas: Canvas, r: RectF, s: Stat, k: Float) {
        card(canvas, r, min(1.8f * k, r.height() * 0.25f))
        val pad = min(1.8f * k, r.width() * 0.1f)
        val num = min(4f * k, min(r.height() * 0.45f, r.width() * 0.26f))
        val small = num * 0.38f
        val totalH = num * 0.85f + small * 1.9f
        val numBase = r.centerY() - totalH / 2 + num * 0.85f
        text.textAlign = Paint.Align.LEFT
        text.typeface = look.sora300
        text.letterSpacing = -0.02f
        text.textSize = num
        text.color = look.ink
        text.fontFeatureSettings = "tnum"
        canvas.drawText(s.value, r.left + pad, numBase, text)
        val vw = text.measureText(s.value)
        text.fontFeatureSettings = null
        text.letterSpacing = 0f
        text.typeface = look.manrope600
        text.textSize = small
        text.color = look.ink2
        line(canvas, s.unit, r.left + pad + vw + small * 0.4f, numBase, r.right - pad - (r.left + pad + vw + small * 0.4f))
        text.typeface = look.manrope700
        text.color = look.ink3
        text.letterSpacing = 0.08f
        line(canvas, s.label.uppercase(), r.left + pad, numBase + small * 1.9f, r.width() - pad * 2)
        text.letterSpacing = 0f
    }

    private fun drawGps(canvas: Canvas, x: Float, baseline: Float, k: Float) {
        val sig = DriveSpeed.signal()
        val label = when {
            !DriveSpeed.hasLocationPermission(carContext) -> "Allow location on phone"
            sig == DriveSpeed.Signal.NONE -> "No GPS"
            DriveSpeed.accuracyM >= 0f -> "GPS · ±${Math.round(DriveSpeed.accuracyM)} m"
            else -> "Car speed"
        }
        val rr = 0.55f * k
        paint.color = when (sig) {
            DriveSpeed.Signal.GOOD -> look.good
            DriveSpeed.Signal.WEAK -> Color.parseColor("#F0A65A")
            DriveSpeed.Signal.NONE -> look.ink3
        }
        canvas.drawCircle(x + rr, baseline - text.textSize * 0.35f, rr, paint)
        text.color = look.ink2
        canvas.drawText(label, x + rr * 2 + 0.7f * k, baseline, text)
    }
}
