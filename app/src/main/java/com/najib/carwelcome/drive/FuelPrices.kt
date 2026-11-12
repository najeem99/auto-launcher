package com.najib.carwelcome.drive

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** UAE fuel grades, as the Fuel Price Committee names them. */
enum class Grade(val key: String, val title: String) {
    SUPER98("super98", "Super 98"),
    SPECIAL95("special95", "Special 95"),
    EPLUS91("eplus91", "E-Plus 91"),
    DIESEL("diesel", "Diesel");

    companion object {
        fun of(key: String?) = values().firstOrNull { it.key == key } ?: SPECIAL95
    }
}

/** One month's prices (AED per litre). */
data class MonthPrices(val month: String, val prices: Map<Grade, Float>, val source: String, val edited: Boolean)

/**
 * UAE petrol prices, which change on the 1st of every month.
 *
 * A GitHub Action in the app's repo (tools/fuel_prices.py) reads the monthly
 * announcement and keeps fuel-prices.json up to date; the app downloads that file.
 * If GitHub can't be reached, the app reads GlobalPetrolPrices.com itself. Any
 * month can be typed in by hand, and a hand-typed price always wins.
 *
 * Trips and fill-ups are priced with the month they happened in.
 */
object FuelPrices {
    private const val REPO_URL = "https://raw.githubusercontent.com/najeem99/auto-launcher/main/fuel-prices.json"
    private const val GPP_URL = "https://www.globalpetrolprices.com/United-Arab-Emirates/%s_prices/"
    /** The committee keeps 98 and 91 a fixed step from 95. */
    private const val GAP_98 = 0.12f
    private const val GAP_91 = -0.07f

    /** Prices known when this version was built, so the app works offline from day one. */
    private val BUILT_IN = mapOf(
        "2026-10" to floatArrayOf(4.40f, 4.28f, 4.21f, 4.80f),
        "2026-11" to floatArrayOf(4.16f, 4.04f, 3.97f, 4.75f),
    )

    private val io = Executors.newSingleThreadExecutor()
    @Volatile private var cache: Map<String, MonthPrices>? = null

    private fun sp(context: Context) = context.applicationContext.getSharedPreferences("fuel_prices", Context.MODE_PRIVATE)

    fun monthKey(at: Long): String = SimpleDateFormat("yyyy-MM", Locale.US).format(Date(at))
    fun monthName(key: String): String = runCatching {
        SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(SimpleDateFormat("yyyy-MM", Locale.US).parse(key)!!)
    }.getOrDefault(key)

    /** The grade you put in the car. */
    fun grade(context: Context): Grade = Grade.of(sp(context).getString("grade", null))
    fun setGrade(context: Context, g: Grade) { sp(context).edit().putString("grade", g.key).apply() }

    fun lastFetchAt(context: Context): Long = sp(context).getLong("fetched_at", 0L)
    fun lastSource(context: Context): String? = sp(context).getString("fetched_from", null)

    /** Every month we have a price for, newest last. Hand edits are merged in. */
    fun all(context: Context): Map<String, MonthPrices> {
        cache?.let { return it }
        val out = sortedMapOf<String, MonthPrices>()
        BUILT_IN.forEach { (k, v) -> out[k] = MonthPrices(k, Grade.values().mapIndexed { i, g -> g to v[i] }.toMap(), "Built in", false) }
        runCatching { parseMonths(JSONObject(sp(context).getString("fetched", "{}")!!)) }.getOrNull()?.forEach { out[it.month] = it }
        runCatching {
            val o = JSONObject(sp(context).getString("edits", "{}")!!)
            o.keys().forEach { k ->
                val m = o.getJSONObject(k)
                val base = out[k]?.prices ?: emptyMap()
                val p = Grade.values().associateWith { g -> m.optDouble(g.key, Double.NaN).toFloat().takeIf { !it.isNaN() } ?: base[g] ?: 0f }
                out[k] = MonthPrices(k, p, "Typed in", true)
            }
        }
        return out.also { cache = it }
    }

    /** Prices for this month, or the latest month before it. */
    fun forMonth(context: Context, key: String): MonthPrices? {
        val all = all(context)
        return all[key] ?: all.entries.lastOrNull { it.key < key }?.value ?: all.values.firstOrNull()
    }

    /** Price per litre of your grade at this moment, AED. */
    fun price(context: Context, at: Long, grade: Grade = grade(context)): Float =
        forMonth(context, monthKey(at))?.prices?.get(grade)?.takeIf { it > 0f } ?: TripPrefs(context).fuelPrice

    /** True if the price for this month came from the internet (or was typed in), not a guess. */
    fun haveMonth(context: Context, key: String) = all(context).containsKey(key)

