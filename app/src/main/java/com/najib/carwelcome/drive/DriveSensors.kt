package com.najib.carwelcome.drive

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import androidx.car.app.CarContext
import androidx.car.app.hardware.CarHardwareManager
import androidx.car.app.hardware.common.CarValue
import androidx.car.app.hardware.common.OnCarDataAvailableListener
import androidx.car.app.hardware.info.Speed
import androidx.core.content.ContextCompat
import kotlin.math.abs

/**
 * One shared source of speed and position for the whole app (trip log, speedometer).
 *
 * GPS runs while anyone holds it ([acquire] / [release]). The car's own speed is added
 * while the dashboard is open on the car ([attachCar] / [detachCar]), because Android
 * Auto only shares it with a running car screen. Everything runs on the main thread.
 */
object DriveSensors {

    /** A reading; [fromCar] is true when it came from the car rather than GPS. */
    data class Sample(val speedMps: Float, val fromCar: Boolean)

    private const val FRESH_MS = 5_000L

    @Volatile private var carSpeed = 0f
    @Volatile private var carAt = 0L
    @Volatile private var gpsSpeed = 0f
    @Volatile private var gpsAt = 0L
    @Volatile var lastFix: Location? = null
        private set

    private var holders = 0
    private var lm: LocationManager? = null
    private var car: CarContext? = null
    private val fixListeners = mutableListOf<(Location) -> Unit>()
    private var previousFix: Location? = null

    private val speedListener = OnCarDataAvailableListener<Speed> { speed ->
        val v = speed.rawSpeedMetersPerSecond
        if (v.status == CarValue.STATUS_SUCCESS) {
            v.value?.let { carSpeed = abs(it); carAt = SystemClock.elapsedRealtime() }
        }
    }

    private val gpsListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val prev = previousFix
            val speed = when {
                location.hasSpeed() -> location.speed
                // Some phones leave speed out: work it out from the last fix.
                prev != null -> {
                    val dt = (location.elapsedRealtimeNanos - prev.elapsedRealtimeNanos) / 1e9f
                    if (dt in 0.5f..10f) prev.distanceTo(location) / dt else null
                }
                else -> null
            }
            previousFix = location
            lastFix = location
            if (speed != null && location.accuracy <= 50f) {
                gpsSpeed = speed
                gpsAt = SystemClock.elapsedRealtime()
            }
            fixListeners.toList().forEach { runCatching { it(location) } }
        }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    fun hasLocationPermission(context: Context) =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Starts GPS (once a second) for one more user. Returns false without location permission. */
    @SuppressLint("MissingPermission")
    fun acquire(context: Context): Boolean {
        holders++
        if (lm != null) return true
        if (!hasLocationPermission(context)) return false
        val manager = context.applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val ok = runCatching {
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, gpsListener, Looper.getMainLooper())
        }.isSuccess
        if (ok) lm = manager
        return ok
    }

    fun release() {
        if (holders > 0) holders--
        if (holders == 0) {
            lm?.let { runCatching { it.removeUpdates(gpsListener) } }
            lm = null
            previousFix = null
        }
    }

    /** Adds the car's own speedometer (when the head unit shares it). */
    fun attachCar(carContext: CarContext) {
        if (car === carContext) return
        detachCar()
        car = carContext
        runCatching {
            carContext.getCarService(CarHardwareManager::class.java).carInfo
                .addSpeedListener(ContextCompat.getMainExecutor(carContext), speedListener)
        }
    }

    fun detachCar(carContext: CarContext? = null) {
        val c = car ?: return
        if (carContext != null && carContext !== c) return
        runCatching { c.getCarService(CarHardwareManager::class.java).carInfo.removeSpeedListener(speedListener) }
        car = null
    }

    /** Current speed in m/s, car speed first, or null if no fresh reading. */
    fun speedMps(): Float? = sample()?.speedMps

    fun sample(): Sample? {
        val now = SystemClock.elapsedRealtime()
        if (carAt != 0L && now - carAt < FRESH_MS) return Sample(carSpeed, true)
        if (gpsAt != 0L && now - gpsAt < FRESH_MS) return Sample(gpsSpeed, false)
        return null
    }

    fun addFixListener(l: (Location) -> Unit) { if (l !in fixListeners) fixListeners += l }
    fun removeFixListener(l: (Location) -> Unit) { fixListeners -= l }
}
