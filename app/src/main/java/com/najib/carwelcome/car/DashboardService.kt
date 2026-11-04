package com.najib.carwelcome.car

import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.connection.CarConnection
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import com.najib.carwelcome.CrashLog
import com.najib.carwelcome.Weather
import com.najib.carwelcome.Welcome
import com.najib.carwelcome.drive.TripRecorder

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
        // Every step is guarded: one failing feature (GPS, the trip log, the car's speed)
        // must not stop the dashboard from opening.
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                current = this@DashboardSession
                Welcome.carConnected = true
                ParkedDetector.manualParked = false
                CrashLog.guard("start speed") { DriveSpeed.start(carContext) }
                // Android Auto is connected: start (or continue) the trip log.
                CrashLog.guard("start trip log") { TripRecorder.onCarScreenOpened(carContext) }
                CrashLog.guard("watch connection") {
                    CarConnection(carContext).type.observe(owner) { type ->
                        if (type == CarConnection.CONNECTION_TYPE_NOT_CONNECTED && current === this@DashboardSession) {
                            Welcome.carConnected = false
                            CrashLog.guard("stop on disconnect") { Welcome.stopAll() }
                            DriveSpeed.stop(carContext)
                        }
                    }
                }
            }

            override fun onDestroy(owner: LifecycleOwner) {
                CrashLog.guard("close trip log") { TripRecorder.onCarScreenClosed(carContext) }
                DriveSpeed.stop(carContext)
                // After a quick replug the new session can start before this one ends;
                // then the new one owns the shared state and this one leaves it alone.
                if (current === this@DashboardSession) {
                    current = null
                    Welcome.carConnected = false
                    CrashLog.guard("stop audio") { Welcome.stopAll() }
                }
            }
        })
    }

    /** Android Auto sent a navigation request to this app; pass it on to Google Maps. */
    override fun onNewIntent(intent: Intent) {
        CrashLog.guard("navigate request") {
            if (intent.action == CarContext.ACTION_NAVIGATE) PlacesScreen.onNavigateBounced(carContext, intent)
        }
    }

    override fun onCreateScreen(intent: Intent): Screen {
        // Fetch weather first so the greeting can mention it, then welcome.
        CrashLog.guard("weather") { Weather.refresh(carContext) }
        CrashLog.guard("welcome") {
            Welcome.startDrive()
            Welcome.runIfDue(carContext)
        }
        return DashboardScreen(carContext)
    }

    companion object {
        /** The newest car session; an older one closing late must not undo its setup. */
        private var current: DashboardSession? = null
    }
}
