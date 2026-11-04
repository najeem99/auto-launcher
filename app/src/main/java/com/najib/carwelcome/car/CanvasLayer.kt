package com.najib.carwelcome.car

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.text.TextPaint
import android.text.TextUtils
import android.util.Log
import androidx.annotation.DrawableRes
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import com.najib.carwelcome.CrashLog

/**
 * A screen drawn straight onto the car surface (the Utilities and Speedometer screens).
 * SurfaceHub lends it the surface while its screen is on top. It redraws once a
 * second, or faster while [wantsFastFrames] (the speedometer needle easing), and only
 * when [frameKey] says something visible changed.
 */
abstract class CanvasLayer(protected val carContext: CarContext) : SurfaceCallback {

    protected val look = Look.get(carContext)
    protected val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    protected val text = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG)
    /** Screen pixels per dp on the car display. */
    protected var density = 1f
        private set

    private var container: SurfaceContainer? = null
    private var visibleArea: Rect? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastKey: String? = null
    private var background: Bitmap? = null
    private val taps = mutableListOf<Pair<RectF, () -> Unit>>()

    private val tick = object : Runnable {
        override fun run() {
            CrashLog.guard("${this@CanvasLayer.javaClass.simpleName} frame") {
                beforeFrame()
                draw()
            }
            val delay = if (wantsFastFrames()) FAST_FRAME_MS else 1000L - System.currentTimeMillis() % 1000L + 15L
            handler.postDelayed(this, delay)
        }
    }

    /** Called before every frame, to advance animations. */
    protected open fun beforeFrame() {}
    /** True while an animation is running and frames should come quickly. */
    protected open fun wantsFastFrames(): Boolean = false
    /** Everything that changes what is drawn; the same key means no redraw. */
    protected abstract fun frameKey(): String
    /** Draws the screen into [area], the part of the car screen not covered by Android Auto. */
    protected abstract fun render(canvas: Canvas, area: Rect)

    /** Makes [r] tappable; the last one added wins when rectangles overlap. */
    protected fun onTap(r: RectF, action: () -> Unit) { taps += RectF(r) to action }

    /** Redraw soon, e.g. when a new speed reading arrives. */
    fun kick() {
        if (container == null) return
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    fun stop() {
        handler.removeCallbacks(tick)
        container = null
    }

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        container = surfaceContainer
        lastKey = null
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        this.visibleArea = Rect(visibleArea)
        lastKey = null
        draw()
    }

    override fun onStableAreaChanged(stableArea: Rect) {}

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        container = null
        handler.removeCallbacks(tick)
        background?.recycle()
        background = null
    }

    override fun onClick(x: Float, y: Float) {
        taps.lastOrNull { it.first.contains(x, y) }?.second?.invoke()
    }

    fun draw() {
        val c = container ?: return
        val surface = c.surface ?: return
        if (!surface.isValid) return
        val key = "${c.width}|${c.height}|${c.dpi}|$visibleArea|${frameKey()}"
        if (key == lastKey) return
        // Hardware canvas: each frame is a complete, GPU-drawn buffer (no tearing).
        val canvas = try {
            surface.lockHardwareCanvas()
        } catch (_: Exception) {
            try { surface.lockCanvas(null) } catch (_: Exception) { null }
        } ?: return
        lastKey = key
        try {
            canvas.drawBitmap(backgroundFor(c.width, c.height), 0f, 0f, null)
            paint.shader = null
            taps.clear()
            density = (c.dpi / 160f).coerceAtLeast(0.75f)
            val area = visibleArea?.takeIf { !it.isEmpty } ?: Rect(0, 0, c.width, c.height)
            render(canvas, area)
        } catch (e: Exception) {
            // A drawing bug must never take the car app down; the next frame tries again.
            CrashLog.caught("${javaClass.simpleName} draw", e)
            lastKey = null
        } finally {
            runCatching { surface.unlockCanvasAndPost(canvas) }
        }
    }

    /** The night gradient with a teal glow top-left and a sand glow bottom-right, drawn once. */
    private fun backgroundFor(w: Int, h: Int): Bitmap {
        background?.let { if (it.width == w && it.height == h && !it.isRecycled) return it }
        background?.recycle()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val bg = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
        p.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), look.night1, look.night2, Shader.TileMode.CLAMP)
        bg.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = RadialGradient(w * 0.08f, -h * 0.1f, w * 0.6f, Color.argb(36, 91, 209, 193), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        bg.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = RadialGradient(w.toFloat(), h * 1.1f, w * 0.5f, Color.argb(31, 232, 194, 122), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        bg.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        background = bmp
        return bmp
    }

    // ---- Small drawing helpers shared by the screens ----

    /** A rounded card with a thin border. */
    protected fun card(canvas: Canvas, r: RectF, radius: Float, fill: Int = look.tileFill, stroke: Int = look.line, strokeW: Float = 1f) {
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = fill
        canvas.drawRoundRect(r, radius, radius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = strokeW
        paint.color = stroke
        canvas.drawRoundRect(r, radius, radius, paint)
        paint.style = Paint.Style.FILL
    }

    /** A vector icon tinted [tint], centred in [r]. */
    protected fun icon(canvas: Canvas, @DrawableRes res: Int, r: RectF, tint: Int) {
        val d = carContext.getDrawable(res)?.mutate() ?: return
        d.setTint(tint)
        d.setBounds(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt())
        d.draw(canvas)
    }

    /** One line of text cut with "…" to fit [maxW]. Uses the current [text] paint. */
    protected fun line(canvas: Canvas, s: String, x: Float, baseline: Float, maxW: Float) {
        if (maxW <= 0f) return
        canvas.drawText(TextUtils.ellipsize(s, text, maxW, TextUtils.TruncateAt.END).toString(), x, baseline, text)
    }

    companion object {
        /** About 8 frames a second while the needle moves. */
        const val FAST_FRAME_MS = 120L
    }
}
