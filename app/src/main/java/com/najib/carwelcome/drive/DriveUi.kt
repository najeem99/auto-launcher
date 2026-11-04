package com.najib.carwelcome.drive

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Colours, fonts and small building blocks shared by the phone screens (same look as the car dashboard). */
object DriveUi {
    val night1 = Color.parseColor("#0D1526")
    val night2 = Color.parseColor("#0A101D")
    val ink = Color.parseColor("#EEF2FA")
    val ink2 = Color.parseColor("#A9B6D0")
    val ink3 = Color.parseColor("#6E7C99")
    val sand = Color.parseColor("#E8C27A")
    val teal = Color.parseColor("#5BD1C1")
    val line = Color.parseColor("#24314D")
    val card = Color.parseColor("#141E33")
    val card2 = Color.parseColor("#1A2640")
    val green = Color.parseColor("#4ADE80")
    val orange = Color.parseColor("#FB923C")
    val red = Color.parseColor("#F87171")

    /** Moving / slow traffic / standing colours in the bars. */
    val movingColor = teal
    val slowColor = orange
    val idleColor = red

    /** Under a quarter of the time waiting is fine, up to 45% is heavy, beyond that is bad. */
    fun waitColor(share: Float) = when {
        share < 0.25f -> green
        share < 0.45f -> orange
        else -> red
    }

    fun waitWord(share: Float) = when {
        share < 0.25f -> "Smooth"
        share < 0.45f -> "Busy"
        else -> "Heavy traffic"
    }

    /** Happy when the traffic was light, sad when most of the time was spent waiting. */
    fun emoji(share: Float) = when {
        share < 0.25f -> "\uD83D\uDE04"   // grinning
        share < 0.45f -> "\uD83D\uDE10"   // neutral
        else -> "\uD83D\uDE23"            // persevering
    }

    private var fonts: Map<String, Typeface>? = null
    fun font(context: Context, name: String): Typeface {
        val f = fonts ?: run {
            fun make(file: String, w: Int, fb: Typeface) = runCatching {
                Typeface.Builder(context.assets, "fonts/$file").setFontVariationSettings("'wght' $w").setWeight(w).build()
            }.getOrNull() ?: fb
            mapOf(
                "display" to make("Sora.ttf", 600, Typeface.DEFAULT_BOLD),
                "light" to make("Sora.ttf", 300, Typeface.DEFAULT),
                "regular" to make("Manrope.ttf", 500, Typeface.DEFAULT),
                "medium" to make("Manrope.ttf", 600, Typeface.DEFAULT),
                "bold" to make("Manrope.ttf", 700, Typeface.DEFAULT_BOLD),
            ).also { fonts = it }
        }
        return f[name] ?: Typeface.DEFAULT
    }

    fun dp(context: Context, v: Float) = (v * context.resources.displayMetrics.density).toInt()

