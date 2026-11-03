package com.najib.carwelcome

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread
import kotlin.math.roundToInt

data class WeatherNow(val tempC: Int, val code: Int, val fetchedAt: Long) {
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
        0, 1 -> Kind.SUN
        2 -> Kind.SUN_CLOUD
        3, 45, 48 -> Kind.CLOUD
        in 95..99 -> Kind.STORM
        in 51..67, in 80..82 -> Kind.RAIN
        else -> Kind.CLOUD
    }
    enum class Kind { SUN, SUN_CLOUD, CLOUD, RAIN, STORM }
}

/** Outside temperature from the phone's last known location (Open-Meteo, no API key). */
object Weather {
    private const val REFRESH_MS = 15 * 60 * 1000L
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
                val loc = lastLocation(app) ?: return@thread
                val url = URL(
                    "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f&current=temperature_2m,weather_code"
                        .format(java.util.Locale.US, loc.latitude, loc.longitude)
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
                )
                onUpdate()
            } catch (_: Exception) {
                // No network or bad response: keep the last reading.
            } finally {
                loading = false
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun lastLocation(context: Context): Location? {
        val granted = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return lm.getProviders(true)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
    }
}
