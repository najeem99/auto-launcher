package com.najib.carwelcome

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Speaks a sequence of lines (English and Arabic) through the phone's media
 * audio, which Android Auto plays on the car speakers. Other audio is ducked.
 */
class Greeter(context: Context) : TextToSpeech.OnInitListener {

    /** One spoken line. [fallback] is spoken in English if the phone has no voice for [locale]. */
    data class Segment(val text: String, val locale: Locale, val fallback: String? = null, val tag: Int = -1)

    interface Listener {
        fun onSegmentStart(segment: Segment) {}
        fun onFinished() {}
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attrs)
        .build()
    private val tts = TextToSpeech(context.applicationContext, this)
    private var ready = false
    private var failed = false
    private var pending: Pair<List<Segment>, Listener>? = null
    private var current: List<Segment> = emptyList()
    private var listener: Listener? = null
    private var lastId: String? = null

    @Volatile var isSpeaking = false
        private set

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            failed = true
            pending?.second?.onFinished()
            pending = null
            return
        }
        tts.setAudioAttributes(attrs)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {
                val index = utteranceId.substringAfterLast('#').toIntOrNull() ?: return
                current.getOrNull(index)?.let { listener?.onSegmentStart(it) }
            }
            override fun onDone(utteranceId: String) { if (utteranceId == lastId) finish() }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) { if (utteranceId == lastId) finish() }
            override fun onError(utteranceId: String, errorCode: Int) { if (utteranceId == lastId) finish() }
            override fun onStop(utteranceId: String, interrupted: Boolean) { if (utteranceId == lastId) finish() }
        })
        ready = true
        pending?.let { (segs, l) -> speakAll(segs, l) }
        pending = null
    }

    fun speakAll(segments: List<Segment>, l: Listener) {
        if (failed) { l.onFinished(); return }
        if (!ready) { pending = segments to l; return }
        tts.stop()
        current = segments
        listener = l
        lastId = null
        val run = System.nanoTime()
        segments.forEachIndexed { i, seg ->
            val (text, locale) = when {
                hasVoice(seg.locale) -> seg.text to seg.locale
                seg.fallback != null -> seg.fallback to Locale.US
                else -> return@forEachIndexed
            }
            tts.setLanguage(locale)
            val id = "$run#$i"
            if (tts.speak(text, TextToSpeech.QUEUE_ADD, null, id) == TextToSpeech.SUCCESS) lastId = id
        }
        if (lastId == null) { l.onFinished(); return }
        isSpeaking = true
        audioManager.requestAudioFocus(focusRequest)
    }

    fun stop() {
        if (!ready) { pending = null; return }
        tts.stop()
        finish()
    }

    private fun hasVoice(locale: Locale) =
        tts.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE

    private fun finish() {
        if (!isSpeaking) return
        isSpeaking = false
        lastId = null
        audioManager.abandonAudioFocusRequest(focusRequest)
        listener?.onFinished()
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }
}
