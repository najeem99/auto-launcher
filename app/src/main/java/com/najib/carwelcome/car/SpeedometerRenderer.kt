package com.najib.carwelcome.car

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import androidx.car.app.CarContext
import kotlin.math.max
import kotlin.math.min

/**
 * The speedometer on the whole screen, sized to be read at a glance while driving:
 * a big needle dial on the left half and a huge digital speed on the right half,
 * with top speed, average and trip in a small line underneath.
 * On a tall or split screen the dial sits on top and the number below it.
 */
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
        val k = min(area.width(), area.height()) / 100f
        val pad = 2.5f * k
        val r = RectF(area.left + pad, area.top + pad, area.right - pad, area.bottom - pad)
        val kmh = needle.shown

        if (r.width() >= r.height() * 1.2f) {
            // Side by side: dial fills the left half, number fills the right half.
            val mid = r.left + r.width() * 0.5f
            RoundGauge.draw(canvas, RectF(r.left, r.top, mid - 1.5f * k, r.bottom), kmh, look, paint, text)
            divider(canvas, mid + 1f * k, r.top + r.height() * 0.12f, r.bottom - r.height() * 0.12f)
            drawReadout(canvas, RectF(mid + 3f * k, r.top, r.right, r.bottom), kmh, k)
        } else {
            // Stacked: dial on top, number underneath.
            val split = r.top + r.height() * 0.56f
            RoundGauge.draw(canvas, RectF(r.left, r.top, r.right, split), kmh, look, paint, text)
            drawReadout(canvas, RectF(r.left, split + 1.5f * k, r.right, r.bottom), kmh, k)
        }
    }

    private fun divider(canvas: Canvas, x: Float, top: Float, bottom: Float) {
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(0f, top, 0f, bottom,
            intArrayOf(Color.TRANSPARENT, look.line, Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        canvas.drawRect(x - 1f, top, x + 1f, bottom, paint)
        paint.shader = null
    }

    /** GPS status on top, the huge number in the middle, KM/H under it and the stats at the bottom. */
    private fun drawReadout(canvas: Canvas, r: RectF, kmh: Float?, k: Float) {
        val cx = r.centerX()

        // GPS status, small, top centre.
        val statusSize = max(2.6f * k, 11f * density)
        text.typeface = look.manrope600
        text.textSize = statusSize
        text.textAlign = Paint.Align.LEFT
        drawGps(canvas, cx, r.top + statusSize, statusSize)

        // Stats line at the bottom.
        val statSize = max(3.2f * k, 12f * density)
        val statsH = statSize * 3.2f
        drawStats(canvas, RectF(r.left, r.bottom - statsH, r.right, r.bottom), statSize)

        // The big number: as large as the space allows, sized for three digits.
        val space = RectF(r.left, r.top + statusSize * 2.2f, r.right, r.bottom - statsH)
        val unitSize = min(space.height() * 0.07f, 5f * k).coerceAtLeast(10f * density)
        val numBox = RectF(space.left, space.top, space.right, space.bottom - unitSize * 2.4f)
        text.typeface = look.sora600
        text.fontFeatureSettings = "tnum"
        text.letterSpacing = -0.04f
        text.textAlign = Paint.Align.CENTER
        text.textSize = 100f
        val w100 = text.measureText("188")
        // Sora's digits are ~0.73 of the text size tall.
        val size = min(numBox.width() * 0.96f / w100 * 100f, numBox.height() / 0.76f)
        text.textSize = size
        val digitH = size * 0.73f
        val base = numBox.centerY() + digitH / 2f
        val label = kmh?.let { Math.round(it).toString() } ?: "--"
        val fast = (kmh ?: 0f) >= 140f
        if (kmh == null) {
            text.shader = null
            text.color = look.ink3
        } else {
            val bottomCol = if (fast) look.red else look.teal
            text.shader = LinearGradient(0f, base - digitH, 0f, base,
                intArrayOf(Color.WHITE, look.ink, bottomCol), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        }
        canvas.drawText(label, cx, base, text)
        text.shader = null
        text.fontFeatureSettings = null
        text.letterSpacing = 0f

        // KM/H with short accent lines either side.
        text.typeface = look.manrope700
        text.textSize = unitSize
        text.letterSpacing = 0.35f
        text.color = if (fast) look.red else look.teal
        val unitBase = base + unitSize * 2.0f
        canvas.drawText("KM/H", cx, unitBase, text)
        val uw = text.measureText("KM/H")
        text.letterSpacing = 0f
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(110, Color.red(text.color), Color.green(text.color), Color.blue(text.color))
        val ly = unitBase - unitSize * 0.36f
        val gap = unitSize * 0.9f
        val len = unitSize * 2.6f
        canvas.drawRect(cx - uw / 2 - gap - len, ly - 1.5f, cx - uw / 2 - gap, ly + 1.5f, paint)
        canvas.drawRect(cx + uw / 2 + gap, ly - 1.5f, cx + uw / 2 + gap + len, ly + 1.5f, paint)
        text.textAlign = Paint.Align.LEFT
    }

    /** Top speed, average and trip side by side, centred in [r]. */
    private fun drawStats(canvas: Canvas, r: RectF, size: Float) {
        val items = listOf(
            "TOP" to "${Math.round(DriveSpeed.maxKmh)}",
            "AVG" to "${Math.round(DriveSpeed.avgKmh)}",
            "TRIP" to String.format(java.util.Locale.US, "%.1f km", DriveSpeed.distanceM / 1000.0),
        )
        val colW = r.width() / items.size
        text.textAlign = Paint.Align.CENTER
        items.forEachIndexed { i, (label, value) ->
            val x = r.left + colW * (i + 0.5f)
            text.typeface = look.manrope700
            text.textSize = size * 0.62f
            text.letterSpacing = 0.14f
            text.color = look.ink3
            canvas.drawText(label, x, r.top + size * 1.0f, text)
            text.letterSpacing = 0f
            text.typeface = look.sora500
            text.fontFeatureSettings = "tnum"
            text.textSize = size
            text.color = look.ink2
            canvas.drawText(value, x, r.top + size * 2.4f, text)
            text.fontFeatureSettings = null
        }
        text.textAlign = Paint.Align.LEFT
    }

    /** A coloured dot and the GPS/car-speed source, centred on [cx]. Uses the current text size. */
    private fun drawGps(canvas: Canvas, cx: Float, baseline: Float, size: Float) {
        val sig = DriveSpeed.signal()
        val label = when {
            !DriveSpeed.hasLocationPermission(carContext) -> "Allow location on phone"
            sig == DriveSpeed.Signal.NONE -> "No GPS"
            DriveSpeed.accuracyM >= 0f -> "GPS · ±${Math.round(DriveSpeed.accuracyM)} m"
            else -> "Car speed"
        }
        val rr = size * 0.28f
        val tw = text.measureText(label)
        val total = rr * 2 + size * 0.4f + tw
        val x = cx - total / 2
        paint.style = Paint.Style.FILL
        paint.color = when (sig) {
            DriveSpeed.Signal.GOOD -> look.good
            DriveSpeed.Signal.WEAK -> Color.parseColor("#F0A65A")
            DriveSpeed.Signal.NONE -> look.ink3
        }
        canvas.drawCircle(x + rr, baseline - size * 0.35f, rr, paint)
        text.color = look.ink2
        canvas.drawText(label, x + rr * 2 + size * 0.4f, baseline, text)
    }
}
