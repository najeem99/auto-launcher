package com.najib.carwelcome.car

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import androidx.car.app.CarContext
import com.najib.carwelcome.CheckIn
import com.najib.carwelcome.Prefs
import com.najib.carwelcome.R
import com.najib.carwelcome.Weather
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * The Utilities screen: a big GPS speedometer tile and a grid of tiles (Check in,
 * Office, Home, Claude, Parked). Full screen uses the design's 4-column grid; a
 * narrow split-screen panel gets 2 columns with the dial on top.
 */
class UtilitiesRenderer(
    carContext: CarContext,
    private val onTile: (Tile) -> Unit,
) : CanvasLayer(carContext) {

    enum class Tile { SPEEDO, CHECKIN, OFFICE, HOME, PRAYER, CLAUDE, PARKED }

    private val prefs = Prefs(carContext)
    private val needle = Needle()
    private var moving = false

    override fun beforeFrame() { moving = needle.step(DriveSpeed.kmh()) }
    override fun wantsFastFrames() = moving

    override fun frameKey(): String {
        val w = Weather.latest
        return listOf(
            needle.shown?.let { (it * 2).toInt() }, DriveSpeed.signal(), DriveSpeed.accuracyM.toInt(), DriveSpeed.maxKmh.toInt(),
            CheckIn.phase, CheckIn.message, prefs.checkInUrl.isBlank(), ParkedDetector.manualParked,
            w?.tempC, SimpleDateFormat("yyyyMMdd", Locale.US).format(Date()),
        ).joinToString("|")
    }

    /** Small tiles in grid order. The next-prayer tile joins once it has real times. */
    private fun smallTiles(split: Boolean): List<Tile> =
        if (split) listOf(Tile.CHECKIN, Tile.OFFICE, Tile.HOME, Tile.PARKED)
        else listOf(Tile.CHECKIN, Tile.OFFICE, Tile.HOME, Tile.CLAUDE, Tile.PARKED)

    override fun render(canvas: Canvas, area: Rect) {
        val wDp = area.width() / density
        val split = wDp < 480 || area.width() < area.height() * 1.25f
        // k: one "design unit" (1 cqw in the mockup) in pixels.
        val k = if (split) area.width() / 46f else min(area.width() / 92f, area.height() / 52f)
        val padX = (if (split) 2.2f else 3.2f) * k
        val padY = (if (split) 2.6f else 3f) * k
        val r = RectF(area.left + padX, area.top + padY, area.right - padX, area.bottom - padY)

        // Title row. The Home button (Android Auto's action strip) sits top-right.
        val titleH = 5.4f * k
        text.textAlign = Paint.Align.LEFT
        text.typeface = look.sora500
        text.letterSpacing = -0.01f
        text.textSize = (if (split) 3f else 3.4f) * k
        text.color = look.ink
        val base = r.top + titleH / 2 + text.textSize * 0.36f
        canvas.drawText("Utilities", r.left, base, text)
        val titleW = text.measureText("Utilities")
        text.letterSpacing = 0f
        if (!split) {
            text.typeface = look.manrope600
            text.textSize = 2f * k
            text.color = look.ink3
            val date = SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date())
            val sub = Weather.latest?.let { "$date · ${it.tempC}°C" } ?: date
            line(canvas, sub, r.left + titleW + 1.6f * k, base, r.width() * 0.6f - titleW)
        }

        val gap = (if (split) 1.2f else 1.6f) * k
        val grid = RectF(r.left, r.top + titleH + (if (split) 1.6f else 2.2f) * k, r.right, r.bottom)
        if (grid.height() < 40 * density) return
        if (split) paintSplit(canvas, grid, gap, k) else paintFull(canvas, grid, gap, k)
    }

    private fun paintFull(canvas: Canvas, g: RectF, gap: Float, k: Float) {
        // Columns 1.7 : 1 : 1 : 1; the dial fills the first column on both rows.
        val unitW = (g.width() - gap * 3) / 4.7f
        val speedo = RectF(g.left, g.top, g.left + unitW * 1.7f, g.bottom)
        drawSpeedoTile(canvas, speedo, k, footer = true)
        onTap(speedo) { onTile(Tile.SPEEDO) }
        layoutSmall(canvas, RectF(speedo.right + gap, g.top, g.right, g.bottom), smallTiles(false), 3, 2, gap, k, full = true)
    }

    private fun paintSplit(canvas: Canvas, g: RectF, gap: Float, k: Float) {
        // Rows 1.6 : 1 : 1, the dial across the top.
        val unitH = (g.height() - gap * 2) / 3.6f
        val speedo = RectF(g.left, g.top, g.right, g.top + unitH * 1.6f)
        drawSpeedoTile(canvas, speedo, k, footer = false)
        onTap(speedo) { onTile(Tile.SPEEDO) }
        layoutSmall(canvas, RectF(g.left, speedo.bottom + gap, g.right, g.bottom), smallTiles(true), 2, 2, gap, k, full = false)
    }

    /** Lays [tiles] out [cols] across; a short last row stretches to fill the width. */
    private fun layoutSmall(canvas: Canvas, r: RectF, tiles: List<Tile>, cols: Int, rows: Int, gap: Float, k: Float, full: Boolean) {
        val rowCount = max(rows, ceil(tiles.size / cols.toFloat()).toInt())
        val th = (r.height() - gap * (rowCount - 1)) / rowCount
        tiles.chunked(cols).forEachIndexed { row, items ->
            val tw = (r.width() - gap * (items.size - 1)) / items.size
            items.forEachIndexed { i, t ->
                val left = r.left + i * (tw + gap)
                val top = r.top + row * (th + gap)
                val rect = RectF(left, top, left + tw, top + th)
                drawSmallTile(canvas, rect, t, k, full)
                onTap(rect) { onTile(t) }
            }
        }
    }

    private fun drawSpeedoTile(canvas: Canvas, r: RectF, k: Float, footer: Boolean) {
        val radius = 2.2f * k
        card(canvas, r, radius, fill = Color.argb(242, 15, 23, 40))
        // Soft teal glow under the dial.
        paint.shader = RadialGradient(r.centerX(), r.top + r.height() * 0.7f, max(r.width(), r.height()) * 0.55f,
            Color.argb(26, 91, 209, 193), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(r, radius, radius, paint)
        paint.shader = null

        val pad = 1.6f * k
        val footH = if (footer) 1.8f * k * 1.8f else 0f
        val dial = RectF(r.left + pad, r.top + pad, r.right - pad, r.bottom - pad - footH)
        Gauge.draw(canvas, dial, needle.shown, look, paint, text)
        if (!footer) return
        val y = r.bottom - pad - footH * 0.25f
        text.typeface = look.manrope600
        text.textSize = 1.8f * k
        drawGps(canvas, r.left + pad + 0.6f * k, y, k)
        text.textAlign = Paint.Align.RIGHT
        text.color = look.ink
        text.typeface = look.manrope700
        val top = Math.round(DriveSpeed.maxKmh).toString()
        canvas.drawText(top, r.right - pad - 0.6f * k, y, text)
        val maxW = text.measureText(top)
        text.typeface = look.manrope600
        text.color = look.ink2
        canvas.drawText("Max ", r.right - pad - 0.6f * k - maxW, y, text)
        text.textAlign = Paint.Align.LEFT
    }

    /** "● GPS · ±4 m": green with a good fix, amber when weak, grey with none. */
    private fun drawGps(canvas: Canvas, x: Float, baseline: Float, k: Float) {
        val sig = DriveSpeed.signal()
        val label = when {
            !DriveSpeed.hasLocationPermission(carContext) -> "Allow location on phone"
            sig == DriveSpeed.Signal.NONE -> "No GPS"
            DriveSpeed.accuracyM >= 0f -> "GPS · ±${Math.round(DriveSpeed.accuracyM)} m"
            else -> "Car speed"
        }
        val dot = when (sig) {
            DriveSpeed.Signal.GOOD -> look.good
            DriveSpeed.Signal.WEAK -> Color.parseColor("#F0A65A")
            DriveSpeed.Signal.NONE -> look.ink3
        }
        val rr = 0.55f * k
        paint.color = dot
        canvas.drawCircle(x + rr, baseline - text.textSize * 0.35f, rr, paint)
        text.color = look.ink2
        canvas.drawText(label, x + rr * 2 + 0.7f * k, baseline, text)
    }

    private fun drawSmallTile(canvas: Canvas, r: RectF, tile: Tile, k: Float, full: Boolean) {
        val radius = (if (full) 2.2f else 1.8f) * k
        val checkPhase = CheckIn.phase
        val noCheckIn = prefs.checkInUrl.isBlank()
        val parkedOn = ParkedDetector.manualParked

        // Card: check-in is gold until done, green after; Parked glows teal when on.
        when {
            tile == Tile.CHECKIN && checkPhase == CheckIn.Phase.DONE -> {
                card(canvas, r, radius, stroke = Color.argb(128, 99, 214, 154))
                paint.shader = LinearGradient(r.left, r.top, r.left + r.width() * 0.3f, r.bottom,
                    Color.argb(36, 99, 214, 154), Color.TRANSPARENT, Shader.TileMode.CLAMP)
                canvas.drawRoundRect(r, radius, radius, paint)
                paint.shader = null
            }
            tile == Tile.CHECKIN -> card(canvas, r, radius, stroke = Color.argb(102, 232, 194, 122))
            tile == Tile.PARKED && parkedOn -> card(canvas, r, radius, stroke = Color.argb(140, 91, 209, 193), strokeW = 2f)
            else -> card(canvas, r, radius)
        }

        val pad = min(r.width(), r.height()) * 0.11f
        val label = min(r.height() * 0.17f, r.width() * 0.14f)
        val subSize = label * 0.74f
        val iconBox = min(r.height() * 0.32f, r.width() * 0.27f)
        val showIcon = r.height() >= pad * 2 + iconBox + label * 2.6f
        val showSub = r.height() >= pad * 2 + label * 2.2f

        if (showIcon) {
            val box = RectF(r.left + pad, r.top + pad, r.left + pad + iconBox, r.top + pad + iconBox)
            paint.color = if (tile == Tile.CHECKIN) Color.argb(46, 232, 194, 122) else look.iconBox
            val br = iconBox * 0.28f
            canvas.drawRoundRect(box, br, br, paint)
            val inset = iconBox * 0.2f
            val (res, tint) = when (tile) {
                Tile.CHECKIN -> R.drawable.ic_checkin to when (checkPhase) {
                    CheckIn.Phase.DONE -> look.good
                    CheckIn.Phase.FAILED -> look.bad
                    else -> look.sand
                }
                Tile.OFFICE -> R.drawable.ic_work to look.teal
                Tile.HOME -> R.drawable.ic_home to look.teal
                Tile.PRAYER -> R.drawable.ic_mosque to look.sand
                Tile.CLAUDE -> R.drawable.ic_claude to look.claude
                Tile.PARKED -> (if (parkedOn) R.drawable.ic_parked_on else R.drawable.ic_parked) to look.teal
                Tile.SPEEDO -> R.drawable.ic_grid to look.teal
            }
            icon(canvas, res, RectF(box.left + inset, box.top + inset, box.right - inset, box.bottom - inset), tint)
        }

        // Parked: a switch in the top-right corner.
        if (tile == Tile.PARKED) {
            val th = min(iconBox * 0.74f, r.height() * 0.22f)
            val tw = th * 1.9f
            val tr = RectF(r.right - pad - tw, r.top + pad, r.right - pad, r.top + pad + th)
            paint.color = if (parkedOn) look.teal else Color.argb(31, 255, 255, 255)
            canvas.drawRoundRect(tr, th / 2, th / 2, paint)
            val knobR = th / 2 - th * 0.12f
            paint.color = if (parkedOn) Color.parseColor("#0B1220") else look.ink2
            canvas.drawCircle(if (parkedOn) tr.right - th / 2 else tr.left + th / 2, tr.centerY(), knobR, paint)
        }

        val (title, sub) = when (tile) {
            Tile.CHECKIN -> when {
                noCheckIn -> "Check in" to "Set up in phone app"
                checkPhase == CheckIn.Phase.RUNNING -> "Checking in…" to CheckIn.message.ifBlank { "Darwinbox" }
                checkPhase == CheckIn.Phase.DONE -> "Checked in" to CheckIn.message
                checkPhase == CheckIn.Phase.FAILED -> "Check in" to CheckIn.message.ifBlank { "Tap to try again" }
                else -> "Check in" to "Darwinbox"
            }
            Tile.OFFICE -> "Office" to "Directions in Maps"
            Tile.HOME -> "Home" to "Directions in Maps"
            Tile.PRAYER -> "Next prayer" to ""
            Tile.CLAUDE -> "Claude" to "On your phone"
            Tile.PARKED -> "Parked" to if (full) (if (parkedOn) "YouTube on" else "YouTube locked") else (if (parkedOn) "On" else "Off")
            Tile.SPEEDO -> "Speedometer" to ""
        }

        val textW = r.width() - pad * 2
        var baseline = r.bottom - pad - label * 0.15f
        if (showSub && sub.isNotEmpty()) {
            text.typeface = look.manrope500
            text.textSize = subSize
            text.color = look.ink2
            line(canvas, sub, r.left + pad, baseline, textW)
            baseline -= subSize * 1.35f
        }
        text.typeface = look.manrope700
        text.textSize = label
        text.color = if (tile == Tile.CHECKIN && checkPhase != CheckIn.Phase.DONE) look.sand else look.ink
        line(canvas, title, r.left + pad, baseline, if (tile == Tile.PARKED && !showIcon) textW * 0.55f else textW)

        // Full layout: a status dot on the check-in tile, above the label.
        if (full && tile == Tile.CHECKIN && showIcon && !noCheckIn) {
            val sy = baseline - label * 1.35f
            val dotR = label * 0.26f
            val state = when (checkPhase) {
                CheckIn.Phase.DONE -> "Done today"
                CheckIn.Phase.RUNNING -> "Working"
                CheckIn.Phase.FAILED -> "Didn't work"
                CheckIn.Phase.IDLE -> "Not yet"
            }
            val color = when (checkPhase) {
                CheckIn.Phase.DONE -> look.good
                CheckIn.Phase.FAILED -> look.bad
                else -> look.sand
            }
            if (sy - label > r.top + pad + iconBox) {
                paint.color = color
                canvas.drawCircle(r.left + pad + dotR, sy - subSize * 0.35f, dotR, paint)
                text.typeface = look.manrope700
                text.textSize = subSize
                text.color = if (checkPhase == CheckIn.Phase.DONE) look.good else look.ink2
                line(canvas, state, r.left + pad + dotR * 2 + label * 0.35f, sy, textW - dotR * 3)
            }
        }
    }
}
