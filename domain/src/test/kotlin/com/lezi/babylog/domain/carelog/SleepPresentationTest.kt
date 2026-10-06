package com.lezi.babylog.domain.carelog

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.model.SleepEndSource
import com.lezi.babylog.core.model.SleepIntervalProjection
import com.lezi.babylog.core.model.WakeObservationFact
import com.lezi.babylog.core.model.projectSleepInterval
import org.junit.Test

class SleepPresentationTest {
    @Test
    fun provisionalAndOverlapBadges() {
        val provisional = projectSleepInterval(
            sleepClientUuid = "s",
            startTimestamp = 1_000L,
            observations = listOf(WakeObservationFact("w", 2_000L)),
        )
        assertThat(SleepPresentation.endBadge(provisional))
            .isEqualTo(SleepPresentation.PROVISIONAL_BADGE)

        val overlap = projectSleepInterval(
            sleepClientUuid = "old",
            startTimestamp = 1_000L,
            peerOpenSleepStarts = listOf("new" to 2_000L),
        )
        assertThat(SleepPresentation.endBadge(overlap))
            .isEqualTo(SleepPresentation.OVERLAP_PENDING_BADGE)
    }

    @Test
    fun conflictCardSummary_andObservationLabels() {
        assertThat(SleepPresentation.conflictCardSummary(true, 2))
            .isEqualTo("有冲突 · 2 项差异")
        val interval = SleepIntervalProjection(
            sleepClientUuid = "s",
            startTimestamp = 1_000L,
            endTimestamp = 2_000L,
            endSource = SleepEndSource.PROVISIONAL,
            endObservationClientUuid = "w1",
            isProvisional = true,
        )
        val obs = WakeObservation(
            clientUuid = "w1",
            sleepRecordClientUuid = "s",
            wakeTimestamp = 2_000L,
            observerMembershipId = "m-a",
            updatedAt = 2_000L,
        )
        assertThat(SleepPresentation.observationListLabel(obs, interval))
            .contains(SleepPresentation.PROVISIONAL_BADGE)
    }
}
