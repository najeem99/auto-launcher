package com.najib.carwelcome

import android.content.Context
import java.util.Calendar
import java.util.TimeZone

/** The next of the five daily prayers, for the tile. */
data class NextPrayer(
    /** "Fajr", "Dhuhr", "Asr", "Maghrib" or "Isha". */
    val name: String,
    val arabic: String,
    /** Epoch ms of the prayer time. */
    val at: Long,
    /** Where the times are for, e.g. "Dubai". */
    val place: String,
) {
    /** "in 48 min", "in 2 h 5 min", or "now" for the first minute. */
    fun countdown(now: Long = System.currentTimeMillis()): String {
        val mins = ((at - now + 59_999) / 60_000).toInt() // round up, so it never shows "in 0 min"
        return when {
            mins <= 0 -> "now"
            mins < 60 -> "in $mins min"
            mins % 60 == 0 -> "in ${mins / 60} h"
            else -> "in ${mins / 60} h ${mins % 60} min"
        }
    }
}

/**
 * Prayer times worked out on the phone (no internet) with the UAE method, for
 * where the phone is. Without a location it uses the last place it knew from the
 * past three days, and otherwise Dubai.
 */
object Prayer {
    private const val DUBAI_LAT = 25.2048
    private const val DUBAI_LON = 55.2708
    private const val SAVED_FOR_MS = 3 * 24 * 60 * 60 * 1000L

    private val ARABIC = mapOf(
        "Fajr" to "الفجر", "Dhuhr" to "الظهر", "Asr" to "العصر",
        "Maghrib" to "المغرب", "Isha" to "العشاء",
    )
    /** The five prayers, by index in [PrayerTimes.NAMES] (Sunrise isn't a prayer). */
    private val FIVE = intArrayOf(
        PrayerTimes.FAJR, PrayerTimes.DHUHR, PrayerTimes.ASR, PrayerTimes.MAGHRIB, PrayerTimes.ISHA,
    )

    private data class Spot(val lat: Double, val lon: Double, val place: String)

    // Cached so the tile can ask every second without recalculating.
    private var cacheKey = ""
    private var cached: List<Pair<String, Long>> = emptyList()
    @Volatile private var spot: Spot? = null
    @Volatile private var spotAt = 0L

    /** The next prayer from now. Cheap: safe to call on every frame. */
    fun next(context: Context, now: Long = System.currentTimeMillis()): NextPrayer {
        val s = location(context, now)
        val times = upcoming(s, now)
        val (name, at) = times.first { it.second > now - 60_000 } // keep "now" for a minute
        return NextPrayer(name, ARABIC[name] ?: "", at, s.place)
    }

    /** Today's six times (with Sunrise) and the place, for the phone app's list. */
    fun today(context: Context, now: Long = System.currentTimeMillis()): Pair<String, List<Pair<String, Long>>> {
        val s = location(context, now)
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val t = PrayerTimes.forDay(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH), s.lat, s.lon)
        return s.place to PrayerTimes.NAMES.indices.map { PrayerTimes.NAMES[it] to t[it] }
    }

    /** Today's and tomorrow's five prayers, in local time, cached per day and place. */
    @Synchronized
    private fun upcoming(s: Spot, now: Long): List<Pair<String, Long>> {
        val cal = Calendar.getInstance(TimeZone.getDefault()).apply { timeInMillis = now }
        val key = "%d-%d-%d|%.2f|%.2f|%s".format(
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH),
            s.lat, s.lon, TimeZone.getDefault().id,
        )
        if (key == cacheKey) return cached
        val list = ArrayList<Pair<String, Long>>(10)
        repeat(2) {
            val t = PrayerTimes.forDay(
                cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH), s.lat, s.lon,
            )
            for (i in FIVE) list += PrayerTimes.NAMES[i] to t[i]
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }
        cacheKey = key
        cached = list
        return list
    }

    /** Where to calculate for. Checks the phone's location at most every 10 minutes. */
    private fun location(context: Context, now: Long): Spot {
        spot?.let { if (now - spotAt < 10 * 60 * 1000L) return it }
        val sp = context.applicationContext.getSharedPreferences("prayer", Context.MODE_PRIVATE)
        val found = runCatching { Weather.lastLocation(context.applicationContext) }.getOrNull()
        val s = if (found != null) {
            val near = isNearDubai(found.latitude, found.longitude)
            val label = Weather.latest?.place.orEmpty()
            val place = when {
                label.isNotBlank() && label != "Your location" && (label != "Dubai" || near) -> label
                near -> "Dubai"
                else -> "Your location"
            }
            sp.edit()
                .putFloat("lat", found.latitude.toFloat()).putFloat("lon", found.longitude.toFloat())
                .putString("place", place).putLong("at", now).apply()
            Spot(found.latitude, found.longitude, place)
        } else if (sp.contains("lat") && now - sp.getLong("at", 0L) < SAVED_FOR_MS) {
            Spot(sp.getFloat("lat", 0f).toDouble(), sp.getFloat("lon", 0f).toDouble(), sp.getString("place", null) ?: "Your location")
        } else {
            Spot(DUBAI_LAT, DUBAI_LON, "Dubai")
        }
        spot = s
        spotAt = now
        return s
    }

    /** The weather card says "Dubai" when it had no location, so only trust that label near Dubai. */
    private fun isNearDubai(lat: Double, lon: Double) =
        kotlin.math.abs(lat - DUBAI_LAT) < 0.4 && kotlin.math.abs(lon - DUBAI_LON) < 0.5
}
