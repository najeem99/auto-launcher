package com.najib.carwelcome.drive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.najib.carwelcome.HomeActivity
import com.najib.carwelcome.R

/**
 * Keeps the trip log alive while Google Maps (or anything else) is on the car screen:
 * a foreground service of the "location" type, so Android keeps giving the app GPS.
 * The recording itself lives in [TripRecorder]; this only holds the app awake and
 * shows a quiet notification. If Android refuses to start it, the trip is still
 * recorded for as long as the app stays alive.
 */
class TripService : Service() {

    private var lastUpdate = 0L
    private val listener: () -> Unit = {
        val now = SystemClock.elapsedRealtime()
        if (!TripRecorder.isRecording) stopSelf()
        else if (now - lastUpdate > 30_000) {
            lastUpdate = now
            runCatching { getSystemService(NotificationManager::class.java).notify(ID, notification()) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ok = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(ID, notification())
            }
        }.isSuccess
        if (!ok || !TripRecorder.isRecording) {
            stopSelf()
            return START_NOT_STICKY
        }
        lastUpdate = SystemClock.elapsedRealtime()
        TripRecorder.addListener(listener)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        TripRecorder.removeListener(listener)
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Trip log", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "Shown while a drive is being recorded"
                    setShowBadge(false)
                }
            )
        }
        val t = TripRecorder.current
        val text = if (t == null) "Recording your drive" else {
            val min = t.durationMs / 60_000
            "%.1f km · %s".format(t.distanceM / 1000.0, if (min < 60) "$min min" else "${min / 60} h ${min % 60} min")
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL)
        else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("Trip in progress")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        private const val ID = 4107
        private const val CHANNEL = "trip_log"

        fun start(context: Context) {
            // A location service without location permission would be refused (and crash).
            if (!DriveSensors.hasLocationPermission(context)) return
            runCatching { ContextCompat.startForegroundService(context, Intent(context, TripService::class.java)) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, TripService::class.java)) }
        }
    }
}
