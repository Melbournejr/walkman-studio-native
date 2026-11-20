// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Walkman Studio Native contributors
package com.walkmanstudio.nativeplayer

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ApplicationInfo
import android.net.ConnectivityManager
import android.os.Looper
import android.view.ViewGroup
import androidx.lifecycle.Lifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSessionService
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.robolectric.util.ReflectionHelpers
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.android.controller.ServiceController
import org.robolectric.shadows.ShadowBinder
import org.robolectric.shadows.ShadowProcess
import com.walkmanstudio.nativeplayer.playback.PlaybackService

/** Host startup smoke only: this does not establish physical Android/HyperOS behavior. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
@androidx.annotation.OptIn(UnstableApi::class)
class MainActivitySmokeTest {
    private var playbackFixture: ServiceController<PlaybackService>? = null
    // Robolectric 4.16.1 does not implement the API33 typed flag overloads.
    // Keep deprecated int-flag calls confined to this host test; production code
    // does not use them. This still checks the real merged manifest metadata.
    @Suppress("DEPRECATION")
    @Before fun initializeStartupDependencyInHostSandbox() {
        val application = ApplicationProvider.getApplicationContext<WalkmanStudioApplication>()
        // Keep this startup/player fixture deterministically offline now that OTA
        // is present. UpdateFlowTest exercises update HTTP through its transport fake.
        val connectivity=application.getSystemService(ConnectivityManager::class.java)
        shadowOf(connectivity).setActiveNetworkInfo(null)
        org.junit.Assert.assertNull("Host startup must remain offline",connectivity.activeNetworkInfo)
        val provider = application.packageManager.getProviderInfo(
            ComponentName(application, "androidx.startup.InitializationProvider"),
            PackageManager.GET_META_DATA,
        )
        assertTrue("Production startup provider must remain enabled", provider.enabled)
        assertEquals("androidx.startup", provider.metaData.getString("androidx.work.WorkManagerInitializer"))
        // Robolectric does not run AndroidX startup providers during this host
        // application bootstrap. Exercise the REAL WorkManager with the same
        // default configuration as WorkManagerInitializer; never replace it with
        // a mock or change production startup to make this test pass.
        try {
            WorkManager.getInstance(application)
        } catch (_: IllegalStateException) {
            WorkManager.initialize(application, Configuration.Builder().build())
        }
        // Android supplies the application's real identity and a non-null
        // ComponentName/Binder when bindService succeeds. Robolectric otherwise
        // defaults to onServiceConnected(null, null); configure that OS boundary,
        // retaining the real production service/session/player and controller.
        ShadowProcess.setUid(application.applicationInfo.uid)
        ShadowBinder.setCallingUid(application.applicationInfo.uid)
        val component = ComponentName(application, PlaybackService::class.java)
        val intent = Intent(MediaSessionService.SERVICE_INTERFACE).setComponent(component)
        val fixture = Robolectric.buildService(PlaybackService::class.java).create()
        playbackFixture = fixture
        val binder = fixture.get().onBind(intent)
        assertNotNull("PlaybackService must return its actual Media3 service Binder", binder)
        shadowOf(application).setComponentNameAndServiceForBindServiceForIntent(intent, component, binder)
        shadowOf(application).setUnbindServiceCallsOnServiceDisconnected(false)
    }

    @After fun destroyServiceFixture() {
        playbackFixture?.destroy()
        playbackFixture = null
        shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
        ShadowBinder.reset()
        ShadowProcess.reset()
    }

    @Suppress("DEPRECATION") // Same host-only flag-overload limitation as above.
    @Test fun launchesAndRecreatesWithManifestApplicationAndComposeContent() {
        val application = ApplicationProvider.getApplicationContext<WalkmanStudioApplication>()
        assertEquals("com.walkmanstudio.nativeplayer", application.packageName)
        assertEquals(0, application.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
        val mediaReceiver=application.packageManager.getReceiverInfo(ComponentName(application,"androidx.media3.session.MediaButtonReceiver"),0)
        assertTrue("Cold media-button resumption must have a registered receiver",mediaReceiver.enabled && mediaReceiver.exported)
        val packageInfo = application.packageManager.getPackageInfo(
            application.packageName,
            PackageManager.GET_PERMISSIONS,
        )
        // OTA is the explicitly authorized network feature from stage10 onward.
        val permissions=packageInfo.requestedPermissions.orEmpty().toSet()
        assertTrue(permissions.containsAll(setOf("android.permission.INTERNET","android.permission.ACCESS_NETWORK_STATE","android.permission.REQUEST_INSTALL_PACKAGES","android.permission.POST_NOTIFICATIONS")))
        assertTrue(permissions.intersect(setOf("android.permission.MANAGE_EXTERNAL_STORAGE","android.permission.READ_MEDIA_AUDIO","android.permission.READ_EXTERNAL_STORAGE","android.permission.WRITE_EXTERNAL_STORAGE")).isEmpty())
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        val servicePlayer=ReflectionHelpers.getField<Any>(playbackFixture!!.get(),"player")
        try {
            shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
            assertResumedWithContent(controller.get())
            assertTrue("Activity must bind to the configured PlaybackService", shadowOf(application).boundServiceConnections.isNotEmpty())
            controller.recreate()
            shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
            assertResumedWithContent(controller.get())
            assertSame("Activity recreation must retain the service-owned player",servicePlayer,ReflectionHelpers.getField<Any>(playbackFixture!!.get(),"player"))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun assertResumedWithContent(activity: MainActivity) {
        assertEquals(Lifecycle.State.RESUMED, activity.lifecycle.currentState)
        assertFalse(activity.isFinishing)
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        assertTrue("Compose content must attach to the activity", content.childCount > 0)
    }
}