    fun rounded(color: Int, radiusPx: Float, stroke: Int? = null, strokePx: Int = 0) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusPx
        if (stroke != null) setStroke(strokePx, stroke)
    }

    fun text(context: Context, s: CharSequence, sp: Float, color: Int = ink, font: String = "regular") =
        TextView(context).apply {
            text = s
            textSize = sp
            setTextColor(color)
            typeface = font(context, font)
            includeFontPadding = false
        }

    /** A card with the dashboard look. */
    fun card(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val p = dp(context, 16f)
        setPadding(p, p, p, p)
        background = rounded(card, dp(context, 20f).toFloat(), line, dp(context, 1f))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(context, 14f) }
    }

    /** Small caps label above a section. */
    fun label(context: Context, s: String) = text(context, s.uppercase(Locale.getDefault()), 12f, ink3, "bold").apply {
        letterSpacing = 0.12f
    }

    /** A stat: small label over a value, used in grids. */
    fun stat(context: Context, label: String, value: String, valueColor: Int = ink, sub: String? = null): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(context, 12f)
            setPadding(p, p, p, p)
            background = rounded(card2, dp(context, 14f).toFloat())
            addView(text(context, label, 12f, ink3, "medium"))
            addView(text(context, value, 20f, valueColor, "display").apply { setPadding(0, dp(context, 6f), 0, 0) })
            if (sub != null) addView(text(context, sub, 11f, ink3).apply { setPadding(0, dp(context, 4f), 0, 0) })
        }

    /** Lays [views] out in rows of [cols], evenly spaced. */
    fun grid(context: Context, views: List<View>, cols: Int = 2): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val gap = dp(context, 8f)
        views.chunked(cols).forEachIndexed { r, rowViews ->
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                rowViews.forEachIndexed { i, v ->
                    addView(v, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                        if (i > 0) marginStart = gap
                    })
                }
                repeat(cols - rowViews.size) {
                    addView(View(context), LinearLayout.LayoutParams(0, 0, 1f).apply { marginStart = gap })
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                if (r > 0) topMargin = gap
            })
        }
    }

    /** Legend row for the time bar. */
    fun legend(context: Context, moving: Long, slow: Long, idle: Long): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(context, 8f), 0, 0)
        fun item(color: Int, name: String, ms: Long) {
            addView(View(context).apply { background = rounded(color, dp(context, 4f).toFloat()) },
                LinearLayout.LayoutParams(dp(context, 8f), dp(context, 8f)).apply { marginEnd = dp(context, 6f) })
            addView(text(context, "$name ${duration(ms)}", 12f, ink2).apply { setPadding(0, 0, dp(context, 14f), 0) })
        }
        item(movingColor, "Driving", moving)
        item(slowColor, "Slow", slow)
        item(idleColor, "Standing", idle)
    }

    // ---- Formatting ---------------------------------------------------------------

    fun duration(ms: Long): String {
        val min = (ms + 30_000) / 60_000
        return when {
            ms in 1..59_999 -> "${(ms / 1000).coerceAtLeast(1)} s"
            min < 60 -> "$min min"
            else -> "${min / 60} h ${min % 60} m"
        }
    }

    fun km(m: Double) = if (m < 10_000) "%.1f km".format(m / 1000) else "%.0f km".format(m / 1000)
    fun kmh(v: Float) = if (v <= 0f) "–" else "%.0f km/h".format(v)
    fun time(context: Context, ms: Long): String =
        (if (android.text.format.DateFormat.is24HourFormat(context)) SimpleDateFormat("HH:mm", Locale.getDefault())
        else SimpleDateFormat("h:mm a", Locale.getDefault())).format(Date(ms))

    fun day(ms: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = ms }
        val today = Calendar.getInstance()
        val sameYear = c.get(Calendar.YEAR) == today.get(Calendar.YEAR)
        val d = c.get(Calendar.DAY_OF_YEAR)
        val t = today.get(Calendar.DAY_OF_YEAR)
        return when {
            sameYear && d == t -> "Today"
            sameYear && d == t - 1 -> "Yesterday"
            sameYear -> SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(ms))
            else -> SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(ms))
        }
    }
}

/** A rounded bar split into driving / slow traffic / standing time. */
class TimeBar(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var parts = longArrayOf(0, 0, 0)

    fun set(moving: Long, slow: Long, idle: Long) { parts = longArrayOf(moving, slow, idle); invalidate() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), DriveUi.dp(context, 12f))
    }

    override fun onDraw(canvas: Canvas) {
        val r = height / 2f
        val w = width.toFloat()
        val clip = Path().apply { addRoundRect(RectF(0f, 0f, w, height.toFloat()), r, r, Path.Direction.CW) }
        canvas.save()
        canvas.clipPath(clip)
        paint.color = DriveUi.line
        canvas.drawRect(0f, 0f, w, height.toFloat(), paint)
        val total = parts.sum().toFloat()
        if (total > 0) {
            var x = 0f
            val colors = intArrayOf(DriveUi.movingColor, DriveUi.slowColor, DriveUi.idleColor)
            parts.forEachIndexed { i, p ->
                val pw = w * p / total
                paint.color = colors[i]
                canvas.drawRect(x, 0f, x + pw, height.toFloat(), paint)
                x += pw
            }
        }
        canvas.restore()
    }
}

