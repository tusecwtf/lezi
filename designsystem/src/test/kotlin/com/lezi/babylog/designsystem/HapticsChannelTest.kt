package com.lezi.babylog.designsystem

import android.view.HapticFeedbackConstants
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure outcome-haptic contracts (0.5.4 ticket 15, spec §M3): tone→platform-constant
 * mapping on API 30+, Compose LongPress fallback below, and the settings-respecting
 * guarantee (no ignore-flags path — enforced by the channel type only carrying
 * constants the framework checks against system settings).
 */
class HapticsChannelTest {
    @Test
    fun `confirm and reject map to the platform outcome constants on R plus`() {
        assertEquals(
            LeziHapticChannel.Platform(HapticFeedbackConstants.CONFIRM),
            leziHapticChannel(LeziHapticTone.Confirm, sdkInt = 30),
        )
        assertEquals(
            LeziHapticChannel.Platform(HapticFeedbackConstants.REJECT),
            leziHapticChannel(LeziHapticTone.Reject, sdkInt = 30),
        )
        assertEquals(
            LeziHapticChannel.Platform(HapticFeedbackConstants.CONFIRM),
            leziHapticChannel(LeziHapticTone.Confirm, sdkInt = 35),
        )
        assertEquals(
            LeziHapticChannel.Platform(HapticFeedbackConstants.REJECT),
            leziHapticChannel(LeziHapticTone.Reject, sdkInt = 35),
        )
    }

    @Test
    fun `below R both tones degrade to the compose LongPress tier`() {
        assertEquals(
            LeziHapticChannel.ComposeFallback(HapticFeedbackType.LongPress),
            leziHapticChannel(LeziHapticTone.Confirm, sdkInt = 26),
        )
        assertEquals(
            LeziHapticChannel.ComposeFallback(HapticFeedbackType.LongPress),
            leziHapticChannel(LeziHapticTone.Reject, sdkInt = 29),
        )
    }

    @Test
    fun `platform channels only carry outcome constants that respect system settings`() {
        // CONFIRM (3) and REJECT (4) are plain outcome effects: performHapticFeedback
        // applies the system haptic-feedback setting unless an ignore flag is passed,
        // and the channel model cannot express flags at all.
        for (sdkInt in 30..35) {
            for (tone in LeziHapticTone.entries) {
                val channel = leziHapticChannel(tone, sdkInt)
                assertTrue("channel must stay flag-free at sdk $sdkInt", channel is LeziHapticChannel.Platform)
                assertTrue(
                    "constant must be CONFIRM or REJECT at sdk $sdkInt",
                    (channel as LeziHapticChannel.Platform).constant ==
                        HapticFeedbackConstants.CONFIRM ||
                        channel.constant == HapticFeedbackConstants.REJECT,
                )
            }
        }
    }
}
