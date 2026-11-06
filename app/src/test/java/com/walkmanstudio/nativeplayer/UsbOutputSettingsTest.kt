// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class UsbOutputSettingsTest {
    @get:Rule val compose=createComposeRule()
    @Test fun unsupportedDeviceExplainsAndDisablesSwitchWithoutClaim() {
        compose.setContent {MaterialTheme {UsbOutputControls(UsbSettingsUi(reason="Chưa kết nối thiết bị USB audio")){_,_->error("Disabled switch invoked")}}}
        compose.onNodeWithContentDescription("Chế độ mixer USB DAC").assertIsNotEnabled().assertIsOff()
        compose.onNodeWithText("Chưa kết nối thiết bị USB audio").assertIsDisplayed()
        compose.onNodeWithText("Bit-perfect USB").assertDoesNotExist()
        compose.onNodeWithText("USB DAC bit-perfect").assertDoesNotExist()
    }
    @Test fun firstEnableRequiresVolumeWarningAndExplicitAcceptance() {
        var enabled=false;var accepted=false
        compose.setContent {MaterialTheme {UsbOutputControls(UsbSettingsUi(available=true,connected=true)){on,ack->enabled=on;accepted=ack}}}
        compose.onNodeWithContentDescription("Chế độ mixer USB DAC").performClick()
        compose.onNodeWithText("Hạ âm lượng DAC/ampli trước, nút âm lượng điện thoại có thể không còn tác dụng").assertIsDisplayed()
        assertFalse(enabled)
        compose.onNodeWithText("Đã hạ âm lượng, bật").performClick()
        assertTrue(enabled);assertTrue(accepted)
    }
    @Test fun requestedButUnconfirmedKeepsSystemMixerLabel() {
        compose.setContent {MaterialTheme {UsbOutputControls(UsbSettingsUi(available=true,requested=true,connected=true,reason="Chờ AudioTrack được tạo")){_,_->}}}
        compose.onNodeWithText("USB · Mixer hệ thống").assertIsDisplayed()
        compose.onNodeWithText("Bit-perfect USB").assertDoesNotExist()
    }
    @Test fun disconnectInvalidatesPreviouslyConfirmedUsbStatus() {
        val store=ViewModelStore()
        val factory=viewModelFactory {initializer {PlaybackViewModel(RuntimeEnvironment.getApplication())}}
        val model=ViewModelProvider(store,factory)[PlaybackViewModel::class.java]
        try {
            model.usb.value=UsbSettingsUi(available=true,requested=true,verified=true,connected=true)
            model.signal.value=SignalUi(usbStatus="Bit-perfect USB")
            model.disconnect()
            assertFalse(model.usb.value.verified);assertFalse(model.usb.value.available)
            assertNull(model.signal.value.usbStatus)
            assertEquals("Chưa kết nối trình phát",model.usb.value.reason)
        } finally {store.clear()}
    }
}
