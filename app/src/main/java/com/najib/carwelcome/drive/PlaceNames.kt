package com.najib.carwelcome.drive

import android.content.Context
import android.location.Geocoder
import java.util.Locale

/**
 * Street and area names for spots, road squares and places.
 *
 * Names come from the phone's own address lookup (Android's Geocoder) once per spot
 * and are kept in the trip database, so each spot is asked about only once. The trip
 * positions themselves stay on the phone. Places can also be given a name of your own
 * ("Home", "Gym"); those win over everything else.
 */
class PlaceNames(context: Context) {
    private val app = context.applicationContext
    private val db = TripDb.get(app)
    private val custom = app.getSharedPreferences("place_names", Context.MODE_PRIVATE)
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<String?, String?>>(runCatching { db.allCachedNames() }.getOrDefault(emptyMap()))

    val canLookUp: Boolean get() = Geocoder.isPresent()

    // ---- Your own names ------------------------------------------------------------------

    private data class Named(val lat: Double, val lon: Double, val name: String)

    private fun customList(): List<Named> = custom.getString("list", "").orEmpty().lines().mapNotNull { line ->
        val parts = line.split('|', limit = 3)
        if (parts.size < 3) return@mapNotNull null
        val la = parts[0].toDoubleOrNull() ?: return@mapNotNull null
        val lo = parts[1].toDoubleOrNull() ?: return@mapNotNull null
        Named(la, lo, parts[2])
    }

    /** Your name for the place at this position, if you gave it one. */
    fun customName(lat: Double, lon: Double): String? =
        customList().filter { PlaceStats.distM(it.lat, it.lon, lat, lon) <= PlaceStats.PLACE_RADIUS_M }
            .minByOrNull { PlaceStats.distM(it.lat, it.lon, lat, lon) }?.name

    /** Names the place at this position; a blank name removes yours. */
    fun setCustomName(lat: Double, lon: Double, name: String) {
        val kept = customList().filter { PlaceStats.distM(it.lat, it.lon, lat, lon) > PlaceStats.PLACE_RADIUS_M }
        val clean = name.replace('|', ' ').replace('\n', ' ').trim()
        val list = if (clean.isEmpty()) kept else kept + Named(lat, lon, clean)
        custom.edit().putString("list", list.joinToString("\n") { "${it.lat}|${it.lon}|${it.name}" }).apply()
    }

    // ---- Looked-up names ---------------------------------------------------------------------

    /** Key for a spot or place: its position rounded to about 100 m. */
    fun spotKey(lat: Double, lon: Double) = "p:" + Math.round(lat * 1000) + ":" + Math.round(lon * 1000)

    /** (road, area) if this key has been looked up, else null. */
    fun cached(key: String): Pair<String?, String?>? = cache[key]

    fun isKnown(key: String) = cache.containsKey(key)

    /**
     * Looks the position up and keeps the answer. Runs on a background thread only.
     * Returns false if the lookup failed (no internet), so it can be tried again later.
     */
    @Suppress("DEPRECATION")
    fun lookUp(key: String, lat: Double, lon: Double): Boolean {
        if (cache.containsKey(key)) return true
        if (!canLookUp) return false
        val list = runCatching { Geocoder(app, Locale.getDefault()).getFromLocation(lat, lon, 1) }.getOrElse { return false }
        val a = list?.firstOrNull()
        val road = a?.thoroughfare?.takeIf { it.isNotBlank() && !it.all { ch -> ch.isDigit() } }
        val area = (a?.subLocality ?: a?.locality)?.takeIf { it.isNotBlank() }
        cache[key] = road to area
        runCatching { db.saveName(key, road, area) }
        return true
    }

    /** A short label for a stop spot: "Sheikh Zayed Rd · Al Barsha". */
    fun spotLabel(lat: Double, lon: Double): String {
        val n = cached(spotKey(lat, lon))
        return listOfNotNull(n?.first, n?.second).joinToString(" · ").ifEmpty { coords(lat, lon) }
    }

    /** A label for a place: your name, else Home/Office from the guess, else the street. */
    fun placeLabel(p: PlaceStats.Place): String =
        customName(p.lat, p.lon) ?: p.guess ?: spotLabel(p.lat, p.lon)

    /** The road a square belongs to, if known. */
    fun roadOf(c: PlaceStats.Cell): String? = cached(c.key)?.first

    companion object {
        fun coords(lat: Double, lon: Double) = String.format(Locale.ROOT, "%.4f, %.4f", lat, lon)
    }
}
