package com.najib.carwelcome.car

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
 * Decides whether the car is parked, for the YouTube player.
 *
 * Uses the car's own speed when the head unit shares it with Android Auto, and the
 * phone's GPS speed as well. "Parked" means every fresh reading has been under
 * walking pace for 3 seconds. With no readings at all it is never treated as parked.
 */
class ParkedDetector(private val carContext: CarContext) {

    enum class State { PARKED, MOVING, CHECKING }

    @Volatile private var carSpeed = 0f
    @Volatile private var carAt = 0L
    @Volatile private var gpsSpeed = 0f
    @Volatile private var gpsAt = 0L
    private var movingSince = 0L
    private var running = false

    private val speedListener = OnCarDataAvailableListener<Speed> { speed ->
        val v = speed.rawSpeedMetersPerSecond
        if (v.status == CarValue.STATUS_SUCCESS) {
            v.value?.let { carSpeed = abs(it); carAt = SystemClock.elapsedRealtime() }
        }
    }

    private val gpsListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (location.hasSpeed()) {
                gpsSpeed = location.speed
                gpsAt = SystemClock.elapsedRealtime()
            }
        }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        running = true
        movingSince = 0L
        runCatching {
            carContext.getCarService(CarHardwareManager::class.java).carInfo
                .addSpeedListener(ContextCompat.getMainExecutor(carContext), speedListener)
        }
        if (carContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            runCatching {
                val lm = carContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, gpsListener, Looper.getMainLooper())
            }
        }
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching {
            carContext.getCarService(CarHardwareManager::class.java).carInfo.removeSpeedListener(speedListener)
        }
        runCatching {
            (carContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(gpsListener)
        }
    }

    /**
     * Set by the "I'm parked" button. Then only clear driving (over 18 km/h) pauses
     * the video; slow or missing readings never do.
     */
    @Volatile var forcedParked = false

    /** A fresh speed reading says the car is standing still (for the P on the dashboard). */
    fun isStopped(): Boolean {
        val now = SystemClock.elapsedRealtime()
        val readings = buildList {
            if (now - carAt < FRESH_MS) add(carSpeed)
            if (now - gpsAt < FRESH_MS) add(gpsSpeed)
        }
        return readings.isNotEmpty() && readings.all { it < 0.5f }
    }

    /**
     * Lenient: the car counts as parked unless a fresh speed reading has shown it
     * moving for 2 seconds. With no readings at all (many head units share none)
     * the video is allowed.
     */
    fun state(): State {
        val now = SystemClock.elapsedRealtime()
        val readings = buildList {
            if (now - carAt < FRESH_MS) add(carSpeed)
            if (now - gpsAt < FRESH_MS) add(gpsSpeed)
        }
        val limit = if (forcedParked) FORCED_MOVING_MPS else MOVING_MPS
        if (readings.none { it > limit }) { movingSince = 0L; return State.PARKED }
        if (movingSince == 0L) movingSince = now
        return if (now - movingSince >= SETTLE_MS) State.MOVING else State.PARKED
    }

    companion object {
        /**
         * The Parked switch on the Utilities screen. While on, YouTube opens unlocked and
         * only pauses above 18 km/h. Cleared on every new drive and as soon as the car
         * drives faster than 25 km/h.
         */
        @Volatile var manualParked = false

        private const val FRESH_MS = 4_000L
        private const val SETTLE_MS = 2_000L
        /** About 10 km/h: clearly driving, well above GPS jitter while standing. */
        private const val MOVING_MPS = 2.8f
        /** About 18 km/h, used after "I'm parked" is pressed. */
        private const val FORCED_MOVING_MPS = 5.0f
    }
}
