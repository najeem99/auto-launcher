package com.najib.carwelcome

import android.app.Application

/** The app process: starts the crash log before anything else runs. */
class DriveApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }
}
