package com.lezi.babylog.domain.carelog

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.RecordEntity
import com.lezi.babylog.core.database.DatabaseTransactionRunner
import com.lezi.babylog.sync.backend.SyncBackend
import com.lezi.babylog.sync.backend.CurrentSourceRelationsRequest
import com.lezi.babylog.sync.backend.CurrentSourceRelationsSnapshot
import com.lezi.babylog.sync.backend.CurrentSourceRelationRecord
import com.lezi.babylog.sync.backend.CurrentSourceRelationGroup
import com.lezi.babylog.sync.session.SyncSession
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.sync.session.TrustedEndpointProfile
import com.lezi.babylog.sync.sourcerelation.SourceRelationCommandOwner
import java.lang.reflect.Proxy
import java.io.IOException
import com.lezi.babylog.core.database.causal.AUTO_NEAR_NEIGHBOR_MUTATION_PREFIX
import com.lezi.babylog.core.database.causal.SourceRelationEntity
import com.lezi.babylog.core.database.causal.SourceRelationMemberEntity
import com.lezi.babylog.core.database.causal.SourceRelationReason
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.domain.FakeRecordDao
import com.lezi.babylog.sync.NoOpSyncPort
import com.lezi.babylog.sync.SyncPort
import com.lezi.babylog.sync.backend.SourceRelationDeclareRequest
import com.lezi.babylog.sync.backend.SourceRelationResolveGroupRequest
import com.lezi.babylog.core.database.causal.SourceRelationDeclarationStatus
import com.lezi.babylog.sync.backend.SourceRelationResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Test

class SourceRelationCoordinatorTest {

    private val records = FakeRecordDao()
    private val sourceRelations = FakeSourceRelationDao()
    private val backend = RecordingSourceRelationPort(sourceRelations)

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
    fun authorDeclareReturnsSyncSettledRelationWithoutDomainRewrite() {
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
            assertThat(sourceRelations.get("rel-1")?.createdAt).isEqualTo(123L)
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
    fun ownerResolveReturnsSyncSettledGroupWithoutDomainRewrite() {
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
            assertThat(sourceRelations.get("rel-owner")?.createdAt).isEqualTo(123L)
            assertThat(backend.resolveCalls.single().displayClientUuid).isEqualTo("a")
            assertThat(backend.resolveCalls.single().memberClientUuids).containsExactly("a", "b")
            assertThat(
                sourceRelations.listMembers("rel-owner").map { it.recordClientUuid to it.role },
            ).containsExactly("a" to "display", "b" to "source")
        }
    }