    /** Save hand-typed prices for a month, or clear them with null. */
    fun setEdit(context: Context, key: String, prices: Map<Grade, Float>?) {
        val o = runCatching { JSONObject(sp(context).getString("edits", "{}")!!) }.getOrDefault(JSONObject())
        if (prices == null) o.remove(key)
        else o.put(key, JSONObject().apply { prices.forEach { (g, v) -> put(g.key, v.toDouble()) } })
        sp(context).edit().putString("edits", o.toString()).apply()
        cache = null
    }

    fun editsJson(context: Context): String = sp(context).getString("edits", "{}")!!
    fun restoreEdits(context: Context, json: String) {
        val mine = runCatching { JSONObject(sp(context).getString("edits", "{}")!!) }.getOrDefault(JSONObject())
        runCatching { JSONObject(json) }.getOrNull()?.let { theirs -> theirs.keys().forEach { if (!mine.has(it)) mine.put(it, theirs.get(it)) } }
        sp(context).edit().putString("edits", mine.toString()).apply()
        cache = null
    }

    /** Fetch now if this month is missing or the last check was a while ago. */
    fun refreshIfStale(context: Context, done: (Boolean) -> Unit = {}) {
        val now = System.currentTimeMillis()
        val missing = !all(context).containsKey(monthKey(now))
        val age = now - lastFetchAt(context)
        if ((missing && age > 30 * 60_000L) || age > 12 * 60 * 60_000L) refresh(context, done)
    }

    /** Downloads the latest prices. Calls back on a background thread with true if anything was read. */
    fun refresh(context: Context, done: (Boolean) -> Unit = {}) {
        val app = context.applicationContext
        io.execute {
            var ok = false
            var from: String? = null
            runCatching {
                val o = JSONObject(download(REPO_URL))
                if (o.has("months")) {
                    sp(app).edit().putString("fetched", o.getJSONObject("months").toString()).apply()
                    ok = true; from = "app's price list on GitHub"
                }
            }
            cache = null
            val key = monthKey(System.currentTimeMillis())
            if (!all(app).containsKey(key)) runCatching {
                fromGlobalPetrolPrices(key)?.let { m ->
                    val fetched = runCatching { JSONObject(sp(app).getString("fetched", "{}")!!) }.getOrDefault(JSONObject())
                    fetched.put(key, JSONObject().apply {
                        m.prices.forEach { (g, v) -> put(g.key, v.toDouble()) }
                        put("source", m.source)
                    })
                    sp(app).edit().putString("fetched", fetched.toString()).apply()
                    ok = true; from = "GlobalPetrolPrices.com"
                }
            }
            cache = null
            val e = sp(app).edit().putLong("fetched_at", System.currentTimeMillis())
            if (ok) e.putString("fetched_from", from)
            e.apply()
            done(ok)
        }
    }

    private fun parseMonths(months: JSONObject): List<MonthPrices> = months.keys().asSequence().mapNotNull { k ->
        val m = months.optJSONObject(k) ?: return@mapNotNull null
        val p = Grade.values().associateWith { m.optDouble(it.key, 0.0).toFloat() }
        if (p.values.any { it !in 1f..9f }) null else MonthPrices(k, p, m.optString("source", "Online"), false)
    }.toList()

    /** Special 95 and diesel from GlobalPetrolPrices; 98 and 91 follow from 95. */
    private fun fromGlobalPetrolPrices(key: String): MonthPrices? {
        val re = Regex("""The current [\w ]{0,30}? in the United Arab Emirates is AED (\d+\.\d{2}) per liter.{0,80}?(\d\d-\w{3}-\d{4})""")
        fun read(kind: String): Pair<Float, String>? {
            val t = download(GPP_URL.format(kind)).replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ")
            val m = re.find(t) ?: return null
            val at = SimpleDateFormat("dd-MMM-yyyy", Locale.US).parse(m.groupValues[2]) ?: return null
            return m.groupValues[1].toFloat() to monthKey(at.time)
        }
        val (p95, month) = read("gasoline") ?: return null
        val diesel = read("diesel")?.first ?: return null
        if (month != key || p95 !in 1f..9f || diesel !in 1f..9f) return null
        fun r(v: Float) = Math.round(v * 100) / 100f
        return MonthPrices(key, mapOf(
            Grade.SUPER98 to r(p95 + GAP_98), Grade.SPECIAL95 to p95, Grade.EPLUS91 to r(p95 + GAP_91), Grade.DIESEL to diesel,
        ), "GlobalPetrolPrices (98 and 91 worked out from 95)", false)
    }

    private fun download(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 20_000
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36")
        c.setRequestProperty("Cache-Control", "no-cache")
        try {
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    /** First day of the month after `at`, for "new prices on ..." hints. */
    fun nextChange(at: Long): Long = Calendar.getInstance().apply {
        timeInMillis = at; set(Calendar.DAY_OF_MONTH, 1); add(Calendar.MONTH, 1)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