/** Kilometres per hour / day / month as bars, each coloured by how much of it was waiting. */
class KmChart(context: Context) : View(context) {
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = DriveUi.ink3; textSize = DriveUi.dp(context, 10f).toFloat(); typeface = DriveUi.font(context, "medium")
        textAlign = Paint.Align.CENTER
    }
    private val value = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = DriveUi.ink2; textSize = DriveUi.dp(context, 10f).toFloat(); typeface = DriveUi.font(context, "bold")
        textAlign = Paint.Align.CENTER
    }
    private var km = FloatArray(0)
    private var colors = IntArray(0)
    private var labels: List<String> = emptyList()
    /** Called with the index of a tapped bar that has kilometres. */
    var onBar: (Int) -> Unit = {}

    fun set(km: FloatArray, colors: IntArray, labels: List<String>) {
        this.km = km; this.colors = colors; this.labels = labels; invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), DriveUi.dp(context, 150f))
    }

    private fun slot() = if (km.isEmpty()) 1f else width.toFloat() / km.size

    override fun onDraw(canvas: Canvas) {
        if (km.isEmpty()) return
        val top = DriveUi.dp(context, 16f).toFloat()
        val bottom = height - DriveUi.dp(context, 18f).toFloat()
        val max = km.maxOrNull()?.coerceAtLeast(1f) ?: 1f
        val slot = slot()
        val bw = (slot * 0.62f).coerceAtMost(DriveUi.dp(context, 28f).toFloat())
        val r = (bw / 2).coerceAtMost(DriveUi.dp(context, 6f).toFloat())
        val showValues = km.size <= 12
        km.forEachIndexed { i, v ->
            val cx = slot * i + slot / 2
            // A faint track for every slot, so empty days still show.
            bar.color = DriveUi.line
            canvas.drawRoundRect(RectF(cx - bw / 2, bottom - DriveUi.dp(context, 3f), cx + bw / 2, bottom), r, r, bar)
            if (v > 0f) {
                val h = ((bottom - top) * v / max).coerceAtLeast(DriveUi.dp(context, 4f).toFloat())
                bar.color = colors.getOrElse(i) { DriveUi.teal }
                canvas.drawRoundRect(RectF(cx - bw / 2, bottom - h, cx + bw / 2, bottom), r, r, bar)
                if (showValues) canvas.drawText(if (v < 10) "%.1f".format(v) else "%.0f".format(v), cx, bottom - h - DriveUi.dp(context, 4f), value)
            }
            labels.getOrNull(i)?.takeIf { it.isNotEmpty() }?.let { canvas.drawText(it, cx, height.toFloat() - 3, label) }
        }
    }

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        if (event.action == android.view.MotionEvent.ACTION_DOWN) return true
        if (event.action == android.view.MotionEvent.ACTION_UP) {
            val i = (event.x / slot()).toInt()
            if (i in km.indices && km[i] > 0f) { performClick(); onBar(i) }
            return true
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()
}

/** Speed over the trip, as a filled line. */
class SpeedGraph(context: Context) : View(context) {
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = DriveUi.dp(context, 2f).toFloat()
        strokeJoin = Paint.Join.ROUND; color = DriveUi.teal
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = DriveUi.line; strokeWidth = 1f }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = DriveUi.ink3; textSize = DriveUi.dp(context, 10f).toFloat(); typeface = DriveUi.font(context, "medium")
    }
    private var points: List<SpeedPoint> = emptyList()

    fun set(p: List<SpeedPoint>) { points = p; invalidate() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), DriveUi.dp(context, 170f))
    }

    override fun onDraw(canvas: Canvas) {
        val left = DriveUi.dp(context, 30f).toFloat()
        val top = DriveUi.dp(context, 6f).toFloat()
        val bottom = height - DriveUi.dp(context, 18f).toFloat()
        val right = width.toFloat()
        val maxKmh = ((points.maxOfOrNull { it.kmh } ?: 0f).coerceAtLeast(40f) / 20f).let { kotlin.math.ceil(it) * 20f }
        var v = 0f
        while (v <= maxKmh) {
            val y = bottom - (bottom - top) * v / maxKmh
            canvas.drawLine(left, y, right, y, grid)
            canvas.drawText(v.toInt().toString(), 0f, y + label.textSize / 3, label)
            v += if (maxKmh > 100) 40f else 20f
        }
        if (points.size < 2) {
            canvas.drawText("Not enough speed data for a graph", left + 8, (top + bottom) / 2, label)
            return
        }
        val t0 = points.first().at
        val span = (points.last().at - t0).coerceAtLeast(1L).toFloat()
        val path = Path()
        points.forEachIndexed { i, p ->
            val x = left + (right - left) * (p.at - t0) / span
            val y = bottom - (bottom - top) * (p.kmh / maxKmh).coerceIn(0f, 1f)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        val area = Path(path).apply { lineTo(right, bottom); lineTo(left, bottom); close() }
        fill.shader = LinearGradient(0f, top, 0f, bottom, Color.argb(110, 91, 209, 193), Color.argb(0, 91, 209, 193), Shader.TileMode.CLAMP)
        canvas.drawPath(area, fill)
        canvas.drawPath(path, line)
        canvas.drawText(DriveUi.time(context, points.first().at), left, height.toFloat() - 2, label)
        val endText = DriveUi.time(context, points.last().at)
        canvas.drawText(endText, right - label.measureText(endText), height.toFloat() - 2, label)
    }
}
