package com.najib.carwelcome

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer

/**
 * Plays Dua al-Safar (the recording built into the app, or one you added in the
 * phone app) and works out which line is being recited, so the car screen can
 * show it like lyrics.
 */
object DuaPlayer {
    private var player: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null
    private var onEnd: (() -> Unit)? = null
    private var noisyContext: Context? = null

    /** Unplugging from the car (or headphones) makes audio "noisy": stop right away. */
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) stop()
        }
    }

    /** Start of each phrase in the built-in recording (ms). */
    private val BUILT_IN_STARTS = DuaSafar.LINES.mapIndexed { i, l -> if (i == 0) 0 else l.wordStarts.first() }


    val isPlaying: Boolean get() = runCatching { player?.isPlaying == true }.getOrDefault(false)
    /** Loaded and either playing or paused part-way. */
    val isActive: Boolean get() = player != null
    val isPaused: Boolean get() = player != null && !isPlaying
    /** True once the recording has played to the end during this drive. */
    @Volatile var completedThisDrive = false
    val positionMs: Int get() = runCatching { player?.currentPosition ?: 0 }.getOrDefault(0)
    val durationMs: Int get() = runCatching { player?.duration ?: 0 }.getOrDefault(0)

    /** Starts the built-in recording. Returns false if it couldn't be played. */
    fun play(context: Context, onFinished: () -> Unit = {}): Boolean {
        stop()
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val mp = try {
            MediaPlayer().apply {
                setAudioAttributes(attrs)
                context.resources.openRawResourceFd(R.raw.dua_al_safar).use {
                    setDataSource(it.fileDescriptor, it.startOffset, it.length)
                }
                prepare()
            }
        } catch (_: Exception) {
            return false
        }
        val am = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS -> stop()
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pause()
                }
            }
            .build().also { am.requestAudioFocus(it) }
        val app = context.applicationContext
        runCatching {
            app.registerReceiver(noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
            noisyContext = app
        }
        onEnd = {
            focus?.let { am.abandonAudioFocusRequest(it) }
            focus = null
            noisyContext?.let { c -> runCatching { c.unregisterReceiver(noisyReceiver) } }
            noisyContext = null
            onFinished()
        }
        mp.setOnCompletionListener {
            completedThisDrive = true
            stop()
        }
        player = mp
        mp.start()
        return true
    }

    fun pause() {
        runCatching { if (player?.isPlaying == true) player?.pause() }
    }

    fun resume() {
        runCatching { if (player != null && player?.isPlaying == false) player?.start() }
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
     * Word being recited inside line [line] of the built-in recording, or -1 when no
     * word has started yet or a recording of your own is playing (no word timings).
     */
    fun wordAt(context: Context, line: Int, positionMs: Int): Int {
        val starts = DuaSafar.LINES.getOrNull(line)?.wordStarts ?: return -1
        var idx = -1
        starts.forEachIndexed { i, s -> if (positionMs >= s) idx = i }
        return idx
    }

    /** Start time of each line in ms, for the built-in recording. */
    fun lineStarts(context: Context, durationMs: Int): List<Int> {
        return BUILT_IN_STARTS
    }
}
