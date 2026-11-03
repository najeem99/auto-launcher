package com.najib.carwelcome.car

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.TextPaint
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The classic half-circle speedometer from the design: 0–160 km/h, a number every 20,
 * a red zone from 140, a red needle and a digital readout. Drawn in a 200 × 128 box
 * scaled to fit.
 */
object Gauge {
    const val MAX = 160f
    private const val CX = 100f
    private const val CY = 104f
    private const val R = 90f

    /** [kmh] null means no speed reading: the readout shows "--" and the needle rests at 0. */
    fun draw(canvas: Canvas, r: RectF, kmh: Float?, look: Look, paint: Paint, text: TextPaint) {
        if (r.width() <= 0 || r.height() <= 0) return
        val s = min(r.width() / 200f, r.height() / 128f)
        val ox = r.centerX() - 100f * s
        val oy = r.centerY() - 64f * s
        val cx = ox + CX * s
        val cy = oy + CY * s
        val rad = R * s
        val v = (kmh ?: 0f).coerceIn(0f, MAX)

        fun pt(value: Float, radius: Float): Pair<Float, Float> {
            val a = Math.PI * (1 - value / MAX)
            return Pair(cx + radius * cos(a).toFloat(), cy - radius * sin(a).toFloat())
        }
        fun oval(radius: Float) = RectF(cx - radius, cy - radius, cx + radius, cy + radius)

        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        // Track, progress, red zone.
        paint.strokeWidth = 3f * s
        paint.color = Color.argb(26, 255, 255, 255)
        canvas.drawArc(oval(rad), 180f, 180f, false, paint)
        if (v > 0.2f) {
            paint.color = look.teal
            canvas.drawArc(oval(rad), 180f, 180f * v / MAX, false, paint)
        }
        paint.strokeCap = Paint.Cap.BUTT
        paint.strokeWidth = 5f * s
        paint.color = Color.argb(217, 255, 90, 78)
        canvas.drawArc(oval(rad - 6f * s), 180f + 180f * 140f / MAX, 180f * 20f / MAX, false, paint)

        // Ticks every 5, longer every 10 and 20, with numbers every 20.
        paint.strokeCap = Paint.Cap.ROUND
        text.typeface = look.sora500
        text.textSize = 10f * s
        text.textAlign = Paint.Align.CENTER
        text.letterSpacing = 0f
        text.color = look.ink2
        var t = 0
        while (t <= MAX.toInt()) {
            val major = t % 20 == 0
            val mid = t % 10 == 0
            val (x1, y1) = pt(t.toFloat(), rad - 3f * s)
            val (x2, y2) = pt(t.toFloat(), rad - (if (major) 15f else if (mid) 11f else 7f) * s)
            paint.strokeWidth = (if (major) 2f else 1.1f) * s
            paint.color = when { t >= 140 -> look.red; major -> look.ink; else -> look.ink3 }
            canvas.drawLine(x1, y1, x2, y2, paint)
            if (major) {
                val (tx, ty) = pt(t.toFloat(), rad - 27f * s)
                canvas.drawText(t.toString(), tx, ty + 3.5f * s, text)
            }
            t += 5
        }
        paint.style = Paint.Style.FILL

        // Digital readout and unit.
        text.typeface = look.sora300
        text.textSize = 26f * s
        text.color = if (kmh == null) look.ink3 else look.ink
        text.fontFeatureSettings = "tnum"
        canvas.drawText(kmh?.let { Math.round(it).toString() } ?: "--", cx, cy - 22f * s, text)
        text.fontFeatureSettings = null
        text.typeface = look.manrope700
        text.textSize = 7f * s
        text.color = look.ink3
        text.letterSpacing = 0.12f
        canvas.drawText("KM/H", cx, cy + 20f * s, text)
        text.letterSpacing = 0f
        text.textAlign = Paint.Align.LEFT

        // Needle: points left at 0, turns clockwise to the right at 160.
        canvas.save()
        canvas.rotate(180f * v / MAX, cx, cy)
        val needle = Path().apply {
            moveTo(cx, cy - 2.6f * s)
            lineTo(cx - rad + 12f * s, cy)
            lineTo(cx, cy + 2.6f * s)
            close()
        }
        paint.color = look.red
        canvas.drawPath(needle, paint)
        canvas.restore()
        paint.color = Color.parseColor("#1A2640")
        canvas.drawCircle(cx, cy, 7f * s, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f * s
        paint.color = look.ink
        canvas.drawCircle(cx, cy, 7f * s, paint)
        paint.style = Paint.Style.FILL
        paint.color = look.red
        canvas.drawCircle(cx, cy, 2.2f * s, paint)
    }
}

/** Eases the drawn speed toward the latest reading so the needle sweeps instead of jumping. */
class Needle {
    var shown: Float? = null
        private set

    /** Moves one frame toward [target]; returns true while still moving. */
    fun step(target: Float?): Boolean {
        if (target == null) { shown = null; return false }
        val cur = shown ?: run { shown = target; return false }
        val d = target - cur
        if (abs(d) < 0.4f) { shown = target; return false }
        shown = cur + d * 0.35f
        return true
    }
}
