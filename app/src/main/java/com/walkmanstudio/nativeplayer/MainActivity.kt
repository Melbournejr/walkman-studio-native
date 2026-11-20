// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Walkman Studio Native contributors
package com.walkmanstudio.nativeplayer

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.viewmodel.ext.android.viewModel

class MainActivity : ComponentActivity() {
    private val model: LibraryViewModel by viewModel()
    private val playback: PlaybackViewModel by viewModel()

    private val updates: com.walkmanstudio.nativeplayer.update.UpdateViewModel by viewModel()

    override fun onStart() { super.onStart(); playback.connect(); updates.activate() }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent);setIntent(intent)
        if(intent.getBooleanExtra(com.walkmanstudio.nativeplayer.update.AppUpdates.OPEN_UPDATES,false)) {
            model.showNowPlaying(false);model.selectTab("settings")
        }
    }
    override fun onStop() { playback.disconnect(); super.onStop() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if(intent.getBooleanExtra(com.walkmanstudio.nativeplayer.update.AppUpdates.OPEN_UPDATES,false)) model.selectTab("settings")
        enableEdgeToEdge()
        setContent {
            val helpPreferences = remember { getSharedPreferences("device-help", MODE_PRIVATE) }
            var showHelp by remember { mutableStateOf(!helpPreferences.getBoolean("seen", false)) }
            val appearance by model.appearance.collectAsStateWithLifecycle()
            val nowPlaying by model.nowPlaying.collectAsStateWithLifecycle()
            val walkman by model.walkmanTheme.collectAsStateWithLifecycle()
            val mode by model.nowPlayingMode.collectAsStateWithLifecycle()
            val alwaysLandscape by model.cassetteLandscape.collectAsStateWithLifecycle()
            val dark = if(nowPlaying) walkman.dark else appearance == "dark" || (appearance == "system" && isSystemInDarkTheme())
            SideEffect {
                val orientation = CassetteOrientation.requested(nowPlaying, mode, alwaysLandscape)
                if (requestedOrientation != orientation) requestedOrientation = orientation
                val style = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == Activity.RESULT_OK) result.data?.data?.let(model::addTree)
            }
            val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri -> uri?.let(playback::export) }
            StudioTheme(appearance) {
                LibraryScreen(model, playback, updates, { showHelp = true }, { export.launch("walkman-playback-log.jsonl") }) {
                    try {
                        launcher.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                            putExtra(Intent.EXTRA_LOCAL_ONLY, true)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
                        })
                    } catch (_: android.content.ActivityNotFoundException) {
                        model.reportPickerError()
                    }
                }
                if (showHelp) DeviceHelp { showHelp = false; helpPreferences.edit().putBoolean("seen", true).apply() }
            }
        }
    }
}

/** Activity-only policy; neither orientation nor Compose owns the service/player. */
internal object CassetteOrientation {
    fun requested(showNowPlaying: Boolean, mode: NowPlayingMode, alwaysLandscape: Boolean): Int =
        if (showNowPlaying && mode == NowPlayingMode.CASSETTE && alwaysLandscape)
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
}
