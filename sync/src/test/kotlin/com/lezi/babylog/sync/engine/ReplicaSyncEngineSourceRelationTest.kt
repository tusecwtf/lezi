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
import java.security.MessageDigest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Verbatim port of the 0.4.7 release reader (d7d1650d
 * `CausalDaos.applyPullSummary`): the mutation-id guard and member-set
 * fingerprint a rolled-back 0.4.7 client applies to one arriving pull summary
 * against the stored row. Frozen on purpose — do not modernize.
 */
private object LegacyZeroFourSevenPullSummaryReader {
    fun memberSetFingerprint(memberIds: Set<String>): String {
        val canonical = memberIds.sorted().joinToString(separator = "") { memberId ->
            val bytes = memberId.toByteArray(Charsets.UTF_8)
            "${bytes.size}:$memberId"
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }
    }

    fun requireConsumable(
        relationId: String,
        storedMutationId: String,
        storedDisplayClientUuid: String,
        storedMemberIds: Set<String>,
        closedMemberIds: Set<String>,
    ) {
        val pullMutationId = "pull-$relationId:${memberSetFingerprint(closedMemberIds)}"
        when (storedMutationId) {
            pullMutationId -> Unit
            "pull-$relationId" -> if (storedDisplayClientUuid.isNotBlank()) {
                check(storedMemberIds == closedMemberIds) { "source relation peer set drift" }
            }
            else -> throw IllegalArgumentException("source relation peer set drift")
        }
    }
}

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
    fun unresolvedRecordDoesNotCommitItsRelationSidecar() = runTest {
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
                clientUuid = "record-dangling",
                babyId = babyId,
                type = "formula",
                timestamp = 210,
                payloadJson = """{"amount_ml":90}""",
                schemaVersion = 2,
                updatedAt = 210,
                syncDirty = false,
                baseVersion = "version-dangling",
            ),
        )
        // The pulled record references a baby that is not local and carries a
        // SOURCE-role summary: it must stay unresolved WITHOUT committing the
        // sidecar, or the skipped record would already be hidden as source.
        rig.backend.nextPull = PullResult(
            entities = listOf(
                remoteReplicaRecord("record-dangling").copy(
                    payloadJson = remoteReplicaRecord("record-dangling").payloadJson
                        .replace("baby-local", "baby-not-local"),
                    sourceRelationSummary = PullSourceRelationSummary(
                        relationId = "relation-dangling",
                        role = SourceRelationRole.SOURCE,
                        peerIds = listOf("record-display"),
                    ),
                ),
            ),
            cursor = 4,
            generation = session.pullGeneration,
            hasMore = false,
        )

        rig.engine.synchronize(session, SyncTrigger.PullToRefresh)

        assertThat(rig.sourceRelations.listAllMembers()).isEmpty()
        assertThat(rig.preferences.current().pullCursor).isEqualTo(session.pullCursor)
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

        val frozen = sources.get(relationId)?.mutationId
        assertThat(frozen)
            .isEqualTo(
                "pull-$relationId:" +
                    LegacyZeroFourSevenPullSummaryReader.memberSetFingerprint(
                        setOf("display", "source"),
                    ),
            )
        LegacyZeroFourSevenPullSummaryReader.requireConsumable(
            relationId = relationId,
            storedMutationId = frozen!!,
            storedDisplayClientUuid = "display",
            storedMemberIds = setOf("display", "source"),
            closedMemberIds = setOf("display", "source"),
        )
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

    @Test
    fun autoAlignedPullStaysConsumableByTheFrozenZeroFourSevenReader() = runTest {
        val sources = ReplicaEngineRig(joinedReplicaSession()).sourceRelations
        val relationId = "relation-auto"
        val closedMemberIds = setOf("display", "source")

        sources.applyPullSummary(
            relationId = relationId,
            recordClientUuid = "display",
            role = SourceRelationRole.DISPLAY,
            peerIds = listOf("source"),
            observedAt = 1,
            autoAligned = true,
        )

        val mutationId = sources.get(relationId)?.mutationId
        assertThat(mutationId)
            .isEqualTo(
                "pull-$relationId:" +
                    LegacyZeroFourSevenPullSummaryReader.memberSetFingerprint(closedMemberIds),
            )
        assertThat(sources.listAutoAlignedDisplayClientUuids()).containsExactly("display")

        LegacyZeroFourSevenPullSummaryReader.requireConsumable(
            relationId = relationId,
            storedMutationId = mutationId!!,
            storedDisplayClientUuid = "display",
            storedMemberIds = closedMemberIds,
            closedMemberIds = closedMemberIds,
        )
        sources.applyPullSummary(
            relationId = relationId,
            recordClientUuid = "source",
            role = SourceRelationRole.SOURCE,
            peerIds = listOf("display"),
            observedAt = 2,
            autoAligned = true,
        )
        assertThat(sources.get(relationId)?.mutationId).isEqualTo(mutationId)
        assertThat(sources.listAutoAlignedDisplayClientUuids()).containsExactly("display")

        val drift = runCatching {
            sources.applyPullSummary(
                relationId = relationId,
                recordClientUuid = "display",
                role = SourceRelationRole.DISPLAY,
                peerIds = listOf("source", "other"),
                observedAt = 3,
            )
        }
        assertThat(drift.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun autoAlignedSourceFirstHalfEdgeRejectsDriftAndFreezesWhenDisplayArrives() = runTest {
        val sources = ReplicaEngineRig(joinedReplicaSession()).sourceRelations
        val relationId = "relation-half"
        val closedMemberIds = setOf("display", "source", "source-b")

        sources.applyPullSummary(
            relationId = relationId,
            recordClientUuid = "source",
            role = SourceRelationRole.SOURCE,
            peerIds = listOf("display", "source-b"),
            observedAt = 1,
            autoAligned = true,
        )

        // From the first source-only summary the id carries the frozen closed
        // set in the 0.4.7 format; the stored members stay the observed subset.
        val halfEdge = sources.get(relationId)?.mutationId
        assertThat(halfEdge)
            .isEqualTo(
                "pull-$relationId:" +
                    LegacyZeroFourSevenPullSummaryReader.memberSetFingerprint(closedMemberIds),
            )
        LegacyZeroFourSevenPullSummaryReader.requireConsumable(
            relationId = relationId,
            storedMutationId = halfEdge!!,
            storedDisplayClientUuid = "",
            storedMemberIds = setOf("source"),
            closedMemberIds = closedMemberIds,
        )
        assertThat(sources.listAutoAlignedDisplayClientUuids()).isEmpty()

        val drift = runCatching {
            sources.applyPullSummary(
                relationId = relationId,
                recordClientUuid = "source-x",
                role = SourceRelationRole.SOURCE,
                peerIds = listOf("display"),
                observedAt = 2,
            )
        }
        assertThat(drift.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)

        sources.applyPullSummary(
            relationId = relationId,
            recordClientUuid = "display",
            role = SourceRelationRole.DISPLAY,
            peerIds = listOf("source", "source-b"),
            observedAt = 3,
            autoAligned = true,
        )

        val frozen = sources.get(relationId)?.mutationId
        assertThat(frozen).isEqualTo(halfEdge)
        LegacyZeroFourSevenPullSummaryReader.requireConsumable(
            relationId = relationId,
            storedMutationId = frozen!!,
            storedDisplayClientUuid = "display",
            storedMemberIds = closedMemberIds,
            closedMemberIds = closedMemberIds,
        )
        assertThat(sources.listAutoAlignedDisplayClientUuids()).containsExactly("display")
    }

    @Test
    fun pullWithoutAutoAlignedFreezesTheSameZeroFourSevenFormat() = runTest {
        val sources = ReplicaEngineRig(joinedReplicaSession()).sourceRelations
        sources.applyPullSummary(
            relationId = "relation-manual",
            recordClientUuid = "display",
            role = SourceRelationRole.DISPLAY,
            peerIds = listOf("source"),
            observedAt = 1,
        )
        assertThat(sources.get("relation-manual")?.mutationId)
            .isEqualTo(
                "pull-relation-manual:" +
                    LegacyZeroFourSevenPullSummaryReader.memberSetFingerprint(
                        setOf("display", "source"),
                    ),
            )
        assertThat(sources.listAutoAlignedDisplayClientUuids()).isEmpty()
    }
}
