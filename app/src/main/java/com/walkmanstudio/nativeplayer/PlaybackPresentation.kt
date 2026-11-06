// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer

import androidx.media3.common.Player

/** Presentation bounds; a restored idle queue has metadata but no prepared timeline yet. */
object PlaybackTimeline {
    data class Timeline(val durationMs: Long, val positionMs: Long, val seekEnabled: Boolean, val timelineKnown: Boolean = seekEnabled) {
        val progress: Float get() = if (timelineKnown && durationMs > 0) (positionMs.toDouble() / durationMs).coerceIn(0.0, 1.0).toFloat() else 0f
    }

    fun snapshot(
        rawDurationMs: Long,
        storedDurationMs: Long?,
        rawPositionMs: Long,
        playbackState: Int,
        isSeekable: Boolean = true,
    ): Timeline {
        val stored = storedDurationMs?.takeIf { it > 0 }
        val prepared = playbackState != Player.STATE_IDLE
        val timelineKnown = prepared && rawDurationMs > 0
        // An IDLE controller can retain its previous timeline/position after queue restoration.
        // Room/item metadata is the only duration shown until that item is prepared.
        val duration = if (prepared) rawDurationMs.takeIf { it > 0 } ?: stored ?: 0 else stored ?: 0
        // A stored duration is display metadata, not proof of a usable player timeline.
        // In particular TIME_UNSET may accompany a stale currentPosition during restore.
        val position = if (timelineKnown) rawPositionMs.coerceIn(0, duration) else 0
        return Timeline(duration, position, timelineKnown && isSeekable, timelineKnown)
    }

    fun seekTarget(
        requestedMs: Long,
        rawDurationMs: Long,
        storedDurationMs: Long?,
        rawPositionMs: Long,
        playbackState: Int,
        isSeekable: Boolean = true,
    ): Long? = snapshot(rawDurationMs, storedDurationMs, rawPositionMs, playbackState, isSeekable)
        .takeIf { it.seekEnabled }?.let { requestedMs.coerceIn(0, it.durationMs) }
}

/** Display-only cleanup: originals and Room tags are never changed. */
object NowPlayingText {
    data class Display(val title: String, val artist: String, val album: String)

    private val whitespace = Regex("\\s+")
    private val schemeUrl = Regex("(?i)\\b(?:https?|ftp)://\\S+")
    private val wwwUrl = Regex("(?i)\\bwww\\.[^\\s]*")
    private val domain = Regex("(?i)(?<![\\p{L}\\p{N}])(?:[\\p{L}\\p{N}](?:[\\p{L}\\p{N}-]*[\\p{L}\\p{N}])?\\.)+(?:com|net|org|vn|io|co|info|biz|me|tv|music|xyz|online)(?:[/?:#][^\\s]*)?(?![\\p{L}\\p{N}])")
    private val audioExtension = Regex("(?i)\\.(?:mp3|flac|wav|wave|aac|m4a|mp4|ogg|oga|opus|dsf|dff|aiff?|alac|ape|wv|wma|tta)$")
    private val junk = "(?:official(?:\\s+(?:audio|video|music\\s+video))?|lyrics?|lyric\\s+video|audio|video|hq|lossless|hi[ -]?res|(?:128|192|256|320)\\s*k(?:b(?:p)?s|bps)?|(?:16|24|32)\\s*bit(?:\\s*[/ -]?\\s*(?:44[.,]1|48|88[.,]2|96|192)\\s*k(?:hz)?)?|flac|mp3|aac|dsd(?:64|128|256)?)"
    private val bracketJunk = Regex("(?i)\\s*(?:\\[\\s*$junk\\s*\\]|\\(\\s*$junk\\s*\\))\\s*$")
    private val suffixJunk = Regex("(?i)\\s*[-–—|]\\s*$junk\\s*$")

    fun sanitize(value: String?): String {
        if (value.isNullOrBlank()) return ""
        // Brackets are structural content. Keep both valid and unmatched brackets;
        // cleanFileName removes only complete, matching whitelisted suffix groups.
        val cleaned = whitespace.replace(domain.replace(wwwUrl.replace(schemeUrl.replace(value, " "), " "), " "), " ")
            .trim().trim('-', '–', '—', '|', '·', '•', ',', ';').trim()
        return cleaned.takeIf { it.any(Char::isLetterOrDigit) }.orEmpty()
    }

    fun cleanFileName(value: String?): String {
        var clean = audioExtension.replace(value.orEmpty().substringAfterLast('/').substringAfterLast('\\'), "").replace('_', ' ')
        clean = sanitize(clean)
        while (true) {
            val next = suffixJunk.replace(bracketJunk.replace(clean, ""), "").trim()
            if (next == clean) return clean
            clean = next
        }
    }

    fun display(
        tagTitle: String? = null,
        tagArtist: String? = null,
        tagAlbum: String? = null,
        fileName: String? = null,
        mediaTitle: String? = null,
        mediaArtist: String? = null,
        mediaAlbum: String? = null,
    ): Display {
        val file = fileName.orEmpty()
        val fileStem = audioExtension.replace(file, "")
        val media = mediaTitle.orEmpty()
        val mediaIsFileFallback = (file.isNotBlank() && media.trim() in listOf(file, fileStem)) ||
            (file.isBlank() && (audioExtension.containsMatchIn(media) || '_' in media))
        val title = sanitize(tagTitle).ifBlank {
            // Media3 may have read a genuine title before Room finishes updating.
            // Catalog metadata otherwise uses the uncleaned file stem as its title.
            if (!mediaIsFileFallback && !mediaTitle.isNullOrBlank()) sanitize(mediaTitle) else ""
        }.ifBlank { cleanFileName(fileName ?: mediaTitle) }.ifBlank { "Chưa chọn bài" }
        return Display(title, sanitize(tagArtist).ifBlank { sanitize(mediaArtist) }, sanitize(tagAlbum).ifBlank { sanitize(mediaAlbum) })
    }
}
