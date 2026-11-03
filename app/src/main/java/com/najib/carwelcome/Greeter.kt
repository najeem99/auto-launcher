package com.najib.carwelcome

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Speaks the welcome line through the phone's media audio, which Android Auto
 * sends to the car speakers. Other audio is ducked while it speaks.
 */
class Greeter(context: Context) : TextToSpeech.OnInitListener {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attrs)
        .build()
    private val tts = TextToSpeech(context.applicationContext, this)
    private val callbacks = ConcurrentHashMap<String, () -> Unit>()
    private var ready = false
    private var failed = false
    private var pending: Pair<String, () -> Unit>? = null

    @Volatile var isSpeaking = false
        private set

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            failed = true
            pending?.second?.invoke()
            pending = null
            return
        }
        val langResult = tts.setLanguage(Locale.getDefault())
        if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts.setLanguage(Locale.US)
        }
        tts.setAudioAttributes(attrs)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {}
            override fun onDone(utteranceId: String) = finish(utteranceId)
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) = finish(utteranceId)
            override fun onError(utteranceId: String, errorCode: Int) = finish(utteranceId)
        })
        ready = true
        pending?.let { (text, done) -> speak(text, done) }
        pending = null
    }

    /** Speaks [text]; [onDone] runs afterwards (on a background thread) even if speech fails. */
    fun speak(text: String, onDone: () -> Unit) {
        if (failed) { onDone(); return }
        if (!ready) { pending = text to onDone; return }
        val id = UUID.randomUUID().toString()
        callbacks[id] = onDone
        isSpeaking = true
        audioManager.requestAudioFocus(focusRequest)
        if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) == TextToSpeech.ERROR) finish(id)
    }

    private fun finish(id: String) {
        isSpeaking = false
        audioManager.abandonAudioFocusRequest(focusRequest)
        callbacks.remove(id)?.invoke()
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }
}
