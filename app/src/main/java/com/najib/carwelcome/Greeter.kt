package com.najib.carwelcome

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale

/**
 * Speaks the one-line welcome in a female English voice through the phone's
 * media audio, which Android Auto plays on the car speakers.
 */
class Greeter(context: Context) : TextToSpeech.OnInitListener {

    private val tts = TextToSpeech(context.applicationContext, this)
    private var ready = false
    private var failed = false
    private var pending: Pair<String, () -> Unit>? = null
    private var onDone: (() -> Unit)? = null

    @Volatile var isSpeaking = false
        private set

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            failed = true
            pending?.second?.invoke()
            pending = null
            return
        }
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        tts.language = Locale.US
        femaleVoice()?.let { tts.voice = it }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {}
            override fun onDone(utteranceId: String) = finish()
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) = finish()
            override fun onError(utteranceId: String, errorCode: Int) = finish()
            override fun onStop(utteranceId: String, interrupted: Boolean) = finish()
        })
        ready = true
        pending?.let { (t, d) -> speak(t, d) }
        pending = null
    }

    /** Speaks [text]; [done] runs afterwards on a background thread, even if speech fails. */
    fun speak(text: String, done: () -> Unit) {
        if (failed) { done(); return }
        if (!ready) { pending = text to done; return }
        onDone = done
        isSpeaking = true
        if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "welcome") == TextToSpeech.ERROR) finish()
    }

    fun stop() {
        pending = null
        if (ready) tts.stop()
        finish()
    }

    private fun finish() {
        if (!isSpeaking) return
        isSpeaking = false
        onDone?.also { onDone = null }?.invoke()
    }

    /**
     * Android has no "gender" on voices, so pick from Google's known female English
     * voices, preferring ones installed on the phone. Falls back to the default voice.
     */
    private fun femaleVoice(): Voice? {
        val voices = runCatching { tts.voices }.getOrNull()?.filter {
            it.locale.language == "en" &&
                !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
        } ?: return null
        for (prefix in FEMALE_VOICES) {
            voices.filter { it.name.startsWith(prefix) }
                .minByOrNull { if (it.isNetworkConnectionRequired) 1 else 0 }
                ?.let { return it }
        }
        return voices.firstOrNull { it.name.contains("female", ignoreCase = true) }
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }

    companion object {
        private val FEMALE_VOICES = listOf(
            "en-us-x-tpf", "en-us-x-sfg", "en-us-x-iob", "en-us-x-iog", "en-us-x-tpc",
            "en-gb-x-gba", "en-gb-x-gbc", "en-gb-x-gbg", "en-au-x-afa", "en-au-x-afc",
            "en-in-x-ena", "en-in-x-ahp",
        )
    }
}
