package com.lezi.babylog.domain.carelog

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.RecordEntity
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.domain.FakeRecordDao
import com.lezi.babylog.sync.NoOpSyncPort
import com.lezi.babylog.sync.SyncPort
import com.lezi.babylog.sync.backend.SourceRelationDeclareRequest
import com.lezi.babylog.sync.backend.SourceRelationResolveGroupRequest
import com.lezi.babylog.sync.backend.SourceRelationResult
import kotlinx.coroutines.runBlocking
import org.junit.Test

class SourceRelationCoordinatorTest {

    private val records = FakeRecordDao()
    private val sourceRelations = FakeSourceRelationDao()
    private val backend = RecordingSourceRelationPort()

    private fun coordinator(
        membershipId: String = "m-self",
        owner: Boolean = false,
    ) = SourceRelationCoordinator(
        recordDao = records,
        sourceRelationDao = sourceRelations,
        syncPort = backend,
        currentMembershipId = { membershipId },
        isFamilyOwner = { owner },
        nowMillis = { 1_000L },
    )

    @Test
    fun authorDeclare_persistsRelationWithoutTombstone() {
        runBlocking {
            records.upsert(entity("mine", "m-self", "v-mine"))
            records.upsert(entity("theirs", "m-other", "v-theirs"))
            backend.declareResult = SourceRelationResult(
                status = "accepted",
                relationId = "rel-1",
                displayClientUuid = "theirs",
                sourceClientUuids = listOf("mine"),
                mediaRetained = true,
            )
            val outcome = coordinator().declareEquivalent("mine", "theirs")
            assertThat(outcome).isInstanceOf(SourceRelationOutcome.Accepted::class.java)
            val accepted = outcome as SourceRelationOutcome.Accepted
            assertThat(accepted.displayClientUuid).isEqualTo("theirs")
            assertThat(sourceRelations.get("rel-1")).isNotNull()
            assertThat(sourceRelations.listMembers("rel-1").map { it.role to it.recordClientUuid })
                .containsExactly("display" to "theirs", "source" to "mine")
            assertThat(records.getByClientUuid("mine")!!.deletedAt).isNull()
            assertThat(records.getByClientUuid("theirs")!!.deletedAt).isNull()
            assertThat(
                sourceRelations.getDeclaration(backend.declareCalls.single().mutationId)!!.status,
            ).isEqualTo("consumed")
        }
    }

    @Test
    fun authorCannotDeclareOthersRecord() {
        runBlocking {
            records.upsert(entity("theirs", "m-other", "v-theirs"))
            records.upsert(entity("mine", "m-self", "v-mine"))
            val outcome = coordinator().declareEquivalent("theirs", "mine")
            assertThat(outcome).isInstanceOf(SourceRelationOutcome.Rejected::class.java)
            assertThat((outcome as SourceRelationOutcome.Rejected).code).isEqualTo("forbidden")
            assertThat(backend.declareCalls).isEmpty()
        }
    }

    @Test
    fun nonOwnerCannotResolveGroup() {
        runBlocking {
            records.upsert(entity("a", "m1", "v-a"))
            records.upsert(entity("b", "m2", "v-b"))
            val outcome = coordinator(owner = false).resolveGroupAsOwner(
                memberClientUuids = listOf("a", "b"),
                displayClientUuid = "a",
            )
            assertThat(outcome).isInstanceOf(SourceRelationOutcome.Rejected::class.java)
            assertThat((outcome as SourceRelationOutcome.Rejected).code).isEqualTo("forbidden")
            assertThat(backend.resolveCalls).isEmpty()
        }
    }

    @Test
    fun ownerResolve_persistsFullGroupSourceRelation() {
        runBlocking {
            records.upsert(entity("a", "m1", "v-a"))
            records.upsert(entity("b", "m2", "v-b"))
            backend.resolveResult = SourceRelationResult(
                status = "accepted",
                relationId = "rel-owner",
                displayClientUuid = "a",
                sourceClientUuids = listOf("b"),
                mediaRetained = true,
            )
            val outcome = coordinator(owner = true).resolveGroupAsOwner(
                memberClientUuids = listOf("b", "a"),
                displayClientUuid = "a",
            )
            assertThat(outcome).isInstanceOf(SourceRelationOutcome.Accepted::class.java)
            assertThat(backend.resolveCalls.single().displayClientUuid).isEqualTo("a")
            assertThat(backend.resolveCalls.single().memberClientUuids).containsExactly("a", "b")
            assertThat(
                sourceRelations.listMembers("rel-owner").map { it.recordClientUuid to it.role },
            ).containsExactly("a" to "display", "b" to "source")
        }
    }

    @Test
    fun casMismatch_surfacesLatestVersionsWithoutWritingRelation() {
        runBlocking {
            records.upsert(entity("mine", "m-self", "v-mine"))
            records.upsert(entity("theirs", "m-other", "v-theirs"))
            backend.declareResult = SourceRelationResult(
                status = "cas_mismatch",
                code = "cas_mismatch",
                latestVersions = mapOf("theirs" to "v-new"),
            )
            val outcome = coordinator().declareEquivalent("mine", "theirs")
            assertThat(outcome).isInstanceOf(SourceRelationOutcome.CasMismatch::class.java)
            assertThat((outcome as SourceRelationOutcome.CasMismatch).latestVersions)
                .containsEntry("theirs", "v-new")
            assertThat(sourceRelations.listAll()).isEmpty()
            assertThat(
                sourceRelations.getDeclaration(backend.declareCalls.single().mutationId)!!.status,
            ).isEqualTo("superseded")
        }
    }

    private fun entity(uuid: String, membership: String, base: String): RecordEntity =
        RecordEntity(
            id = 0,
            clientUuid = uuid,
            babyId = 1,
            type = RecordType.FORMULA.key,
            timestamp = 1_000L,
            endTimestamp = null,
            note = null,
            payloadJson = """{"amount_ml":100}""",
            schemaVersion = 2,
            updatedAt = 1_000L,
            deletedAt = null,
            syncDirty = false,
            createdByMembershipId = membership,
            baseVersion = base,
        )
}

private class RecordingSourceRelationPort : SyncPort by NoOpSyncPort() {
    // delegate
    var declareResult: SourceRelationResult = SourceRelationResult(status = "rejected")
    var resolveResult: SourceRelationResult = SourceRelationResult(status = "rejected")
    val declareCalls = mutableListOf<SourceRelationDeclareRequest>()
    val resolveCalls = mutableListOf<SourceRelationResolveGroupRequest>()

    override suspend fun declareSourceRelation(
        request: SourceRelationDeclareRequest,
    ): SourceRelationResult {
        declareCalls += request
        return declareResult
    }

    override suspend fun resolveSourceRelationGroup(
        request: SourceRelationResolveGroupRequest,
    ): SourceRelationResult {
        resolveCalls += request
        return resolveResult
    }
}
