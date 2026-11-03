package com.najib.carwelcome

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.car.app.connection.CarConnection
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * The media service Android Auto connects to. It serves the surah list, plays
 * the audio, and says the welcome line when the phone connects to the car.
 */
class SurahService : MediaLibraryService() {

    private lateinit var prefs: Prefs
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaLibrarySession
    private lateinit var greeter: Greeter
    private lateinit var carConnectionType: LiveData<Int>
    private val main = Handler(Looper.getMainLooper())
    private val fallbackTried = mutableSetOf<String>()
    private var resumeAfterGreeting = false

    private val carObserver = Observer<Int> { type ->
        if (type == CarConnection.CONNECTION_TYPE_PROJECTION) greetIfDue()
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        greeter = Greeter(this)
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        player.addListener(playerListener)

        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        session = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .setSessionActivity(openApp)
            .setCustomLayout(ImmutableList.of(stopButton))
            .build()

        carConnectionType = CarConnection(this).type
        carConnectionType.observeForever(carObserver)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        carConnectionType.removeObserver(carObserver)
        greeter.shutdown()
        session.release()
        player.release()
        super.onDestroy()
    }

    // ---- Greeting ----------------------------------------------------------

    /** Greets at most once per car trip (10 minute cool-down). Main thread only. */
    private fun greetIfDue() {
        if (!prefs.greetOnConnect) return
        val now = System.currentTimeMillis()
        if (now - prefs.lastGreetAt < GREET_COOLDOWN_MS) return
        prefs.lastGreetAt = now

        resumeAfterGreeting = player.isPlaying || prefs.autoPlayAfterGreeting
        if (player.isPlaying) player.pause()
        greeter.speak(prefs.greetingText) {
            main.post {
                if (resumeAfterGreeting) startOrResume()
                resumeAfterGreeting = false
            }
        }
    }

    /** Starts the journey surah (or continues the last one) when a drive begins. */
    private fun startOrResume() {
        if (player.mediaItemCount == 0 || prefs.journeySurah != 0) {
            player.setMediaItems(Library.queueFrom(prefs.driveStartSurah, reciter()))
        }
        player.prepare()
        player.play()
    }

    /** Stops playback and clears the queue; the next play starts a fresh drive queue. */
    private fun stopPlayback() {
        resumeAfterGreeting = false
        player.stop()
        player.clearMediaItems()
    }

    private fun reciter() = reciterById(prefs.reciterId)

    // ---- Player events -----------------------------------------------------

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // If Android Auto resumes audio while the welcome is being spoken,
            // hold the surah until the greeting has finished.
            if (isPlaying && greeter.isSpeaking) {
                resumeAfterGreeting = true
                player.pause()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            Library.surahNumber(mediaItem?.mediaId)?.let { prefs.lastSurah = it }
        }

        override fun onPlayerError(error: PlaybackException) {
            // Chosen reciter's server failed: switch this surah to the backup source once.
            val index = player.currentMediaItemIndex
            val item = player.currentMediaItem ?: return
            val n = Library.surahNumber(item.mediaId) ?: return
            if (!fallbackTried.add(item.mediaId)) return
            player.replaceMediaItem(index, Library.surah(n, FALLBACK_RECITER))
            player.prepare()
            player.play()
        }
    }

    // ---- What Android Auto asks for ---------------------------------------

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            if (controller.packageName == ANDROID_AUTO_PACKAGE) main.post { greetIfDue() }
            // Offer the Stop button to every controller, including Android Auto
            // and the media notification (which Android Auto mirrors).
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                .buildUpon()
                .add(STOP_COMMAND)
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .setCustomLayout(ImmutableList.of(stopButton))
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == ACTION_STOP) {
                stopPlayback()
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return super.onCustomCommand(session, controller, customCommand, args)
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(Library.root(), params))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val all = Library.children(parentId, reciter())
                ?: return Futures.immediateFuture(
                    LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
                )
            val from = (page.toLong() * pageSize).coerceAtMost(all.size.toLong()).toInt()
            val to = (from.toLong() + pageSize).coerceAtMost(all.size.toLong()).toInt()
            return Futures.immediateFuture(
                LibraryResult.ofItemList(ImmutableList.copyOf(all.subList(from, to)), params)
            )
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val item = Library.item(mediaId, reciter())
            return Futures.immediateFuture(
                if (item != null) LibraryResult.ofItem(item, null)
                else LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
            )
        }

        /** Tapping a surah plays it and then continues through the following surahs. */
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long
        ): ListenableFuture<MediaItemsWithStartPosition> {
            if (mediaItems.size == 1) {
                val n = surahFor(mediaItems[0])
                if (n != null) {
                    return Futures.immediateFuture(
                        MediaItemsWithStartPosition(Library.queueFrom(n, reciter()), 0, startPositionMs)
                    )
                }
            }
            val resolved = mediaItems.mapNotNull { m -> surahFor(m)?.let { Library.surah(it, reciter()) } }
            return Futures.immediateFuture(
                MediaItemsWithStartPosition(resolved, startIndex.coerceIn(0, maxOf(0, resolved.size - 1)), startPositionMs)
            )
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> =
            Futures.immediateFuture(
                mediaItems.mapNotNull { m -> surahFor(m)?.let { Library.surah(it, reciter()) } }
                    .toMutableList()
            )

        /** Android Auto's "resume last app" lands here: start the journey surah. */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo
        ): ListenableFuture<MediaItemsWithStartPosition> =
            Futures.immediateFuture(
                MediaItemsWithStartPosition(
                    Library.queueFrom(prefs.driveStartSurah, reciter()), 0, C.TIME_UNSET
                )
            )

        /** Accepts a surah id from the list, or a voice request like "play Surah Yasin". */
        private fun surahFor(item: MediaItem): Int? =
            Library.surahNumber(item.mediaId)
                ?: item.requestMetadata.searchQuery?.let { findSurah(it) }
    }

    @Suppress("DEPRECATION")
    private val stopButton: CommandButton = CommandButton.Builder()
        .setDisplayName("Stop")
        .setIconResId(R.drawable.ic_stop)
        .setSessionCommand(STOP_COMMAND)
        .build()

    companion object {
        private const val ACTION_STOP = "com.najib.carwelcome.STOP"
        private val STOP_COMMAND = SessionCommand(ACTION_STOP, Bundle.EMPTY)
        private const val ANDROID_AUTO_PACKAGE = "com.google.android.projection.gearhead"
        private const val GREET_COOLDOWN_MS = 10 * 60 * 1000L
    }
}
