// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Walkman Studio Native contributors
package com.walkmanstudio.nativeplayer.core

import java.util.Locale

/** Discovery candidates, not a claim that stage 1 can decode these formats. */
object AudioFileTypes {
    private val suffixes = setOf(
        "mp3", "flac", "wav", "wave", "aac", "m4a", "m4b", "mp4", "ogg", "oga", "opus",
        "aif", "aiff", "aifc", "alac", "ape", "wv", "tta", "wma", "ac3", "eac3", "dts",
        "dsf", "dff", "amr", "3gp", "mka", "mp2", "mp1", "a52", "caf", "tak", "ofr",
    )
    private val playlists = setOf("m3u", "m3u8", "pls", "xspf", "cue", "asx", "wpl")
    private val playlistMimeTypes = setOf(
        "audio/x-mpegurl", "audio/mpegurl", "audio/x-scpls", "application/vnd.apple.mpegurl",
        "application/x-mpegurl", "application/xspf+xml",
    )

    fun isAudioCandidate(document: SourceDocument): Boolean {
        if (document.isDirectory) return false
        val extension = document.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val mimeType = document.mimeType.substringBefore(';').trim().lowercase(Locale.ROOT)
        if (extension in playlists || mimeType in playlistMimeTypes) return false
        return extension in suffixes || mimeType.startsWith("audio/")
    }
}
