// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Walkman Studio Native contributors
package com.walkmanstudio.nativeplayer

import android.app.Application
import com.walkmanstudio.nativeplayer.library.LibraryDatabase
import com.walkmanstudio.nativeplayer.library.LibraryRepository
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.core.context.startKoin
import org.koin.dsl.module

class WalkmanStudioApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@WalkmanStudioApplication)
            modules(module {
                single { LibraryDatabase.getInstance(androidContext()) }
                single { LibraryRepository(androidContext(), get()) }
                viewModel { LibraryViewModel(get(), get(), androidContext()) }
                viewModel { PlaybackViewModel(androidContext()) }
                single { com.walkmanstudio.nativeplayer.update.AppUpdates(androidContext()) }
                viewModel { com.walkmanstudio.nativeplayer.update.UpdateViewModel(get(),androidContext()) }
            })
        }
    }
}
