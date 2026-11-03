package com.najib.carwelcome.car

import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.connection.CarConnection
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import com.najib.carwelcome.Weather
import com.najib.carwelcome.Welcome

/** Entry point Android Auto binds to when you open the dashboard on the car screen. */
class DashboardService : CarAppService() {

    override fun createHostValidator(): HostValidator =
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }

    override fun onCreateSession(): Session = DashboardSession()
}

class DashboardSession : Session() {

    init {
        // The dua only plays while connected: stop it the moment the phone leaves the car.
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                Welcome.carConnected = true
                CarConnection(carContext).type.observe(owner) { type ->
                    if (type == CarConnection.CONNECTION_TYPE_NOT_CONNECTED) {
                        Welcome.carConnected = false
                        Welcome.stopAll()
                    }
                }
            }

            override fun onDestroy(owner: LifecycleOwner) {
                Welcome.carConnected = false
                Welcome.stopAll()
            }
        })
    }

    override fun onCreateScreen(intent: Intent): Screen {
        // Fetch weather first so the greeting can mention it, then welcome.
        Weather.refresh(carContext)
        Welcome.startDrive()
        Welcome.runIfDue(carContext)
        return DashboardScreen(carContext)
    }
}
