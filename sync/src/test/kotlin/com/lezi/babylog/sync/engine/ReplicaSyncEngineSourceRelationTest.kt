package com.lezi.babylog.sync.engine

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.RecordEntity
import com.lezi.babylog.core.database.causal.SourceRelationEntity
import com.lezi.babylog.core.database.causal.SourceRelationMemberEntity
import com.lezi.babylog.core.database.causal.SourceRelationReason
import com.lezi.babylog.core.database.causal.SourceRelationRole
import com.lezi.babylog.sync.SyncTrigger
import com.lezi.babylog.sync.backend.PullResult
import com.lezi.babylog.sync.backend.PullSourceRelationSummary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ReplicaSyncEngineSourceRelationTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun sameVersionPullAppliesRelationSidecarAndInvalidatesObservers() = runTest {
        val session = joinedReplicaSession()
        val rig = ReplicaEngineRig(session)
        rig.backend.enableCausal = true
        val babyId = rig.babies.seed(
            localReplicaBaby().copy(
                syncDirty = false,
                familyAuthority = true,
                baseVersion = "version-baby",
            ),
        )
        rig.records.seed(
            RecordEntity(
                clientUuid = "record-source",
                babyId = babyId,
                type = "formula",
                timestamp = 210,
                note = "local-body-must-not-change",
                payloadJson = """{"amount_ml":90}""",
                schemaVersion = 2,
                updatedAt = 210,
                syncDirty = false,
                baseVersion = "version-record",
            ),
        )
        val emissions = mutableListOf<List<String>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            rig.sourceRelations.observeAllMembers()
                .take(2)
                .toList()
                .mapTo(emissions) { members -> members.map { it.recordClientUuid } }
        }
        rig.backend.nextPull = PullResult(
            entities = listOf(
                remoteReplicaRecord("record-source").copy(
                    versionId = "version-record",
                    sourceRelationSummary = PullSourceRelationSummary(
                        relationId = "relation-canonical",
                        role = SourceRelationRole.SOURCE,
                        peerIds = listOf("record-display"),
                    ),
                ),
            ),
            cursor = 1,
            generation = session.pullGeneration,
            hasMore = false,
        )

        rig.engine.synchronize(session, SyncTrigger.PullToRefresh)

        assertThat(rig.records.getByClientUuid("record-source")?.note)
            .isEqualTo("local-body-must-not-change")
        assertThat(rig.sourceRelations.listAllMembers())
            .containsExactly(
                com.lezi.babylog.core.database.causal.SourceRelationMemberEntity(
                    relationId = "relation-canonical",
                    recordClientUuid = "record-source",
                    role = SourceRelationRole.SOURCE,
                ),
            )
        assertThat(emissions).containsExactly(emptyList<String>(), listOf("record-source")).inOrder()
    }

    @Test
    fun pullSummaryUpgradesLegacyMarkerOnceAndThenFreezesTheClosedSet() = runTest {
        val sources = ReplicaEngineRig(joinedReplicaSession()).sourceRelations
        val relationId = "relation-legacy"
        sources.seedRaw(
            relation = SourceRelationEntity(
                relationId = relationId,
                displayClientUuid = "display",
                mediaRetained = true,
                reason = SourceRelationReason.PULL_SUMMARY,
                mutationId = "pull-$relationId",
                createdByMembershipId = "",
                createdAt = 1,
            ),
            seededMembers = listOf(
                SourceRelationMemberEntity(relationId, "display", SourceRelationRole.DISPLAY),
                SourceRelationMemberEntity(relationId, "source", SourceRelationRole.SOURCE),
            ),
        )

        sources.applyPullSummary(
            relationId = relationId,
            recordClientUuid = "source",
            role = SourceRelationRole.SOURCE,
            peerIds = listOf("display"),
            observedAt = 2,
        )

        assertThat(sources.get(relationId)?.mutationId).startsWith("pull-$relationId:")
        val drift = runCatching {
            sources.applyPullSummary(
                relationId = relationId,
                recordClientUuid = "source",
                role = SourceRelationRole.SOURCE,
                peerIds = listOf("display", "other"),
                observedAt = 3,
            )
        }
        assertThat(drift.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun pendingPullSummaryRejectsObservedMemberRoleDrift() = runTest {
        val sources = ReplicaEngineRig(joinedReplicaSession()).sourceRelations
        sources.applyPullSummary(
            relationId = "relation-role",
            recordClientUuid = "source-a",
            role = SourceRelationRole.SOURCE,
            peerIds = listOf("display", "source-b"),
            observedAt = 1,
        )

        val drift = runCatching {
            sources.applyPullSummary(
                relationId = "relation-role",
                recordClientUuid = "source-a",
                role = SourceRelationRole.DISPLAY,
                peerIds = listOf("display", "source-b"),
                observedAt = 2,
            )
        }

        assertThat(drift.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(sources.listMembers("relation-role"))
            .containsExactly(
                SourceRelationMemberEntity(
                    "relation-role",
                    "source-a",
                    SourceRelationRole.SOURCE,
                ),
            )
    }
}
