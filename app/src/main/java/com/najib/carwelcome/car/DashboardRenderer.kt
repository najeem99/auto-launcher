package com.najib.carwelcome.car

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import com.najib.carwelcome.DuaSafar
import com.najib.carwelcome.Prefs
import com.najib.carwelcome.Weather
import com.najib.carwelcome.WeatherNow
import com.najib.carwelcome.Welcome
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Draws the dashboard on the car screen: greeting, big clock, weather card and
 * the Dua al-Safar card. Redraws every second. Layout follows the HTML mockup.
 */
class DashboardRenderer(private val carContext: CarContext) : SurfaceCallback {

    private var container: SurfaceContainer? = null
    private var visibleArea: Rect? = null
    private val handler = Handler(Looper.getMainLooper())
    private val prefs = Prefs(carContext)
    private var background: Bitmap? = null
    private var lastFrameKey: String? = null

    // Redraw only when something on screen changes. During the dua a few frames
    // a second is plenty for the lyrics and progress bar; redrawing the whole
    // surface many times a second made the projected screen flash.
    private val tick = object : Runnable {
        override fun run() {
            draw()
            handler.postDelayed(this, if (Welcome.isSpeaking) 250L else 1000L)
        }
    }

    // Palette (matches the mockup tokens)
    private val night1 = Color.parseColor("#0D1526")
    private val night2 = Color.parseColor("#0A101D")
    private val ink = Color.parseColor("#EEF2FA")
    private val ink2 = Color.parseColor("#A9B6D0")
    private val ink3 = Color.parseColor("#6E7C99")
    private val sand = Color.parseColor("#E8C27A")
    private val teal = Color.parseColor("#5BD1C1")
    private val line = Color.parseColor("#24314D")
    private val cardFill = Color.argb(20, 255, 255, 255)
    private val duaFill = Color.argb(217, 19, 29, 49)