    @Test
    fun cancelledDeclareDoesNotPersistFailedDeclaration() {
        runBlocking {
            records.upsert(entity("mine", "m-self", "v-mine"))
            records.upsert(entity("theirs", "m-other", "v-theirs"))
            backend.declareFailure = CancellationException("stopped")
            val thrown = runCatching {
                coordinator().declareEquivalent("mine", "theirs")
            }.exceptionOrNull()
            assertThat(thrown).isInstanceOf(CancellationException::class.java)
            val declaration = sourceRelations.getDeclaration(
                backend.declareCalls.single().mutationId,
            )
            assertThat(declaration).isNotNull()
            assertThat(declaration!!.status).isEqualTo(SourceRelationDeclarationStatus.PENDING)
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

    @Test
    fun explicitRepeatAfterUnknownTransportUsesOriginalSyncOperation() = runBlocking {
        records.upsert(entity("mine", "m-self", "v-mine"))
        records.upsert(entity("theirs", "m-other", "v-theirs"))
        backend.declareFailure = IOException("response lost")
        val initial = coordinator().declareEquivalent("mine", "theirs") as SourceRelationOutcome.Rejected
        assertThat(initial.code).isEqualTo("transport")
        assertThat(initial.message).contains("原家庭服务器")
        val original = backend.declareCalls.single()
        assertThat(sourceRelations.getDeclaration(original.mutationId)?.status).isEqualTo("pending")
        backend.declareFailure = null
        backend.declareResult = SourceRelationResult(
            "accepted", "relation-retry", "theirs", listOf("mine"), true,
        )
        assertThat(coordinator().declareEquivalent("mine", "theirs"))
            .isInstanceOf(SourceRelationOutcome.Accepted::class.java)
        assertThat(backend.declareCalls).containsExactly(original, original).inOrder()
        assertThat(sourceRelations.get("relation-retry")?.mutationId).isEqualTo(original.mutationId)
        assertThat(sourceRelations.getDeclaration(original.mutationId)?.status).isEqualTo("consumed")
    }

    @Test
    fun confirmedRemoteChoiceWithUnavailableCurrentReadSurfacesRefreshNeeded() = runBlocking {
        records.upsert(entity("mine", "m-self", "v-mine"))
        records.upsert(entity("theirs", "m-other", "v-theirs"))
        backend.declareResult = SourceRelationResult("accepted", "relation", "theirs", listOf("mine"), true)
        backend.readFailure = UnsupportedOperationException("old server")
        val outcome = coordinator().declareEquivalent("mine", "theirs")
        assertThat(outcome).isInstanceOf(SourceRelationOutcome.ConfirmedRefreshRequired::class.java)
        assertThat((outcome as SourceRelationOutcome.ConfirmedRefreshRequired).message).contains("更新原服务器")
        assertThat(sourceRelations.listAll()).isEmpty()
        backend.readFailure = null
        assertThat(coordinator().declareEquivalent("mine", "theirs"))
            .isInstanceOf(SourceRelationOutcome.Accepted::class.java)
        assertThat(backend.declareCalls).hasSize(1)
    }

    @Test
    fun autoAlignedDisplay_isDetectedFromDaoJournalProjection() {
        runBlocking {
            sourceRelations.applyPullSummary(
                relationId = "rel-auto",
                recordClientUuid = "display",
                role = com.lezi.babylog.core.database.causal.SourceRelationRole.DISPLAY,
                peerIds = listOf("source"),
                observedAt = 1,
                autoAligned = true,
            )
            sourceRelations.applyPullSummary(
                relationId = "rel-auto",
                recordClientUuid = "source",
                role = com.lezi.babylog.core.database.causal.SourceRelationRole.SOURCE,
                peerIds = listOf("display"),
                observedAt = 1,
                autoAligned = true,
            )
            assertThat(coordinator().autoAlignedDisplayClientUuids()).containsExactly("display")
            assertThat(coordinator().sourceRoleClientUuids()).containsExactly("source")
        }
    }

    @Test
    fun manualResolveSupersedesAutoBadgeForTheChangedDisplay() {
        runBlocking {
            records.upsert(entity("display", "m1", "v-display"))
            records.upsert(entity("source", "m2", "v-source"))
            sourceRelations.applyPullSummary(
                relationId = "rel-auto",
                recordClientUuid = "source",
                role = com.lezi.babylog.core.database.causal.SourceRelationRole.DISPLAY,
                peerIds = listOf("display"),
                observedAt = 1,
                autoAligned = true,
            )
            assertThat(coordinator(owner = true).autoAlignedDisplayClientUuids())
                .containsExactly("source")

            backend.resolveResult = SourceRelationResult(
                status = "accepted",
                relationId = "rel-owner",
                displayClientUuid = "display",
                sourceClientUuids = listOf("source"),
                mediaRetained = true,
            )
            val outcome = coordinator(owner = true).resolveGroupAsOwner(
                memberClientUuids = listOf("display", "source"),
                displayClientUuid = "display",
            )
            assertThat(outcome).isInstanceOf(SourceRelationOutcome.Accepted::class.java)

            assertThat(coordinator(owner = true).autoAlignedDisplayClientUuids()).isEmpty()
        }
    }

    @Test
    fun canonicalAutoMutationOnNonPullRelationStillShowsBadge() {
        runBlocking {
            val prefix = AUTO_NEAR_NEIGHBOR_MUTATION_PREFIX
            sourceRelations.applyOwnerGroupResolution(
                relation = SourceRelationEntity(
                    relationId = "rel-server-auto",
                    displayClientUuid = "display",
                    mediaRetained = true,
                    reason = SourceRelationReason.OWNER_GROUP_RESOLVE,
                    mutationId = "${prefix}abc123",
                    createdByMembershipId = "",
                    createdAt = 1,
                ),
                members = listOf(
                    SourceRelationMemberEntity(
                        "rel-server-auto",
                        "display",
                        com.lezi.babylog.core.database.causal.SourceRelationRole.DISPLAY,
                    ),
                    SourceRelationMemberEntity(
                        "rel-server-auto",
                        "source",
                        com.lezi.babylog.core.database.causal.SourceRelationRole.SOURCE,
                    ),
                ),
            )
            assertThat(coordinator().autoAlignedDisplayClientUuids()).containsExactly("display")
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

/** The production sync command owner settles rows; only the remote transport is faked. */
private class RecordingSourceRelationPort(
    sourceRelations: FakeSourceRelationDao,
) : SyncPort by NoOpSyncPort() {
    var declareResult: SourceRelationResult = SourceRelationResult(status = "rejected", code = "invalid")
    var resolveResult: SourceRelationResult = SourceRelationResult(status = "rejected", code = "invalid")
    var declareFailure: Throwable? = null
    var readFailure: Throwable? = null
    var currentResult: SourceRelationResult? = null
    val declareCalls = mutableListOf<SourceRelationDeclareRequest>()
    val resolveCalls = mutableListOf<SourceRelationResolveGroupRequest>()
    private val session = SyncSession(
        familyId = "family", membershipId = "m-self", deviceId = "device", role = FamilyRole.Owner,
        pullGeneration = "generation", accessToken = "test-token",
        serverHost = "original.example", serverPort = 443, serverScheme = "https",
    )
    // A synchronous test response is legal for the suspend transport boundary. Any unrelated
    // transport call fails loudly; the production owner and its DAO writes remain real.
    private val transport = Proxy.newProxyInstance(
        SyncBackend::class.java.classLoader,
        arrayOf(SyncBackend::class.java),
    ) { _, method, args ->
        when (method.name) {
            "declareSourceRelation" -> {
                declareCalls += args!![1] as SourceRelationDeclareRequest
                declareFailure?.let { throw it }
                currentResult = declareResult
                declareResult
            }
            "resolveSourceRelationGroup" -> {
                resolveCalls += args!![1] as SourceRelationResolveGroupRequest
                currentResult = resolveResult
                resolveResult
            }
            "readCurrentSourceRelations" -> {
                readFailure?.let { throw it }
                val request = args!![1] as CurrentSourceRelationsRequest
                val result = requireNotNull(currentResult)
                val group = CurrentSourceRelationGroup(requireNotNull(result.relationId),
                    requireNotNull(result.displayClientUuid), result.sourceClientUuids.sorted())
                CurrentSourceRelationsSnapshot(request.familyId, request.generation, 100,
                    request.recordClientUuids, group.memberClientUuids.map {
                        CurrentSourceRelationRecord(it, "live", group.relationId)
                    }, listOf(group))
            }
            else -> error("Unexpected transport call: ${method.name}")
        }
    } as SyncBackend
    private val owner = SourceRelationCommandOwner(
        sourceRelationDao = sourceRelations,
        journalDao = FakeConflictSnapshotCacheDao(),
        transactionRunner = object : DatabaseTransactionRunner {
            override suspend fun <T> run(block: suspend () -> T): T = block()
        },
        backend = transport,
        currentSession = { session },
        currentEndpoint = { TrustedEndpointProfile.systemPki(session.baseUrl) },
        nowMillis = { 123L },
    )

    override suspend fun declareSourceRelation(request: SourceRelationDeclareRequest): SourceRelationResult =
        owner.declare(request)

    override suspend fun resolveSourceRelationGroup(request: SourceRelationResolveGroupRequest): SourceRelationResult =
        owner.resolveGroup(request)
}
