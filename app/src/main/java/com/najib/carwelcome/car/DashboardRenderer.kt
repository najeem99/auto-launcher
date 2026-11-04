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
import android.text.style.ForegroundColorSpan
import android.text.Spanned
import android.text.SpannableString
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import com.najib.carwelcome.CheckIn
import com.najib.carwelcome.CrashLog
import com.najib.carwelcome.DuaPlayer
import com.najib.carwelcome.DuaSafar
import com.najib.carwelcome.Prefs
import com.najib.carwelcome.R
import com.najib.carwelcome.Weather
import com.najib.carwelcome.WeatherNow
import com.najib.carwelcome.Welcome
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Draws the dashboard on the car screen: greeting, big clock, weather card and
 * the Dua al-Safar card. Redraws every second. Layout follows the HTML mockup.
 */
class DashboardRenderer(
    private val carContext: CarContext,
    private val onTile: (DashboardRenderer.Tile) -> Unit = {},
    private val onTick: () -> Unit = {},
) : SurfaceCallback {

    /** True while the car's speed (or GPS) says it's standing still: shows a blinking P. */
    var isStopped: () -> Boolean = { false }
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
            CrashLog.guard("dashboard tick") {
                onTick()
                Weather.refresh(carContext) // no-op unless the reading is 15 minutes old
                if (lyricsFull && !Welcome.isSpeaking) lyricsFull = false
                draw()
            }
            val now = System.currentTimeMillis()
            // Land just after each new second so the clock's seconds tick evenly.
            val delay = if (Welcome.isSpeaking) 250L else 1000L - now % 1000L + 15L
            handler.postDelayed(this, delay)
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

    // The same fonts as the mockup, bundled in assets/fonts (SIL Open Font License).
    private val light = font("Sora.ttf", 200, Typeface.create("sans-serif-light", Typeface.NORMAL))      // clock
    private val light300 = font("Sora.ttf", 300, light)
    private val clockFont = font("Sora.ttf", 600, Typeface.create("sans-serif-medium", Typeface.NORMAL)) // bold clock                                                   // temperature
    private val regular = font("Manrope.ttf", 500, Typeface.create("sans-serif", Typeface.NORMAL))
    private val medium = font("Manrope.ttf", 600, Typeface.create("sans-serif-medium", Typeface.NORMAL))
    private val bold = font("Manrope.ttf", 700, medium)
    private val serif = runCatching { Typeface.createFromAsset(carContext.assets, "fonts/Amiri-Regular.ttf") }
        .getOrDefault(Typeface.create(Typeface.SERIF, Typeface.NORMAL))

    private fun font(file: String, weight: Int, fallback: Typeface): Typeface = runCatching {
        Typeface.Builder(carContext.assets, "fonts/$file")
            .setFontVariationSettings("'wght' $weight")
            .setWeight(weight)
            .build()
    }.getOrNull() ?: fallback

    private val textFlags = Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val text = TextPaint(textFlags)

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
        } catch (e: Exception) {
            // A drawing bug (an odd screen size, a split-screen panel) must never take the
            // car app down; note it and try again on the next frame.
            CrashLog.caught("dashboard draw ${c.width}x${c.height} $visibleArea", e)
            lastFrameKey = null
        } finally {
            runCatching { surface.unlockCanvasAndPost(canvas) }
        }
    }

    /** Everything that can change what is drawn; identical keys mean no redraw. */
    private fun frameKey(c: SurfaceContainer): String {
        val p = Welcome.progress
        val w = Weather.latest
        return listOf(
            c.width, c.height, visibleArea,
            SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(Date()), lyricsFull, DuaPlayer.completedThisDrive, isStopped(), prefs.use24Hour, prefs.name,
            Welcome.isGreeting, Welcome.isReciting, DuaPlayer.isPlaying, Welcome.currentDuaLine, Welcome.currentDuaWord, Welcome.duaButton(), c.dpi,
            if (p < 0f) -1 else (p * 400).toInt(),
            CheckIn.phase, CheckIn.message, prefs.checkInUrl.isBlank(),
            w?.tempC, w?.code, w?.isDay, w?.place, w?.let { (System.currentTimeMillis() - it.fetchedAt) / 60000 },
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

    enum class Tile { DUA, MAP, CLAUDE, UTILITIES, YOUTUBE, HOME, WORK, CHECKIN }

    /** Which layout fits the space Android Auto gives us (full screen or a split-screen panel). */
    private enum class Mode { FULL, MEDIUM, COMPACT }

    private var density = 1f
    private val tileRects = mutableMapOf<Tile, RectF>()
    /** Where the lyrics card is, for taps. */
    private var lyricsRect: RectF? = null
    /** Lyrics shown over the whole screen (tap the lyrics card while the dua plays). */
    private var lyricsFull = false

    /** Taps: the big buttons, and the lyrics card to open or close full-screen lyrics. */
    override fun onClick(x: Float, y: Float) {
        if (lyricsFull) {
            lyricsFull = false
            draw()
            return
        }
        tileRects.entries.firstOrNull { it.value.contains(x, y) }?.let { onTile(it.key); return }
        if (Welcome.isSpeaking && lyricsRect?.contains(x, y) == true) {
            lyricsFull = true
            draw()
        }
    }

    private fun paintDashboard(canvas: Canvas, w: Int, h: Int) {
        // Background: deep night with a teal glow top-left and a sand glow bottom-right.
        canvas.drawBitmap(backgroundFor(w, h), 0f, 0f, null)
        paint.shader = null
        tileRects.clear()
        lyricsRect = null

        val area = visibleArea?.takeIf { !it.isEmpty } ?: Rect(0, 0, w, h)
        density = ((container?.dpi ?: 160) / 160f).coerceAtLeast(0.75f)
        val wDp = area.width() / density
        val hDp = area.height() / density
        val mode = when {
            wDp < 420 || hDp < 230 -> Mode.COMPACT
            wDp < 620 || hDp < 330 -> Mode.MEDIUM
            else -> Mode.FULL
        }
        val u = min(area.width(), (area.height() * 1.67f).toInt()).toFloat() // ~width of a 5:3 box
        val pad = if (mode == Mode.COMPACT) 12 * density else u * 0.034f
        val inner = RectF(area.left + pad, area.top + pad, area.right - pad, area.bottom - pad)
        when {
            lyricsFull && Welcome.isSpeaking -> paintFullLyrics(canvas, inner)
            mode == Mode.COMPACT -> paintCompact(canvas, inner)
            else -> paintWide(canvas, inner, u, mode, wDp)
        }
        if (isStopped() && System.currentTimeMillis() / 1000 % 2 == 0L) drawParkedBadge(canvas, inner)
    }

    /** Full screen and medium panels: clock, weather and the lyrics card. Extras drop out as space shrinks. */
    private fun paintWide(canvas: Canvas, r: RectF, u: Float, mode: Mode, wDp: Float) {
        val full = mode == Mode.FULL
        val gap = u * 0.026f
        val speaking = Welcome.isSpeaking

        // Bottom row: the lyrics card until the dua has played, then Office/Home shortcuts
        // so the clock gets the room. The card grows while the dua plays.
        val showDua = speaking || !DuaPlayer.completedThisDrive
        val bottomH = r.height() * when {
            !showDua -> if (full) 0.22f else 0.28f
            Welcome.isReciting -> if (full) 0.50f else 0.58f
            else -> if (full) 0.32f else 0.42f
        }
        val bottomRect = RectF(r.left, r.bottom - bottomH, r.right, r.bottom)
        if (showDua) {
            drawDuaCard(canvas, bottomRect, u, speaking)
            lyricsRect = bottomRect
        } else {
            drawPlaces(canvas, bottomRect, u)
        }
        val upperBottom = bottomRect.top - gap

        // Greeting row only on the full layout.
        val greetH = if (full) u * 0.06f else 0f
        val showWeather = full || wDp >= 520
        val cardW = if (showWeather) u * (if (full) 0.30f else 0.34f) else 0f
        val cardRect = RectF(r.right - cardW, r.top + greetH + gap * 0.5f, r.right, upperBottom)
        if (showWeather) {
            val cardH = min(cardRect.height(), u * 0.25f)
            cardRect.top = cardRect.centerY() - cardH / 2
            cardRect.bottom = cardRect.top + cardH
            drawWeather(canvas, cardRect, u, Weather.latest)
        }
        if (full) drawGreeting(canvas, r.left, r.top, greetH, cardRect.left - gap, u, speaking)

        val clockRight = if (showWeather) cardRect.left - gap else r.right
        drawClock(canvas, RectF(r.left, r.top + greetH, clockRight, upperBottom), u, showDate = full)
    }

    /** Small split-screen card: big buttons first, then lyrics (while playing) or the clock if room is left. */
    private fun paintCompact(canvas: Canvas, r: RectF) {
        val d = density
        val gap = 10 * d
        val tiles = buildList {
            if (Welcome.duaButton() != Welcome.DuaButton.HIDDEN) add(Tile.DUA)
            add(Tile.MAP)
            add(Tile.UTILITIES)
            add(Tile.YOUTUBE)
            if (prefs.checkInUrl.isNotBlank()) add(Tile.CHECKIN)
        }
        // Buttons: one row along the bottom, or a column if the panel is tall and narrow.
        val tall = r.height() > r.width() * 1.3f
        val tileArea = if (tall) {
            RectF(r.left, r.bottom - min(r.height() * 0.6f, tiles.size * 84 * d), r.right, r.bottom)
        } else {
            RectF(r.left, r.bottom - min(r.height() * 0.45f, 96 * d), r.right, r.bottom)
        }
        tiles.forEachIndexed { i, t ->
            val rect = if (tall) {
                val th = (tileArea.height() - gap * (tiles.size - 1)) / tiles.size
                RectF(tileArea.left, tileArea.top + i * (th + gap), tileArea.right, tileArea.top + i * (th + gap) + th)
            } else {
                val tw = (tileArea.width() - gap * (tiles.size - 1)) / tiles.size
                RectF(tileArea.left + i * (tw + gap), tileArea.top, tileArea.left + i * (tw + gap) + tw, tileArea.bottom)
            }
            drawTile(canvas, rect, t)
            tileRects[t] = rect
        }

        val upper = RectF(r.left, r.top, r.right, tileArea.top - gap)
        if (upper.height() < 44 * d) return // no room: buttons only
        if (Welcome.isReciting || Welcome.isGreeting) {
            drawLyrics(canvas, upper, bare = true)
        } else {
            drawClock(canvas, upper, upper.width(), showDate = upper.height() > 120 * d)
        }
    }

    /**
     * Office and Home shortcuts (Google Maps directions). Office comes first on
     * weekday mornings (Mon–Fri, 7–11 AM); Home comes first the rest of the time.
     */
    private fun drawPlaces(canvas: Canvas, r: RectF, u: Float) {
        val gap = u * 0.022f
        val c = java.util.Calendar.getInstance()
        val weekday = c.get(java.util.Calendar.DAY_OF_WEEK) in java.util.Calendar.MONDAY..java.util.Calendar.FRIDAY
        val morning = c.get(java.util.Calendar.HOUR_OF_DAY) in 7..10
        val order = (if (weekday && morning) listOf(Tile.WORK, Tile.HOME) else listOf(Tile.HOME, Tile.WORK)).toMutableList()
        // Clock in sits next to Office in the morning, and last the rest of the day.
        if (prefs.checkInUrl.isNotBlank()) order.add(if (weekday && morning) 1 else 2, Tile.CHECKIN)
        val w = (r.width() - gap * (order.size - 1)) / order.size
        order.forEachIndexed { i, t ->
            val rect = RectF(r.left + i * (w + gap), r.top, r.left + i * (w + gap) + w, r.bottom)
            drawPlaceChip(canvas, rect, u, t, primary = i == 0)
            tileRects[t] = rect
        }
    }

    private fun drawPlaceChip(canvas: Canvas, r: RectF, u: Float, tile: Tile, primary: Boolean) {
        val radius = u * 0.022f
        paint.color = if (primary) Color.argb(46, 91, 209, 193) else cardFill
        canvas.drawRoundRect(r, radius, radius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = if (primary) 2f else 1f
        paint.color = if (primary) Color.argb(150, 91, 209, 193) else line
        canvas.drawRoundRect(r, radius, radius, paint)
        paint.style = Paint.Style.FILL
        val pad = r.height() * 0.22f
        val iconSize = min(r.height() * 0.5f, u * 0.07f)
        val checkIn = tile == Tile.CHECKIN
        val iconRes = when (tile) { Tile.WORK -> R.drawable.ic_work; Tile.CHECKIN -> R.drawable.ic_checkin; else -> R.drawable.ic_home }
        val icon = carContext.getDrawable(iconRes)?.mutate()
        icon?.setTint(when {
            checkIn && CheckIn.phase == CheckIn.Phase.DONE -> teal
            checkIn && CheckIn.phase == CheckIn.Phase.FAILED -> Color.parseColor("#FF8A7A")
            checkIn -> sand
            primary -> teal
            else -> ink2
        })
        val iy = r.centerY() - iconSize / 2
        icon?.setBounds((r.left + pad).toInt(), iy.toInt(), (r.left + pad + iconSize).toInt(), (iy + iconSize).toInt())
        icon?.draw(canvas)
        val tx = r.left + pad + iconSize + u * 0.02f
        val title = when (tile) { Tile.WORK -> "Office"; Tile.CHECKIN -> "Clock in"; else -> "Home" }
        text.textAlign = Paint.Align.LEFT
        text.typeface = bold
        text.textSize = min(r.height() * 0.28f, u * 0.04f)
        text.color = ink
        canvas.drawText(title, tx, r.centerY() - text.textSize * 0.05f, text)
        val titleSize = text.textSize
        text.typeface = regular
        text.textSize = titleSize * 0.55f
        text.color = ink2
        val sub = if (checkIn) CheckIn.statusLine(prefs) else "Directions in Google Maps"
        val subMax = r.right - tx - pad * 0.5f
        canvas.drawText(TextUtils.ellipsize(sub, text, subMax, TextUtils.TruncateAt.END).toString(), tx, r.centerY() + titleSize * 0.85f, text)
    }

    /** Blinking "P" in the top-right corner while the car is stopped. */
    private fun drawParkedBadge(canvas: Canvas, r: RectF) {
        val size = max(28 * density, r.height() * 0.075f)
        val box = RectF(r.right - size, r.top, r.right, r.top + size)
        paint.color = Color.parseColor("#2F6FED")
        canvas.drawRoundRect(box, size * 0.22f, size * 0.22f, paint)
        text.textAlign = Paint.Align.CENTER
        text.typeface = bold
        text.textSize = size * 0.72f
        text.color = Color.WHITE
        canvas.drawText("P", box.centerX(), box.centerY() + text.textSize * 0.36f, text)
        text.textAlign = Paint.Align.LEFT
    }

    /** The lyrics over the whole screen. Any tap closes them. */
    private fun paintFullLyrics(canvas: Canvas, r: RectF) {
        val d = density
        text.textAlign = Paint.Align.CENTER
        text.typeface = bold
        text.textSize = max(12 * d, r.height() * 0.04f)
        text.color = sand
        text.letterSpacing = 0.14f
        val idx = Welcome.currentDuaLine.takeIf { it in DuaSafar.LINES.indices } ?: 0
        val head = if (Welcome.isGreeting) "WELCOME" else "DUA AL-SAFAR  ·  ${idx + 1} / ${DuaSafar.LINES.size}"
        canvas.drawText(head, r.centerX(), r.top + text.textSize, text)
        text.letterSpacing = 0f
        text.typeface = regular
        text.textSize = max(11 * d, r.height() * 0.032f)
        text.color = ink3
        canvas.drawText("Tap anywhere to close", r.centerX(), r.bottom - text.textSize * 0.4f, text)
        text.textAlign = Paint.Align.LEFT
        val top = r.top + r.height() * 0.09f
        val bottom = r.bottom - r.height() * 0.08f
        drawLyrics(canvas, RectF(r.left, top, r.right, bottom), bare = true)
    }

    private fun drawTile(canvas: Canvas, r: RectF, tile: Tile) {
        val d = density
        val radius = 16 * d
        val (label, iconRes, accent) = when (tile) {
            Tile.DUA -> if (Welcome.duaButton() == Welcome.DuaButton.PAUSE)
                Triple("Pause", R.drawable.ic_pause, true) else Triple("Dua", R.drawable.ic_play, true)
            Tile.MAP -> Triple("Maps", R.drawable.ic_map, false)
            Tile.CLAUDE -> Triple("Claude", R.drawable.ic_claude, false)
            Tile.UTILITIES -> Triple("Utilities", R.drawable.ic_grid, false)
            Tile.YOUTUBE -> Triple("YouTube", R.drawable.ic_video, false)
            Tile.HOME -> Triple("Home", R.drawable.ic_home, false)
            Tile.WORK -> Triple("Office", R.drawable.ic_work, false)
            Tile.CHECKIN -> Triple(if (CheckIn.isRunning) "Working…" else "Clock in", R.drawable.ic_checkin, false)
        }
        paint.color = if (accent) sand else Color.parseColor("#1E283E")
        canvas.drawRoundRect(r, radius, radius, paint)
        val fg = if (accent) Color.parseColor("#2A1D05") else ink
        val iconSize = min(r.height() * 0.42f, 34 * d)
        text.typeface = bold
        text.textSize = min(r.height() * 0.2f, 17 * d)
        text.color = fg
        val showLabel = r.height() > 64 * d
        val total = iconSize + if (showLabel) text.textSize * 1.5f else 0f
        val iconTop = r.centerY() - total / 2
        val icon = carContext.getDrawable(iconRes)?.mutate()
        icon?.setTint(when (tile) {
            Tile.CLAUDE -> Color.parseColor("#D97757")
            Tile.MAP -> teal
            Tile.UTILITIES -> teal
            Tile.YOUTUBE -> Color.parseColor("#FF5A5A")
            Tile.CHECKIN -> if (CheckIn.phase == CheckIn.Phase.FAILED) Color.parseColor("#FF8A7A") else teal
            else -> fg
        })
        icon?.setBounds(
            (r.centerX() - iconSize / 2).toInt(), iconTop.toInt(),
            (r.centerX() + iconSize / 2).toInt(), (iconTop + iconSize).toInt()
        )
        icon?.draw(canvas)
        if (showLabel) {
            text.textAlign = Paint.Align.CENTER
            canvas.drawText(label, r.centerX(), iconTop + iconSize + text.textSize * 1.3f, text)
            text.textAlign = Paint.Align.LEFT
        }
    }

    private fun drawGreeting(canvas: Canvas, left: Float, top: Float, greetH: Float, maxRight: Float, u: Float, speaking: Boolean) {
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
        canvas.drawText(
            TextUtils.ellipsize(greeting, text, maxRight - greetX, TextUtils.TruncateAt.END).toString(),
            greetX, orbCy + text.textSize * 0.35f, text
        )
    }

    /** Big clock, fitted to [r]; the date is added only when asked and when it fits. */
    /** Big bold time with seconds; AM/PM sits above the seconds. */
    private fun drawClock(canvas: Canvas, r: RectF, u: Float, showDate: Boolean) {
        if (r.height() <= 0 || r.width() <= 0) return
        val now = Date()
        val is24 = prefs.use24Hour
        val timeStr = SimpleDateFormat(if (is24) "HH:mm" else "h:mm", Locale.getDefault()).format(now)
        val secStr = SimpleDateFormat("ss", Locale.US).format(now)
        val ampm = if (is24) "" else SimpleDateFormat("a", Locale.US).format(now)
        text.textAlign = Paint.Align.LEFT
        text.typeface = clockFont
        text.letterSpacing = -0.03f
        text.textSize = u * 0.17f
        val secPaint = TextPaint(text).apply { color = ink2; letterSpacing = 0f }
        val ampmPaint = TextPaint(text).apply { typeface = bold; color = ink2; letterSpacing = 0.06f }
        fun sizeSmall() {
            secPaint.textSize = text.textSize * 0.36f
            ampmPaint.textSize = text.textSize * 0.17f
        }
        sizeSmall()
        // Seconds use fixed-width digits so the clock doesn't jiggle.
        secPaint.fontFeatureSettings = "tnum"
        text.fontFeatureSettings = "tnum"
        fun needed() = text.measureText(timeStr) + u * 0.014f + max(secPaint.measureText("00"), ampmPaint.measureText(ampm))
        if (needed() > r.width()) {
            text.textSize *= r.width() / needed()
            sizeSmall()
        }
        var dateSize = if (showDate) u * 0.028f else 0f
        if (text.textSize * 0.78f + dateSize * 1.9f > r.height()) {
            dateSize = 0f
            text.textSize = min(text.textSize, r.height() / 0.85f)
            sizeSmall()
        }
        val blockH = text.textSize * 0.78f + dateSize * 1.9f
        val baseline = r.top + (r.height() - blockH) / 2 + text.textSize * 0.78f
        text.color = ink
        canvas.drawText(timeStr, r.left - text.textSize * 0.03f, baseline, text)
        val sx = r.left + text.measureText(timeStr) + u * 0.014f
        canvas.drawText(secStr, sx, baseline, secPaint)
        if (ampm.isNotEmpty()) {
            canvas.drawText(ampm, sx + u * 0.004f, baseline - secPaint.textSize * 0.78f - ampmPaint.textSize * 0.7f, ampmPaint)
        }
        text.letterSpacing = 0f
        text.fontFeatureSettings = null
        if (dateSize > 0f) {
            text.typeface = medium
            text.textSize = dateSize
            text.color = ink2
            val dateStr = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(now)
            canvas.drawText(
                TextUtils.ellipsize(dateStr, text, r.width(), TextUtils.TruncateAt.END).toString(),
                r.left, baseline + dateSize * 1.9f, text
            )
        }
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
        text.typeface = light300
        text.letterSpacing = -0.03f
        text.textSize = u * 0.074f
        text.color = ink
        val temp = w?.let { "${it.tempC}°" } ?: "--°"
        // Keep the temperature and the condition inside the space above the divider.
        val metaTop = r.bottom - inPad - u * 0.032f
        val condSize = u * 0.02f
        text.textSize = min(text.textSize, (metaTop - r.top - inPad - condSize * 1.8f) / 0.82f).coerceAtLeast(10f)
        val tempBase = r.top + inPad + text.textSize * 0.82f
        canvas.drawText(temp, tx, tempBase, text)
        text.letterSpacing = 0f
        text.typeface = regular
        text.textSize = condSize
        text.color = ink2
        val cond = w?.description ?: "Loading weather…"
        canvas.drawText(
            TextUtils.ellipsize(cond, text, r.right - inPad - tx, TextUtils.TruncateAt.END).toString(),
            tx, tempBase + condSize * 1.6f, text
        )

        val metaY = r.bottom - inPad
        paint.color = Color.argb(18, 255, 255, 255)
        canvas.drawRect(r.left + inPad, metaY - u * 0.032f, r.right - inPad, metaY - u * 0.032f + 1f, paint)
        text.textSize = u * 0.017f
        text.color = ink3
        val ago = w?.let { agoText(it.fetchedAt) } ?: ""
        val agoW = text.measureText(ago)
        val place = w?.place?.takeIf { it.isNotBlank() } ?: "Outside"
        canvas.drawText(
            TextUtils.ellipsize(place, text, r.width() - inPad * 3 - agoW, TextUtils.TruncateAt.END).toString(),
            r.left + inPad, metaY, text
        )
        canvas.drawText(ago, r.right - inPad - agoW, metaY, text)
    }

    private fun agoText(t: Long): String {
        val mins = ((System.currentTimeMillis() - t) / 60000).toInt()
        return if (mins < 1) "Just now" else "$mins min ago"
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
        if (kind == WeatherNow.Kind.MOON || kind == WeatherNow.Kind.MOON_CLOUD) {
            paint.color = sand
            val moon = Path().apply { addCircle(sunCx, sunCy, s * 0.22f, Path.Direction.CW) }
            val bite = Path().apply { addCircle(sunCx + s * 0.11f, sunCy - s * 0.08f, s * 0.19f, Path.Direction.CW) }
            moon.op(bite, Path.Op.DIFFERENCE)
            canvas.drawPath(moon, paint)
        }
        if (kind != WeatherNow.Kind.SUN && kind != WeatherNow.Kind.MOON) {
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

    /** Dua al-Safar card: label, state, and the lyrics, as large as the card allows. */
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

        // Label and state stacked on the left, so the lyrics get the full width on the right.
        text.typeface = bold
        text.textSize = u * 0.016f
        text.color = sand
        text.letterSpacing = 0.14f
        val label = "DUA AL-SAFAR"
        val labelW = text.measureText(label)
        canvas.drawText(label, r.left + inPad, r.centerY() - text.textSize * 0.3f, text)
        text.letterSpacing = 0f
        text.typeface = regular
        text.textSize = u * 0.017f
        text.color = if (speaking) sand else ink3
        val state = when {
            reciting && !DuaPlayer.isPlaying -> "Paused · ${lineIdx + 1}/${DuaSafar.LINES.size}"
            reciting -> "${lineIdx + 1} / ${DuaSafar.LINES.size}"
            Welcome.isGreeting -> "Welcome…"
            else -> "Tap Dua to play"
        }
        canvas.drawText(
            TextUtils.ellipsize(state, text, labelW, TextUtils.TruncateAt.END).toString(),
            r.left + inPad, r.centerY() + text.textSize * 1.2f, text
        )

        // Same margin on both sides so the words sit in the middle of the card.
        val side = inPad * 2 + labelW
        val lyrics = RectF(r.left + side, r.top + inPad * 0.6f, r.right - side, r.bottom - inPad * 0.9f)
        drawLyrics(canvas, lyrics, bare = false)

        // Progress along the bottom edge.
        val p = Welcome.progress
        if (p >= 0f) {
            val barH = max(2f, u * 0.003f)
            val barY = r.bottom - u * 0.007f
            paint.color = Color.argb(40, 255, 255, 255)
            canvas.drawRect(r.left + radius, barY, r.right - radius, barY + barH, paint)
            paint.color = sand
            canvas.drawRect(r.left + radius, barY, r.left + radius + (r.width() - 2 * radius) * p, barY + barH, paint)
        }
    }

    /**
     * The lyrics: the phrase being recited in large Arabic, sized to fill [r], with each
     * word lighting up as the reciter says it. The transliteration sits underneath when
     * there is room.
     */
    private fun drawLyrics(canvas: Canvas, r: RectF, bare: Boolean) {
        val reciting = Welcome.isReciting
        val lineIdx = Welcome.currentDuaLine.takeIf { it in DuaSafar.LINES.indices } ?: 0
        val dua = DuaSafar.LINES[lineIdx]
        val word = if (reciting) Welcome.currentDuaWord else -1
        val width = r.width().toInt().coerceAtLeast(10)

        // Spoken words in white, the current word in gold, words still to come dimmed.
        val styled = SpannableString(dua.arabic)
        if (reciting && word >= 0) {
            var at = 0
            dua.words.forEachIndexed { i, w ->
                val color = when {
                    i == word -> sand
                    i < word -> ink
                    else -> ink3
                }
                styled.setSpan(ForegroundColorSpan(color), at, at + w.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                at += w.length + 1
            }
        }
        val ar = TextPaint(textFlags).apply {
            typeface = serif
            color = if (reciting && word < 0) ink3 else if (reciting) ink else ink2
            textSize = r.height() * 0.62f
        }
        val tr = TextPaint(textFlags).apply { typeface = regular; color = ink2; textAlign = Paint.Align.CENTER }
        var arLayout: StaticLayout
        var subH: Float
        while (true) {
            tr.textSize = max(ar.textSize * 0.26f, 11 * density)
            subH = if (reciting) tr.textSize * 1.7f else 0f
            arLayout = layout(styled, ar, width, Layout.Alignment.ALIGN_CENTER, 2)
            val cut = arLayout.getEllipsisCount(arLayout.lineCount - 1) > 0
            if ((!cut && arLayout.height + subH <= r.height()) || ar.textSize < 10f) break
            ar.textSize *= 0.94f
        }
        if (subH > 0f && arLayout.height + subH > r.height()) subH = 0f
        val y0 = r.centerY() - (arLayout.height + subH) / 2
        canvas.save()
        canvas.translate(r.left, y0)
        arLayout.draw(canvas)
        canvas.restore()
        if (subH > 0f) {
            val sub = TextUtils.ellipsize(dua.transliteration, tr, r.width(), TextUtils.TruncateAt.END).toString()
            canvas.drawText(sub, r.centerX(), y0 + arLayout.height + tr.textSize * 1.35f, tr)
        }
        if (bare) {
            val p = Welcome.progress
            if (p >= 0f) {
                val barH = max(3f, 3 * density)
                paint.color = Color.argb(40, 255, 255, 255)
                canvas.drawRect(r.left, r.bottom - barH, r.right, r.bottom, paint)
                paint.color = sand
                canvas.drawRect(r.left, r.bottom - barH, r.left + r.width() * p, r.bottom, paint)
            }
        }
    }

    private fun layout(s: CharSequence, p: TextPaint, rawWidth: Int, align: Layout.Alignment, maxLines: Int): StaticLayout =
        StaticLayout.Builder.obtain(s, 0, s.length, p, rawWidth.coerceAtLeast(1))
            .setAlignment(align)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()

    companion object {
        /** The renderer currently drawing on the car screen. */
        private var active: DashboardRenderer? = null
    }
}
