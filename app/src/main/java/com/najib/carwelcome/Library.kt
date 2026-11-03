package com.najib.carwelcome

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

/**
 * The browse tree Android Auto shows:
 *   root
 *   ├── Surahs 1–30      (each folder becomes a tab on the car screen)
 *   ├── Surahs 31–60
 *   ├── Surahs 61–90
 *   └── Surahs 91–114
 */
object Library {
    const val ROOT_ID = "root"
    private const val GROUP_PREFIX = "group:"
    private const val SURAH_PREFIX = "surah:"
    private val GROUPS = listOf(1..30, 31..60, 61..90, 91..114)

    fun root(): MediaItem = folder(ROOT_ID, "Holy Quran")

    fun children(parentId: String, reciter: Reciter): List<MediaItem>? = when {
        parentId == ROOT_ID -> GROUPS.mapIndexed { i, r ->
            folder("$GROUP_PREFIX$i", "Surahs ${r.first}–${r.last}")
        }
        parentId.startsWith(GROUP_PREFIX) ->
            GROUPS.getOrNull(parentId.removePrefix(GROUP_PREFIX).toIntOrNull() ?: -1)
                ?.map { surah(it, reciter) }
        else -> null
    }

    fun item(mediaId: String, reciter: Reciter): MediaItem? {
        if (mediaId == ROOT_ID) return root()
        if (mediaId.startsWith(GROUP_PREFIX)) {
            val i = mediaId.removePrefix(GROUP_PREFIX).toIntOrNull() ?: return null
            val r = GROUPS.getOrNull(i) ?: return null
            return folder(mediaId, "Surahs ${r.first}–${r.last}")
        }
        return surahNumber(mediaId)?.let { surah(it, reciter) }
    }

    fun surahNumber(mediaId: String?): Int? =
        mediaId?.takeIf { it.startsWith(SURAH_PREFIX) }
            ?.removePrefix(SURAH_PREFIX)?.toIntOrNull()?.takeIf { it in 1..114 }

    /** Surah [start] followed by the rest of the Quran, so playback carries on. */
    fun queueFrom(start: Int, reciter: Reciter): List<MediaItem> =
        (start..114).map { surah(it, reciter) }

    fun surah(n: Int, reciter: Reciter): MediaItem =
        MediaItem.Builder()
            .setMediaId("$SURAH_PREFIX$n")
            .setUri(reciter.url(n))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle("$n. ${SURAH_NAMES[n - 1]}")
                    .setDisplayTitle("$n. ${SURAH_NAMES[n - 1]}")
                    .setArtist(reciter.name)
                    .setAlbumTitle("Holy Quran")
                    .setTrackNumber(n)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .build()
            )
            .build()

    private fun folder(id: String, title: String): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .build()
            )
            .build()
}
