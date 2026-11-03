package com.najib.carwelcome

import android.content.Context
import java.util.Calendar
import java.util.Locale

/**
 * The spoken welcome: an agent-style greeting with the weather, then Dua al-Safar.
 * The dashboard reads [currentDuaLine] and [isSpeaking] to follow along on screen.
 */
object Welcome {
    private const val COOLDOWN_MS = 10 * 60 * 1000L
    private val ARABIC = Locale("ar")
    private var greeter: Greeter? = null

    /** Index into [DuaSafar.LINES] being recited, or -1. */
    @Volatile var currentDuaLine = -1
        private set

    val isSpeaking: Boolean get() = greeter?.isSpeaking == true

    private fun greeter(context: Context) =
        greeter ?: Greeter(context.applicationContext).also { greeter = it }

    fun greetingLine(name: String, hour: Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)): String {
        val part = when {
            hour < 5 -> "Good night"
            hour < 12 -> "Good morning"
            hour < 17 -> "Good afternoon"
            else -> "Good evening"
        }
        return "$part, $name."
    }

    /** Runs the full welcome unless one played in the last 10 minutes. */
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
        val segs = mutableListOf<Greeter.Segment>()
        segs += Greeter.Segment("${greetingLine(prefs.name)} Welcome back.", Locale.US)
        Weather.latest?.let {
            segs += Greeter.Segment("It's ${it.tempC} degrees and ${it.description.lowercase()} outside.", Locale.US)
        }
        if (prefs.reciteDua) {
            segs += Greeter.Segment("Before we set off, here is Dua al-Safar.", Locale.US)
            segs += duaSegments()
        }
        segs += Greeter.Segment("Have a safe journey.", Locale.US)
        speak(context, segs)
    }

    /** The Dua button: recite Dua al-Safar, or stop if it is already speaking. */
    fun toggleDua(context: Context) {
        if (isSpeaking) { greeter?.stop(); return }
        speak(context, duaSegments())
    }

    private fun duaSegments() = DuaSafar.LINES.mapIndexed { i, line ->
        Greeter.Segment(line.arabic, ARABIC, fallback = line.meaning, tag = i)
    }

    private fun speak(context: Context, segs: List<Greeter.Segment>) {
        greeter(context).speakAll(segs, object : Greeter.Listener {
            override fun onSegmentStart(segment: Greeter.Segment) { currentDuaLine = segment.tag }
            override fun onFinished() { currentDuaLine = -1 }
        })
    }
}
