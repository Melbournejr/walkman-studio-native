// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import android.content.Context
import android.os.Bundle
import com.walkmanstudio.nativeplayer.library.ArtworkCache
import com.walkmanstudio.nativeplayer.library.TrackEntity

@androidx.annotation.OptIn(UnstableApi::class)
fun TrackEntity.asMediaItem(context: Context? = null): MediaItem = MediaItem.Builder().setMediaId(id.toString())
    .setUri(SafAudioDataSource.trackUri(id))
    .setMediaMetadata(MediaMetadata.Builder().setTitle(title ?: name.substringBeforeLast('.', name)).setArtist(artist)
        .setAlbumTitle(album).setAlbumArtist(albumArtist).setTrackNumber(trackNumber).setDiscNumber(discNumber)
        .setExtras(Bundle().apply {
            trackGain?.let { putDouble("trackGain",it) };albumGain?.let { putDouble("albumGain",it) }
            trackPeak?.let { putDouble("trackPeak",it) };albumPeak?.let { putDouble("albumPeak",it) }
        }).setDurationMs(durationMs).setIsPlayable(true).setIsBrowsable(false)
        .apply { if (context != null) ArtworkCache.file(context, coverKey, 128)?.takeIf { it.length() <= 32 * 1024 }?.let { setArtworkData(it.readBytes(), MediaMetadata.PICTURE_TYPE_FRONT_COVER) } }
        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC).build()).build()

/** Transient focus suppression/buffering must not stop a requested playback session. */
fun keepServiceAfterTaskRemoved(playWhenReady: Boolean, itemCount: Int, state: Int): Boolean =
    playWhenReady && itemCount > 0 && state != androidx.media3.common.Player.STATE_ENDED && state != androidx.media3.common.Player.STATE_IDLE
