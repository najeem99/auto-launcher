package com.najib.carwelcome

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.Calendar

/**
 * The welcome: "Welcome back, Najeem." in a female voice, then the Dua al-Safar
 * recording. The dashboard reads [currentDuaLine] to show the words like lyrics.
 */
object Welcome {
    private const val COOLDOWN_MS = 10 * 60 * 1000L
    private var greeter: Greeter? = null
    private var appContext: Context? = null
    private val main = Handler(Looper.getMainLooper())
    private var runToken = 0

    val isGreeting: Boolean get() = greeter?.isSpeaking == true
    val isReciting: Boolean get() = DuaPlayer.isPlaying
    val isSpeaking: Boolean get() = isGreeting || isReciting

    /** Index into [DuaSafar.LINES] being recited, or -1. */
    val currentDuaLine: Int get() {
        val ctx = appContext ?: return -1
        if (!DuaPlayer.isPlaying) return -1
        return DuaPlayer.lineAt(ctx, DuaPlayer.positionMs, DuaPlayer.durationMs)
    }

    /** Recitation progress 0..1, or -1 when not reciting. */
    val progress: Float get() {
        if (!DuaPlayer.isPlaying) return -1f
        val d = DuaPlayer.durationMs
        return if (d > 0) DuaPlayer.positionMs.toFloat() / d else 0f
    }

    fun attach(context: Context) { appContext = context.applicationContext }

    private fun greeter(context: Context): Greeter {
        appContext = context.applicationContext
        return greeter ?: Greeter(context.applicationContext).also { greeter = it }
    }

    /** On-screen greeting, which follows the time of day. */
    fun greetingLine(name: String, hour: Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)): String {
        val part = when {
            hour < 5 -> "Good night"
            hour < 12 -> "Good morning"
            hour < 17 -> "Good afternoon"
            else -> "Good evening"
        }
        return "$part, $name."
    }

    /** Runs the welcome unless one played in the last 10 minutes. */
    fun runIfDue(context: Context): Boolean {
        val prefs = Prefs(context)
        if (!prefs.welcomeOnOpen) return false
        val now = System.currentTimeMillis()
        if (now - prefs.lastWelcomeAt < COOLDOWN_MS) return false
        prefs.lastWelcomeAt = now
        run(context)
        return true
    }

    fun run(context: Context) {
        val prefs = Prefs(context)
        DuaPlayer.stop()
        val app = context.applicationContext
        val token = ++runToken
        greeter(context).speak("Welcome back, ${prefs.name}.") {
            main.post { if (token == runToken && prefs.reciteDua) DuaPlayer.play(app) }
        }
    }

    /** The Dua button: play the recording, or stop if something is playing. */
    fun toggleDua(context: Context): Boolean {
        appContext = context.applicationContext
        if (isSpeaking) {
            runToken++
            greeter?.stop()
            DuaPlayer.stop()
            return true
        }
        return DuaPlayer.play(context.applicationContext)
    }
}
