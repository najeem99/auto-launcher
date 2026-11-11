package com.najib.carwelcome.car

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.text.TextPaint
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The big full-screen dial: a 240° sweep (0 at bottom-left, 160 at bottom-right) so it
 * fills a near-square box far better than the half circle on the Utilities tile.
 * No digital readout inside; the full-screen speedometer shows the number beside it.
 */
object RoundGauge {
    private const val START = 150f
    private const val SWEEP = 240f
    private const val MAX = Gauge.MAX

    /** Fits the dial into [r] as large as possible. [kmh] null rests the needle at 0. */
    fun draw(canvas: Canvas, r: RectF, kmh: Float?, look: Look, paint: Paint, text: TextPaint) {
        if (r.width() <= 0 || r.height() <= 0) return
        // The dial spans 2R wide and R + R·sin30° = 1.5R tall (plus a little for the hub).
        val rad = min(r.width() / 2f, r.height() / 1.56f)
        val cx = r.centerX()
        val cy = r.centerY() - (1.5f * rad) / 2f + rad
        val v = (kmh ?: 0f).coerceIn(0f, MAX)

        fun ang(value: Float) = START + SWEEP * value / MAX
        fun pt(value: Float, radius: Float): Pair<Float, Float> {
            val a = Math.toRadians(ang(value).toDouble())
            return Pair(cx + radius * cos(a).toFloat(), cy + radius * sin(a).toFloat())
        }
        fun oval(radius: Float) = RectF(cx - radius, cy - radius, cx + radius, cy + radius)

        // Soft disc behind the dial.
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(cx, cy, rad, Color.argb(38, 91, 209, 193), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, rad, paint)
        paint.shader = null

        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        // Track.
        val ring = rad * 0.97f
        paint.strokeWidth = rad * 0.035f
        paint.color = Color.argb(30, 255, 255, 255)
        canvas.drawArc(oval(ring), START, SWEEP, false, paint)
        // Progress with a glow under it.
        if (v > 0.2f) {
            val sweep = SWEEP * v / MAX
            val col = if (v >= 140f) look.red else look.teal
            paint.strokeWidth = rad * 0.09f
            paint.color = Color.argb(45, Color.red(col), Color.green(col), Color.blue(col))
            canvas.drawArc(oval(ring), START, sweep, false, paint)
            paint.strokeWidth = rad * 0.035f
            paint.color = col
            canvas.drawArc(oval(ring), START, sweep, false, paint)
        }
        // Red zone 140–160.
        paint.strokeCap = Paint.Cap.BUTT
        paint.strokeWidth = rad * 0.05f
        paint.color = Color.argb(217, 255, 90, 78)
        canvas.drawArc(oval(rad * 0.89f), ang(140f), SWEEP * 20f / MAX, false, paint)

        // Ticks every 5, longer every 10 and 20; numbers every 20.
        paint.strokeCap = Paint.Cap.ROUND
        text.typeface = look.sora500
        text.textSize = rad * 0.12f
        text.textAlign = Paint.Align.CENTER
        text.letterSpacing = 0f
        text.fontFeatureSettings = "tnum"
        var t = 0
        while (t <= MAX.toInt()) {
            val major = t % 20 == 0
            val mid = t % 10 == 0
            val outer = rad * 0.86f
            val len = rad * (if (major) 0.13f else if (mid) 0.09f else 0.05f)
            val (x1, y1) = pt(t.toFloat(), outer)
            val (x2, y2) = pt(t.toFloat(), outer - len)
            paint.strokeWidth = rad * (if (major) 0.022f else 0.011f)
            paint.color = when { t >= 140 -> look.red; major -> look.ink; else -> look.ink3 }
            canvas.drawLine(x1, y1, x2, y2, paint)
            if (major) {
                val (tx, ty) = pt(t.toFloat(), rad * 0.6f)
                text.color = if (t >= 140) look.red else if (t <= v) look.ink else look.ink2
                canvas.drawText(t.toString(), tx, ty + text.textSize * 0.36f, text)
            }
            t += 5
        }
        text.fontFeatureSettings = null

        // "km/h" in the empty bottom gap of the dial.
        text.typeface = look.manrope700
        text.textSize = rad * 0.075f
        text.letterSpacing = 0.18f
        text.color = look.ink3
        canvas.drawText("KM/H", cx, cy + rad * 0.42f, text)
        text.letterSpacing = 0f
        text.textAlign = Paint.Align.LEFT

        // Needle: a tapered red hand with a short tail.
        paint.style = Paint.Style.FILL
        canvas.save()
        canvas.rotate(ang(v), cx, cy) // the needle is drawn pointing right (0°)
        val w = rad * 0.035f
        val needle = Path().apply {
            moveTo(cx - rad * 0.16f, cy - w * 0.8f)
            lineTo(cx + rad * 0.80f, cy - w * 0.25f)
            lineTo(cx + rad * 0.84f, cy)
            lineTo(cx + rad * 0.80f, cy + w * 0.25f)
            lineTo(cx - rad * 0.16f, cy + w * 0.8f)
            close()
        }
        paint.color = Color.argb(70, 255, 90, 78)
        paint.style = Paint.Style.STROKE
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeWidth = w * 1.6f
        canvas.drawPath(needle, paint)
        paint.style = Paint.Style.FILL
        paint.color = look.red
        canvas.drawPath(needle, paint)
        canvas.restore()

        // Hub.
        val hub = rad * 0.075f
        paint.color = Color.parseColor("#1A2640")
        canvas.drawCircle(cx, cy, hub, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = rad * 0.014f
        paint.color = look.ink
        canvas.drawCircle(cx, cy, hub, paint)
        paint.style = Paint.Style.FILL
        paint.color = look.red
        canvas.drawCircle(cx, cy, hub * 0.32f, paint)
    }
}
