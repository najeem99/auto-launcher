package com.najib.carwelcome

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("car_welcome", Context.MODE_PRIVATE)

    var greetingText: String
        get() = sp.getString("greeting", DEFAULT_GREETING) ?: DEFAULT_GREETING
        set(v) = sp.edit().putString("greeting", v).apply()

    var greetOnConnect: Boolean
        get() = sp.getBoolean("greet_on_connect", true)
        set(v) = sp.edit().putBoolean("greet_on_connect", v).apply()

    var autoPlayAfterGreeting: Boolean
        get() = sp.getBoolean("autoplay", true)
        set(v) = sp.edit().putBoolean("autoplay", v).apply()

    var reciterId: String
        get() = sp.getString("reciter", RECITERS[0].id) ?: RECITERS[0].id
        set(v) = sp.edit().putString("reciter", v).apply()

    var lastSurah: Int
        get() = sp.getInt("last_surah", 1).coerceIn(1, 114)
        set(v) = sp.edit().putInt("last_surah", v).apply()

    /** Surah to start when a drive begins; 0 means continue where playback left off. */
    var journeySurah: Int
        get() = sp.getInt("journey_surah", 0).coerceIn(0, 114)
        set(v) = sp.edit().putInt("journey_surah", v).apply()

    /** The surah a new drive should start from. */
    val driveStartSurah: Int
        get() = journeySurah.takeIf { it in 1..114 } ?: lastSurah

    var lastGreetAt: Long
        get() = sp.getLong("last_greet_at", 0L)
        set(v) = sp.edit().putLong("last_greet_at", v).apply()

    companion object {
        const val DEFAULT_GREETING = "Welcome Najib"
    }
}
