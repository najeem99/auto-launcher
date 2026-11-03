package com.najib.carwelcome

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import java.io.File

/**
 * Plays the Dua al-Safar recording you added in the phone app and works out which
 * line is being recited, so the car screen can show it like lyrics.
 */
object DuaPlayer {
    private var player: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null
    private var onEnd: (() -> Unit)? = null

    fun file(context: Context) = File(context.filesDir, "dua_al_safar.audio")
    fun hasRecording(context: Context) = file(context).let { it.exists() && it.length() > 0 }

    val isPlaying: Boolean get() = runCatching { player?.isPlaying == true }.getOrDefault(false)
    val positionMs: Int get() = runCatching { player?.currentPosition ?: 0 }.getOrDefault(0)
    val durationMs: Int get() = runCatching { player?.duration ?: 0 }.getOrDefault(0)

    /** Starts the recording. Returns false if none has been added yet. */
    fun play(context: Context, onFinished: () -> Unit = {}): Boolean {
        stop()
        val f = file(context)
        if (!hasRecording(context)) return false
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val mp = try {
            MediaPlayer().apply {
                setAudioAttributes(attrs)
                setDataSource(f.absolutePath)
                prepare()
            }
        } catch (_: Exception) {
            return false
        }
        val am = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs).build().also { am.requestAudioFocus(it) }
        onEnd = {
            focus?.let { am.abandonAudioFocusRequest(it) }
            focus = null
            onFinished()
        }
        mp.setOnCompletionListener { stop() }
        player = mp
        mp.start()
        return true
    }

    fun stop() {
        val p = player ?: return
        player = null
        runCatching { p.stop() }
        p.release()
        onEnd?.also { onEnd = null }?.invoke()
    }

    /** Line being recited at [positionMs], using your synced timings or an estimate. */
    fun lineAt(context: Context, positionMs: Int, durationMs: Int): Int {
        val starts = lineStarts(context, durationMs)
        var idx = 0
        starts.forEachIndexed { i, s -> if (positionMs >= s) idx = i }
        return idx
    }

    /**
     * Start time of each line in ms. Uses the timings saved with "Sync lyrics";
     * otherwise splits the recording by each line's length.
     */
    fun lineStarts(context: Context, durationMs: Int): List<Int> {
        Prefs(context).duaTimings?.let { if (it.size == DuaSafar.LINES.size) return it }
        val weights = DuaSafar.LINES.map { it.arabic.length.toFloat() }
        val total = weights.sum()
        var acc = 0f
        return weights.map { w -> (durationMs * (acc / total)).toInt().also { acc += w } }
    }
}