    private val light = Typeface.create("sans-serif-thin", Typeface.NORMAL)
    private val regular = Typeface.create("sans-serif", Typeface.NORMAL)
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val serif = Typeface.create(Typeface.SERIF, Typeface.NORMAL)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        // Only one renderer may draw on the car surface at a time.
        active?.takeIf { it !== this }?.stop()
        active = this
        container = surfaceContainer
        lastFrameKey = null
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        this.visibleArea = Rect(visibleArea)
        lastFrameKey = null
        draw()
    }

    override fun onStableAreaChanged(stableArea: Rect) {}

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        container = null
        handler.removeCallbacks(tick)
        background?.recycle()
        background = null
    }

    fun stop() {
        handler.removeCallbacks(tick)
        container = null
        if (active === this) active = null
    }

    fun draw() {
        val c = container ?: return
        val surface = c.surface ?: return
        if (!surface.isValid) return
        val key = frameKey(c)
        if (key == lastFrameKey) return
        // Hardware canvas: each frame is a complete, GPU-drawn buffer (no tearing).
        val canvas = try {
            surface.lockHardwareCanvas()
        } catch (_: Exception) {
            try { surface.lockCanvas(null) } catch (_: Exception) { null }
        } ?: return
        lastFrameKey = key
        try {
            paintDashboard(canvas, c.width, c.height)
        } finally {
            surface.unlockCanvasAndPost(canvas)
        }
    }

    /** Everything that can change what is drawn; identical keys mean no redraw. */
    private fun frameKey(c: SurfaceContainer): String {
        val p = Welcome.progress
        val w = Weather.latest
        return listOf(
            c.width, c.height, visibleArea,
            SimpleDateFormat("yyyyMMddHHmm", Locale.US).format(Date()), prefs.use24Hour, prefs.name,
            Welcome.isGreeting, Welcome.isReciting, Welcome.currentDuaLine,
            if (p < 0f) -1 else (p * 400).toInt(),
            w?.tempC, w?.code, w?.let { (System.currentTimeMillis() - it.fetchedAt) / 60000 },
        ).joinToString("|")
    }

    /** The gradient background never changes, so it is drawn once and reused. */
    private fun backgroundFor(w: Int, h: Int): Bitmap {
        background?.let { if (it.width == w && it.height == h && !it.isRecycled) return it }
        background?.recycle()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val bg = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
        p.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), night1, night2, Shader.TileMode.CLAMP)
        bg.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = RadialGradient(w * 0.08f, -h * 0.1f, w * 0.6f, Color.argb(41, 91, 209, 193), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        bg.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = RadialGradient(w.toFloat(), h * 1.1f, w * 0.5f, Color.argb(33, 232, 194, 122), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        bg.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        background = bmp
        return bmp
    }

    private fun paintDashboard(canvas: Canvas, w: Int, h: Int) {
        // Background: deep night with a teal glow top-left and a sand glow bottom-right.
        canvas.drawBitmap(backgroundFor(w, h), 0f, 0f, null)
        paint.shader = null

        val area = visibleArea?.takeIf { !it.isEmpty } ?: Rect(0, 0, w, h)
        val u = min(area.width(), (area.height() * 1.67f).toInt()).toFloat() // ~width of a 5:3 box
        val pad = u * 0.034f
        val left = area.left + pad
        val top = area.top + pad
        val right = area.right - pad
        val bottom = area.bottom - pad
        val gap = u * 0.026f

        val speaking = Welcome.isSpeaking

        // Dua card (bottom, full width)
        val duaH = (area.height() * 0.24f).coerceAtLeast(u * 0.11f)
        val duaRect = RectF(left, bottom - duaH, right, bottom)
        drawDuaCard(canvas, duaRect, u, speaking)

        // Weather card (right column)
        val cardW = u * 0.30f
        val upperBottom = duaRect.top - gap
        val greetH = u * 0.06f
        val cardRect = RectF(right - cardW, top + greetH + gap * 0.5f, right, upperBottom)
        val cardH = min(cardRect.height(), u * 0.25f)
        cardRect.top = cardRect.centerY() - cardH / 2
        cardRect.bottom = cardRect.top + cardH
        drawWeather(canvas, cardRect, u, Weather.latest)

        // Greeting row with the voice orb
        val orbR = u * 0.013f
        val orbCx = left + orbR
        val orbCy = top + greetH / 2
        val glow = if (speaking) 1.2f else 1f
        paint.shader = RadialGradient(orbCx - orbR * 0.3f, orbCy - orbR * 0.3f, orbR * 1.2f * glow,
            intArrayOf(Color.WHITE, teal, Color.parseColor("#2A6F86")), floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(orbCx, orbCy, orbR * glow, paint)
        paint.shader = null

        text.typeface = medium
        text.textSize = u * 0.023f
        text.color = ink2
        val greeting = "${Welcome.greetingLine(prefs.name)} Welcome back."
        val greetX = orbCx + orbR * 2.4f
        val maxGreetW = cardRect.left - gap - greetX
        canvas.drawText(
            TextUtils.ellipsize(greeting, text, maxGreetW, TextUtils.TruncateAt.END).toString(),
            greetX, orbCy + text.textSize * 0.35f, text
        )

        // Big clock + date (left column, centred between greeting and dua card)
        val now = Date()
        val is24 = prefs.use24Hour
        val timeStr = SimpleDateFormat(if (is24) "HH:mm" else "h:mm", Locale.getDefault()).format(now)
        val ampm = if (is24) "" else SimpleDateFormat("a", Locale.US).format(now)
        val colW = cardRect.left - gap - left
        text.typeface = light
        text.textSize = u * 0.17f
        val ampmPaint = TextPaint(text).apply { typeface = medium; textSize = u * 0.032f; color = ink2 }
        val needed = text.measureText(timeStr) + (if (ampm.isEmpty()) 0f else u * 0.012f + ampmPaint.measureText(ampm))
        if (needed > colW) text.textSize *= colW / needed
        val dateSize = u * 0.025f
        val blockH = text.textSize * 0.78f + dateSize * 1.9f
        val spaceTop = top + greetH
        val baseline = spaceTop + ((upperBottom - spaceTop) - blockH) / 2 + text.textSize * 0.78f
        text.color = ink
        canvas.drawText(timeStr, left - text.textSize * 0.03f, baseline, text)
        if (ampm.isNotEmpty()) {
            canvas.drawText(ampm, left + text.measureText(timeStr) + u * 0.012f, baseline, ampmPaint)
        }
        text.typeface = medium
        text.textSize = dateSize
        text.color = ink2
        val dateStr = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(now)
        canvas.drawText(dateStr, left, baseline + dateSize * 1.9f, text)
    }

    private fun drawWeather(canvas: Canvas, r: RectF, u: Float, w: WeatherNow?) {
        val radius = u * 0.024f
        paint.color = cardFill
        canvas.drawRoundRect(r, radius, radius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        paint.color = Color.argb(18, 255, 255, 255)
        canvas.drawRoundRect(r, radius, radius, paint)
        paint.style = Paint.Style.FILL

        val inPad = u * 0.022f
        val iconSize = min(u * 0.075f, r.height() * 0.5f)
        val iconRect = RectF(r.left + inPad, r.top + inPad, r.left + inPad + iconSize, r.top + inPad + iconSize)
        drawWeatherIcon(canvas, iconRect, w?.kind ?: WeatherNow.Kind.CLOUD)

        val tx = iconRect.right + u * 0.016f
        text.typeface = light
        text.textSize = u * 0.07f
        text.color = ink
        val temp = w?.let { "${it.tempC}°" } ?: "--°"
        canvas.drawText(temp, tx, iconRect.top + text.textSize * 0.82f, text)
        text.typeface = medium
        text.textSize = u * 0.02f
        text.color = ink2
        val cond = w?.description ?: "Turn on location for weather"
        canvas.drawText(
            TextUtils.ellipsize(cond, text, r.right - inPad - tx, TextUtils.TruncateAt.END).toString(),
            tx, iconRect.bottom, text
        )

        val metaY = r.bottom - inPad
        paint.color = Color.argb(18, 255, 255, 255)
        canvas.drawRect(r.left + inPad, metaY - u * 0.032f, r.right - inPad, metaY - u * 0.032f + 1f, paint)
        text.textSize = u * 0.017f
        text.color = ink3
        canvas.drawText("Outside", r.left + inPad, metaY, text)
        val ago = w?.let { agoText(it.fetchedAt) } ?: ""
        canvas.drawText(ago, r.right - inPad - text.measureText(ago), metaY, text)
    }

    private fun agoText(t: Long): String {
        val mins = ((System.currentTimeMillis() - t) / 60000).toInt()
        return if (mins < 1) "Just now" else "Updated $mins min ago"
    }

    private fun drawWeatherIcon(canvas: Canvas, r: RectF, kind: WeatherNow.Kind) {
        val s = r.width()
        val sunCx = r.left + s * 0.4f
        val sunCy = r.top + s * 0.38f
        if (kind == WeatherNow.Kind.SUN || kind == WeatherNow.Kind.SUN_CLOUD) {
            paint.color = sand
            canvas.drawCircle(sunCx, sunCy, s * 0.17f, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = s * 0.045f
            paint.strokeCap = Paint.Cap.ROUND
            for (i in 0 until 8) {
                val a = Math.toRadians(i * 45.0)
                val r1 = s * 0.25f
                val r2 = s * 0.32f
                canvas.drawLine(
                    sunCx + (r1 * cos(a)).toFloat(), sunCy + (r1 * sin(a)).toFloat(),
                    sunCx + (r2 * cos(a)).toFloat(), sunCy + (r2 * sin(a)).toFloat(), paint
                )
            }
            paint.style = Paint.Style.FILL
        }
        if (kind != WeatherNow.Kind.SUN) {
            paint.color = Color.parseColor("#C9D3E6")
            val cy = r.top + s * 0.68f
            val cloud = Path().apply {
                addCircle(r.left + s * 0.42f, cy, s * 0.15f, Path.Direction.CW)
                addCircle(r.left + s * 0.62f, cy - s * 0.08f, s * 0.19f, Path.Direction.CW)
                addCircle(r.left + s * 0.80f, cy + s * 0.02f, s * 0.13f, Path.Direction.CW)
                addRect(r.left + s * 0.42f, cy, r.left + s * 0.80f, cy + s * 0.15f, Path.Direction.CW)
            }
            canvas.drawPath(cloud, paint)
            if (kind == WeatherNow.Kind.RAIN || kind == WeatherNow.Kind.STORM) {
                paint.color = if (kind == WeatherNow.Kind.STORM) sand else teal
                paint.strokeWidth = s * 0.05f
                paint.strokeCap = Paint.Cap.ROUND
                paint.style = Paint.Style.STROKE
                for (i in 0..2) {
                    val x = r.left + s * (0.48f + i * 0.14f)
                    canvas.drawLine(x, cy + s * 0.22f, x - s * 0.05f, cy + s * 0.32f, paint)
                }
                paint.style = Paint.Style.FILL
            }
        }
    }

    /** Dua al-Safar card: the line being recited in large Arabic, like song lyrics. */
    private fun drawDuaCard(canvas: Canvas, r: RectF, u: Float, speaking: Boolean) {
        val radius = u * 0.022f
        val reciting = Welcome.isReciting
        paint.color = duaFill
        canvas.drawRoundRect(r, radius, radius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = if (reciting) 2f else 1f
        paint.color = if (reciting) Color.argb(140, 232, 194, 122) else line
        canvas.drawRoundRect(r, radius, radius, paint)
        paint.style = Paint.Style.FILL

        val inPad = u * 0.022f
        val lineIdx = Welcome.currentDuaLine.takeIf { it in DuaSafar.LINES.indices } ?: 0
        val dua = DuaSafar.LINES[lineIdx]
        val next = DuaSafar.LINES.getOrNull(lineIdx + 1)

        // Label on the left
        text.typeface = medium
        text.textSize = u * 0.016f
        text.color = sand
        text.letterSpacing = 0.14f
        val label = "DUA AL-SAFAR"
        canvas.drawText(label, r.left + inPad, r.centerY() + text.textSize * 0.35f, text)
        val labelW = text.measureText(label)
        text.letterSpacing = 0f

        // State on the right
        text.textSize = u * 0.017f
        text.color = if (speaking) sand else ink3
        val state = when {
            reciting -> "${lineIdx + 1} / ${DuaSafar.LINES.size}"
            Welcome.isGreeting -> "Welcome…"
            else -> "Tap Dua to play"
        }
        val stateW = text.measureText(state)
        canvas.drawText(state, r.right - inPad - stateW, r.centerY() + text.textSize * 0.35f, text)

        // Lyrics: current line large and bright, next line small and dim.
        val midL = r.left + inPad * 2 + labelW
        val midR = r.right - inPad * 2 - stateW
        val midW = (midR - midL).toInt().coerceAtLeast(10)
        val ar = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = serif; color = if (reciting) ink else ink2; textSize = u * 0.032f
        }
        val nx = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = serif; color = ink3; textSize = u * 0.019f; textAlign = Paint.Align.RIGHT
        }
        val nextText = next?.let { TextUtils.ellipsize(it.arabic, nx, midW.toFloat(), TextUtils.TruncateAt.END).toString() }
        val nextH = if (nextText != null && reciting) nx.textSize * 1.5f else 0f
        val maxH = r.height() - inPad * 1.6f - nextH
        var arLayout = layout(dua.arabic, ar, midW, Layout.Alignment.ALIGN_OPPOSITE, 2)
        while (arLayout.height > maxH && ar.textSize > u * 0.018f) {
            ar.textSize *= 0.92f
            arLayout = layout(dua.arabic, ar, midW, Layout.Alignment.ALIGN_OPPOSITE, 2)
        }
        val blockH = arLayout.height + nextH
        val y0 = r.centerY() - blockH / 2
        canvas.save()
        canvas.translate(midL, y0)
        arLayout.draw(canvas)
        canvas.restore()
        if (nextText != null && reciting) {
            canvas.drawText(nextText, midL + midW, y0 + arLayout.height + nx.textSize * 1.25f, nx)
        }

        // Progress along the bottom edge while reciting.
        val p = Welcome.progress
        if (p >= 0f) {
            val barY = r.bottom - u * 0.006f
            paint.color = Color.argb(40, 255, 255, 255)
            canvas.drawRect(r.left + radius, barY, r.right - radius, barY + u * 0.003f, paint)
            paint.color = sand
            canvas.drawRect(r.left + radius, barY, r.left + radius + (r.width() - 2 * radius) * p, barY + u * 0.003f, paint)
        }
    }

    private fun layout(s: String, p: TextPaint, width: Int, align: Layout.Alignment, maxLines: Int): StaticLayout =
        StaticLayout.Builder.obtain(s, 0, s.length, p, width)
            .setAlignment(align)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()

    companion object {
        /** The renderer currently drawing on the car screen. */
        private var active: DashboardRenderer? = null
    }
}
