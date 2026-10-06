package com.lezi.babylog.feature.timer

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure trigger decision for the timer start/pause confirm haptic
 * (0.5.4 ticket 15, spec §M3). The device-side emission is covered by
 * TimerHapticsDeviceTest; this locks when a flip is haptic-eligible.
 */
class TimerToggleHapticTest {
    @Test
    fun `any committed running-flag flip is haptic eligible`() {
        assertThat(timerToggleHaptic(false to false, true to false)).isTrue()
        assertThat(timerToggleHaptic(false to false, false to true)).isTrue()
        assertThat(timerToggleHaptic(true to false, false to false)).isTrue()
        assertThat(timerToggleHaptic(false to true, false to false)).isTrue()
        // Side switch while the other keeps running (both running allowed).
        assertThat(timerToggleHaptic(true to false, true to true)).isTrue()
    }

    @Test
    fun `identical consecutive states stay silent`() {
        assertThat(timerToggleHaptic(false to false, false to false)).isFalse()
        assertThat(timerToggleHaptic(true to false, true to false)).isFalse()
        assertThat(timerToggleHaptic(true to true, true to true)).isFalse()
    }
}
