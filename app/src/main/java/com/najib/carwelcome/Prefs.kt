package com.najib.carwelcome

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("car_welcome", Context.MODE_PRIVATE)

    var name: String
        get() = sp.getString("name", DEFAULT_NAME)?.ifBlank { DEFAULT_NAME } ?: DEFAULT_NAME
        set(v) = sp.edit().putString("name", v).apply()

    var welcomeOnOpen: Boolean
        get() = sp.getBoolean("welcome_on_open", true)
        set(v) = sp.edit().putBoolean("welcome_on_open", v).apply()

    var reciteDua: Boolean
        get() = sp.getBoolean("recite_dua", true)
        set(v) = sp.edit().putBoolean("recite_dua", v).apply()

    var use24Hour: Boolean
        get() = sp.getBoolean("use_24h", false)
        set(v) = sp.edit().putBoolean("use_24h", v).apply()

    /** Start time (ms) of each Dua al-Safar line in your recording, from "Sync lyrics". */
    var duaTimings: List<Int>?
        get() = sp.getString("dua_timings", null)?.split(',')?.mapNotNull { it.toIntOrNull() }
        set(v) = sp.edit().putString("dua_timings", v?.joinToString(",")).apply()

    var lastWelcomeAt: Long
        get() = sp.getLong("last_welcome_at", 0L)
        set(v) = sp.edit().putLong("last_welcome_at", v).apply()

    /** Work clock-in: the site, and the labels of the buttons to look for. */
    var checkInUrl: String
        get() = sp.getString("checkin_url", DEFAULT_CHECKIN_URL).orEmpty().trim()
        set(v) = sp.edit().putString("checkin_url", v.trim()).apply()

    var checkInButton: String
        get() = sp.getString("checkin_button", null)?.ifBlank { null } ?: "Clock in"
        set(v) = sp.edit().putString("checkin_button", v.trim()).apply()

    /** The button that appears once you're clocked in. Never pressed, only looked for. */
    var checkInDoneButton: String
        get() = sp.getString("checkin_done", null)?.ifBlank { null } ?: "Clockout"
        set(v) = sp.edit().putString("checkin_done", v.trim()).apply()

    var checkInSignInButton: String
        get() = sp.getString("checkin_signin", null)?.ifBlank { null } ?: "Sign in with Darwinbox Credentials"
        set(v) = sp.edit().putString("checkin_signin", v.trim()).apply()

    var autoCheckIn: Boolean
        get() = sp.getBoolean("checkin_auto", false)
        set(v) = sp.edit().putBoolean("checkin_auto", v).apply()

    var lastAutoCheckInDay: String
        get() = sp.getString("checkin_auto_day", "").orEmpty()
        set(v) = sp.edit().putString("checkin_auto_day", v).apply()

    var lastCheckInResult: String
        get() = sp.getString("checkin_last", "").orEmpty()
        set(v) = sp.edit().putString("checkin_last", v).apply()

    companion object {
        const val DEFAULT_NAME = "Najeem"
        const val DEFAULT_CHECKIN_URL = "https://alfardan.darwinbox.com/"
    }
}
