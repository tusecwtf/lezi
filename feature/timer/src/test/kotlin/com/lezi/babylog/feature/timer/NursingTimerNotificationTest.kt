package com.lezi.babylog.feature.timer

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure notification-model lock for the actionable timer notification (0.5.4 ticket 12):
 * chronometer flag, status words, action set, and the monochrome icon passthrough.
 * The Android builder glue is exercised on-device (release-ticket smoke).
 */
class NursingTimerNotificationTest {
    @Test
    fun runningLeftSpecUsesChronometerAndPauseAction() {
        val spec = nursingTimerNotificationSpec(
            leftMs = 65_000L,
            rightMs = 10_000L,
            leftRunning = true,
            rightRunning = false,
            nowWallMs = 1_000_000L,
            smallIconResId = 12_345,
        )
        assertThat(spec.statusWord).isEqualTo("进行中 · 左")
        assertThat(spec.title).isEqualTo("乐记 · 喂奶计时（进行中 · 左）")
        assertThat(spec.text).isEqualTo("左 1:05 · 右 0:10")
        assertThat(spec.usesChronometer).isTrue()
        assertThat(spec.chronometerBaseWallMs).isEqualTo(1_000_000L - 75_000L)
        assertThat(spec.showWhen).isTrue()
        assertThat(spec.actions).hasSize(2)
        assertThat(spec.actions[0].kind)
            .isEqualTo(NursingTimerNotifActionKind.TOGGLE_PAUSE_RESUME)
        assertThat(spec.actions[0].label).isEqualTo(NURSING_TIMER_NOTIF_LABEL_PAUSE)
        assertThat(spec.actions[1].kind).isEqualTo(NursingTimerNotifActionKind.FINISH)
        assertThat(spec.actions[1].label).isEqualTo(NURSING_TIMER_NOTIF_LABEL_FINISH)
        assertThat(spec.smallIconResId).isEqualTo(12_345)
    }

    @Test
    fun runningRightSpecCarriesRightStatusWord() {
        val spec = nursingTimerNotificationSpec(
            leftMs = 30_000L,
            rightMs = 0L,
            leftRunning = false,
            rightRunning = true,
            nowWallMs = 500_000L,
            smallIconResId = 1,
        )
        assertThat(spec.statusWord).isEqualTo("进行中 · 右")
        assertThat(spec.title).isEqualTo("乐记 · 喂奶计时（进行中 · 右）")
        assertThat(spec.usesChronometer).isTrue()
        assertThat(spec.chronometerBaseWallMs).isEqualTo(500_000L - 30_000L)
        assertThat(spec.actions[0].label).isEqualTo(NURSING_TIMER_NOTIF_LABEL_PAUSE)
    }

    @Test
    fun pausedSpecIsStaticWithResumeAndFinishActions() {
        val spec = nursingTimerNotificationSpec(
            leftMs = 61_000L,
            rightMs = 59_000L,
            leftRunning = false,
            rightRunning = false,
            nowWallMs = 1_000_000L,
            smallIconResId = 7,
        )
        assertThat(spec.statusWord).isEqualTo("已暂停")
        assertThat(spec.title).isEqualTo("乐记 · 喂奶计时（已暂停）")
        assertThat(spec.text).isEqualTo("左 1:01 · 右 0:59")
        assertThat(spec.usesChronometer).isFalse()
        assertThat(spec.chronometerBaseWallMs).isNull()
        assertThat(spec.showWhen).isFalse()
        assertThat(spec.actions).hasSize(2)
        assertThat(spec.actions[0].label).isEqualTo(NURSING_TIMER_NOTIF_LABEL_RESUME)
        assertThat(spec.actions[1].label).isEqualTo(NURSING_TIMER_NOTIF_LABEL_FINISH)
        assertThat(spec.smallIconResId).isEqualTo(7)
    }

    @Test
    fun statusWordTableMatchesTicketWording() {
        assertThat(nursingTimerStatusWord(leftRunning = true, rightRunning = false))
            .isEqualTo("进行中 · 左")
        assertThat(nursingTimerStatusWord(leftRunning = false, rightRunning = true))
            .isEqualTo("进行中 · 右")
        assertThat(nursingTimerStatusWord(leftRunning = false, rightRunning = false))
            .isEqualTo("已暂停")
    }

    @Test
    fun chronometerBaseNeverNegativeForZeroTotals() {
        val spec = nursingTimerNotificationSpec(
            leftMs = 0L,
            rightMs = 0L,
            leftRunning = true,
            rightRunning = false,
            nowWallMs = 100L,
            smallIconResId = 1,
        )
        assertThat(spec.chronometerBaseWallMs).isEqualTo(100L)
    }
}
