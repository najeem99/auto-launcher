package com.najib.carwelcome

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread
import kotlin.math.roundToInt

data class WeatherNow(
    val tempC: Int,
    val code: Int,
    val fetchedAt: Long,
    /** False at night: the icon shows a moon instead of the sun. */
    val isDay: Boolean = true,
    /** Where the reading is for, e.g. "Dubai". */
    val place: String = "",
) {
    /** WMO weather codes as used by Open-Meteo. */
    val description: String get() = when (code) {
        0 -> "Clear"
        1 -> "Mostly clear"
        2 -> "Partly cloudy"
        3 -> "Cloudy"
        45, 48 -> "Foggy"
        in 51..57 -> "Drizzle"
        in 61..67, in 80..82 -> "Rain"
        in 71..77, 85, 86 -> "Snow"
        in 95..99 -> "Thunderstorm"
        else -> "—"
    }
    val kind: Kind get() = when (code) {
        0, 1 -> if (isDay) Kind.SUN else Kind.MOON
        2 -> if (isDay) Kind.SUN_CLOUD else Kind.MOON_CLOUD
        3, 45, 48 -> Kind.CLOUD
        in 95..99 -> Kind.STORM
        in 51..67, in 80..82 -> Kind.RAIN
        else -> Kind.CLOUD
    }
    enum class Kind { SUN, SUN_CLOUD, MOON, MOON_CLOUD, CLOUD, RAIN, STORM }
}

/**
 * Outside temperature for where the phone is (Open-Meteo, no API key). When the
 * location can't be found, it shows Dubai.
 */
object Weather {
    private const val REFRESH_MS = 15 * 60 * 1000L
    private const val DUBAI_LAT = 25.2048
    private const val DUBAI_LON = 55.2708
    @Volatile var latest: WeatherNow? = null
        private set
    @Volatile private var loading = false

    fun refresh(context: Context, onUpdate: () -> Unit = {}) {
        val now = System.currentTimeMillis()
        val last = latest
        if (loading || (last != null && now - last.fetchedAt < REFRESH_MS)) return
        val app = context.applicationContext
        loading = true
        thread(name = "weather") {
            try {
                val loc = lastLocation(app)
                val lat = loc?.latitude ?: DUBAI_LAT
                val lon = loc?.longitude ?: DUBAI_LON
                val place = if (loc == null) "Dubai" else placeName(app, lat, lon) ?: "Your location"
                val url = URL(
                    ("https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f" +
                        "&current=temperature_2m,weather_code,is_day&timezone=auto")
                        .format(java.util.Locale.US, lat, lon)
                )
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val current = JSONObject(body).getJSONObject("current")
                latest = WeatherNow(
                    tempC = current.getDouble("temperature_2m").roundToInt(),
                    code = current.getInt("weather_code"),
                    fetchedAt = System.currentTimeMillis(),
                    isDay = current.optInt("is_day", 1) == 1,
                    place = place,
                )
                onUpdate()
            } catch (_: Exception) {
                // No network or bad response: keep the last reading.
            } finally {
                loading = false
            }
        }
    }

    /** City name for the card, e.g. "Dubai". Runs on the weather thread. */
    private fun placeName(context: Context, lat: Double, lon: Double): String? = runCatching {
        if (!Geocoder.isPresent()) return null
        @Suppress("DEPRECATION")
        val a = Geocoder(context, java.util.Locale.getDefault()).getFromLocation(lat, lon, 1)?.firstOrNull()
        a?.locality ?: a?.subAdminArea ?: a?.adminArea
    }.getOrNull()

    /** Last known location, if it's from the last 6 hours (an old fix may be another city). */
    @SuppressLint("MissingPermission")
    private fun lastLocation(context: Context): Location? {
        val granted = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return lm.getProviders(true)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
            ?.takeIf { System.currentTimeMillis() - it.time < 6 * 60 * 60 * 1000L }
    }
}
