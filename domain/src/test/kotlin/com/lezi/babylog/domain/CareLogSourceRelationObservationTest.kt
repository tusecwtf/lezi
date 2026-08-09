package com.lezi.babylog.domain

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.causal.SourceRelationRole
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

class CareLogSourceRelationObservationTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun relationOnlyDeltaReemitsOrdinaryProjectionInputs() = runTest {
        val fakes = Fakes()
        val careLog = fakes.careLog()
        val sourceRoleEmissions = mutableListOf<Set<String>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            careLog.observeSourceRoleClientUuids()
                .take(2)
                .toList(sourceRoleEmissions)
        }

        fakes.sourceRelations.applyPullSummary(
            relationId = "relation-observed",
            recordClientUuid = "record-source",
            role = SourceRelationRole.SOURCE,
            peerIds = listOf("record-display"),
            observedAt = 10,
        )

        assertThat(sourceRoleEmissions)
            .containsExactly(emptySet<String>(), setOf("record-source"))
            .inOrder()
    }
}
