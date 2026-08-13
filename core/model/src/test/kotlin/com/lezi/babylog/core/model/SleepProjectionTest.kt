package com.lezi.babylog.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SleepProjectionTest {
    @Test
    fun openSleep_withoutWakes_isOpenAndLatestIsWakeTarget() {
        val projection = projectSleepInterval(
            sleepClientUuid = "sleep-a",
            startTimestamp = 1_000L,
        )
        assertThat(projection.isOpen).isTrue()
        assertThat(projection.endTimestamp).isNull()
        assertThat(projection.endSource).isEqualTo(SleepEndSource.OPEN)
        assertThat(projection.isProvisional).isFalse()
        assertThat(isWakeShortcutTarget(projection)).isTrue()
    }

    @Test
    fun olderOpenAmongPeers_isOverlapPending_notWakeTarget() {
        val older = projectSleepInterval(
            sleepClientUuid = "sleep-old",
            startTimestamp = 1_000L,
            peerOpenSleepStarts = listOf("sleep-new" to 2_000L),
        )
        assertThat(older.isOpen).isTrue()
        assertThat(older.isOverlapPending).isTrue()
        assertThat(isWakeShortcutTarget(older)).isFalse()

        val newer = projectSleepInterval(
            sleepClientUuid = "sleep-new",
            startTimestamp = 2_000L,
            peerOpenSleepStarts = listOf("sleep-old" to 1_000L),
        )
        assertThat(newer.isOverlapPending).isFalse()
        assertThat(isWakeShortcutTarget(newer)).isTrue()
    }

    @Test
    fun multipleLegalWakes_provisionalUsesEarliest_andKeepsAllVisible() {
        val early = WakeObservationFact("wake-early", wakeTimestamp = 3_000L, observerMembershipId = "m-a")
        val late = WakeObservationFact("wake-late", wakeTimestamp = 5_000L, observerMembershipId = "m-b")
        val withdrawn = WakeObservationFact(
            "wake-out",
            wakeTimestamp = 2_500L,
            withdrawn = true,
            observerMembershipId = "m-c",
        )
        val preStart = WakeObservationFact("wake-pre", wakeTimestamp = 500L, observerMembershipId = "m-d")

        val projection = projectSleepInterval(
            sleepClientUuid = "sleep-a",
            startTimestamp = 1_000L,
            observations = listOf(late, early, withdrawn, preStart),
        )

        assertThat(projection.endSource).isEqualTo(SleepEndSource.PROVISIONAL)
        assertThat(projection.isProvisional).isTrue()
        assertThat(projection.endTimestamp).isEqualTo(3_000L)
        assertThat(projection.endObservationClientUuid).isEqualTo("wake-early")
        assertThat(projection.visibleObservations.map { it.clientUuid })
            .containsExactly("wake-early", "wake-late")
            .inOrder()
        assertThat(projection.isOpen).isFalse()
        assertThat(isWakeShortcutTarget(projection)).isFalse()
    }

    @Test
    fun effectiveObservation_overridesProvisional_andDoesNotDropOthers() {
        val early = WakeObservationFact("wake-early", 3_000L)
        val late = WakeObservationFact("wake-late", 5_000L)
        val projection = projectSleepInterval(
            sleepClientUuid = "sleep-a",
            startTimestamp = 1_000L,
            effectiveWakeObservationClientUuid = "wake-late",
            observations = listOf(early, late),
        )
        assertThat(projection.endSource).isEqualTo(SleepEndSource.EFFECTIVE)
        assertThat(projection.isProvisional).isFalse()
        assertThat(projection.endTimestamp).isEqualTo(5_000L)
        assertThat(projection.endObservationClientUuid).isEqualTo("wake-late")
        assertThat(projection.visibleObservations).hasSize(2)
    }

    @Test
    fun withdrawnEffective_fallsBackToProvisionalOrOpen() {
        val withdrawn = WakeObservationFact("wake-a", 3_000L, withdrawn = true)
        val other = WakeObservationFact("wake-b", 4_000L)
        val projection = projectSleepInterval(
            sleepClientUuid = "sleep-a",
            startTimestamp = 1_000L,
            effectiveWakeObservationClientUuid = "wake-a",
            observations = listOf(withdrawn, other),
        )
        assertThat(projection.endSource).isEqualTo(SleepEndSource.PROVISIONAL)
        assertThat(projection.endObservationClientUuid).isEqualTo("wake-b")
        assertThat(projection.endTimestamp).isEqualTo(4_000L)
    }

    @Test
    fun legacyEnd_usedOnlyWhenNoLegalWakes() {
        val withLegacy = projectSleepInterval(
            sleepClientUuid = "sleep-a",
            startTimestamp = 1_000L,
            legacyEndTimestamp = 9_000L,
        )
        assertThat(withLegacy.endSource).isEqualTo(SleepEndSource.LEGACY_END)
        assertThat(withLegacy.endTimestamp).isEqualTo(9_000L)

        val wakeWins = projectSleepInterval(
            sleepClientUuid = "sleep-a",
            startTimestamp = 1_000L,
            legacyEndTimestamp = 9_000L,
            observations = listOf(WakeObservationFact("wake-a", 3_000L)),
        )
        assertThat(wakeWins.endSource).isEqualTo(SleepEndSource.PROVISIONAL)
        assertThat(wakeWins.endTimestamp).isEqualTo(3_000L)
    }

    @Test
    fun validateWakeTimestamp_rejectsPreStart_allowsEqualityPerWire() {
        assertThat(validateWakeTimestamp(1_000L, 999L)).isEqualTo("醒来时间不能早于入睡时间")
        // Wire §4.5: wake_timestamp >= sleep.timestamp (equality legal).
        assertThat(validateWakeTimestamp(1_000L, 1_000L)).isNull()
        assertThat(validateWakeTimestamp(1_000L, 1_001L)).isNull()
    }
}
