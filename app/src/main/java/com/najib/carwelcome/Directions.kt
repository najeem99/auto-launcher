package com.najib.carwelcome

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build

/**
 * Google Maps directions ("Home", "Work", "mosque", …).
 *
 * Android doesn't let an app open another app from the background, and the car
 * screen counts as background, so a tap in the car can't open Maps by itself.
 * Two ways that do work:
 * - [startNow] from the phone app's own buttons (the app is on screen).
 * - [notify] from the car: a notification on the phone; one tap on it starts
 *   navigation, and Android Auto shows Maps' turn-by-turn on the car screen.
 */
object Directions {
    const val MAPS_PACKAGE = "com.google.android.apps.maps"
    private const val CHANNEL = "directions"
    private const val NOTIFICATION_ID = 4201

    fun mapsIntent(query: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(query)))
            .setPackage(MAPS_PACKAGE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun hasMaps(context: Context) = mapsIntent("Home").resolveActivity(context.packageManager) != null

    /** Starts navigation right away. Works from the phone app, or from the car with "Display over other apps". */
    fun startNow(context: Context, query: String): Boolean {
        if (!hasMaps(context)) return false
        return runCatching { context.startActivity(mapsIntent(query)) }.isSuccess.also {
            if (it) context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        }
    }

    fun canNotify(context: Context): Boolean {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return false
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return nm.areNotificationsEnabled()
    }

    /** A heads-up notification on the phone: tap it to start navigation to [query]. */
    fun notify(context: Context, title: String, query: String): Boolean {
        if (!canNotify(context) || !hasMaps(context)) return false
        val nm = context.getSystemService(NotificationManager::class.java) ?: return false
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Directions from the car", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Tap to start Google Maps navigation picked in the car"
            }
        )
        val pi = PendingIntent.getActivity(
            context, query.hashCode(), mapsIntent(query),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_map)
            .setContentTitle("Navigate to $title")
            .setContentText("Tap to start Google Maps. It will show in Android Auto.")
            .setCategory(Notification.CATEGORY_NAVIGATION)
            .setContentIntent(pi)
            .addAction(Notification.Action.Builder(null, "Start", pi).build())
            .setAutoCancel(true)
            .setTimeoutAfter(10 * 60 * 1000L)
            .build()
        nm.notify(NOTIFICATION_ID, n)
        return true
    }
}
