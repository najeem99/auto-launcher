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

    var lastWelcomeAt: Long
        get() = sp.getLong("last_welcome_at", 0L)
        set(v) = sp.edit().putLong("last_welcome_at", v).apply()

    companion object {
        const val DEFAULT_NAME = "Najeem"
    }
}
