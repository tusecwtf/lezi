package com.lezi.babylog.sync.engine

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.core.database.RecordEntity
import com.lezi.babylog.core.database.causal.conflictSnapshotStageCacheKey
import com.lezi.babylog.sync.MemoryConflictSnapshotCacheDao
import com.lezi.babylog.sync.MemoryConflictSummaryDao
import com.lezi.babylog.sync.SyncTrigger
import com.lezi.babylog.sync.backend.CausalCommitRejectedException
import com.lezi.babylog.sync.backend.ConflictResolutionChoice
import com.lezi.babylog.sync.backend.ConflictResolveRequest
import com.lezi.babylog.sync.backend.ConflictResolveResult
import com.lezi.babylog.sync.backend.FakeSyncBackend
import com.lezi.babylog.sync.backend.SyncBackend
import com.lezi.babylog.sync.conflict.ConflictSnapshot
import com.lezi.babylog.sync.conflict.ConflictSnapshotCodec
import com.lezi.babylog.sync.conflict.ConflictSnapshotPageRequest
import com.lezi.babylog.sync.conflict.ConflictSnapshotProjection
import com.lezi.babylog.sync.conflict.FetchedConflictSnapshotPage
import com.lezi.babylog.sync.media.CausalMediaRole
import com.lezi.babylog.sync.media.FileImmutableMediaSpool
import com.lezi.babylog.sync.media.ImmutableMediaSpool
import com.lezi.babylog.sync.media.ImmutableMediaSpoolGroup
import com.lezi.babylog.sync.media.ImmutableMediaSpoolItem
import com.lezi.babylog.sync.media.ImmutableMediaSpoolRecovery
import com.lezi.babylog.sync.media.ImmutableMediaSpoolSource
import com.lezi.babylog.sync.session.SyncSession
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test

/** H41 public-seam acceptance: deterministic restart recovery at four durable kill points. */
class ProcessDeathRecoveryAcceptanceTest {
    private val ownedDirectories = mutableListOf<File>()

    @Test
    fun seedAndKillPointTableArePinned() {
        assertThat(H41_SEED).isEqualTo(0x41_20_41L)
        assertThat(H41_KILL_POINTS).containsExactly(
            "envelope-durable-before-http",
            "spool-promote-before-room-manifest",
            "page-transaction-before-complete-promote",
            "resolution-durable-before-response",
        ).inOrder()
    }

    @After
    fun cleanup() {
        ownedDirectories.forEach { directory ->
            directory.walkBottomUp().forEach(File::delete)
        }
    }

    @Test
    fun c1_envelopeDurableBeforeHttp_restartReplaysOneMutationWithoutVersionDrift() = runTest {
        val session = joinedReplicaSession().copy(pullCursor = 41)
        val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
        val babyId = rig.babies.seed(
            localReplicaBaby().copy(syncDirty = false, familyAuthority = true),
        )
        val recordUuid = "00000000-0000-4000-8000-000000004101"
        rig.records.seed(
            RecordEntity(
                clientUuid = recordUuid,
                babyId = babyId,
                type = "formula",
                timestamp = 4101,
                payloadJson = """{"amount_ml":60}""",
                schemaVersion = 2,
                updatedAt = 4101,
                syncDirty = true,
                baseVersion = "v-base-41",
            ),
        )
        rig.backend.nextCausalCommitFailure = CausalCommitRejectedException(
            mutationId = null,
            code = "h41_http_before_response",
        )

        assertThat(runCatching {
            rig.engine.synchronize(session, SyncTrigger.LocalWrite)
        }.exceptionOrNull()).isInstanceOf(CausalCommitRejectedException::class.java)

        val envelope = requireNotNull(rig.conflictDetails.getFrozenMutation("record", recordUuid))
        val firstMutation = decodeFrozenCommitEnvelope(envelope.payloadJson).mutation
        assertThat(firstMutation.media).isEmpty()
        assertThat(rig.preferences.current().pullCursor).isEqualTo(41)

        rig.newEngine().synchronize(session, SyncTrigger.LocalWrite)

        assertThat(rig.backend.causalCommittedUnits).hasSize(2)
        assertThat(rig.backend.causalCommittedUnits[0]).containsExactly(firstMutation)
        assertThat(rig.backend.causalCommittedUnits[1]).containsExactly(firstMutation)
        assertThat(rig.records.getByClientUuid(recordUuid)?.syncDirty).isFalse()
        assertThat(rig.records.getByClientUuid(recordUuid)?.baseVersion)
            .isEqualTo("v-${firstMutation.mutationId.take(8)}")
        assertThat(rig.conflictDetails.getFrozenMutation("record", recordUuid)).isNull()
        assertThat(rig.preferences.current().pullCursor).isEqualTo(41)
    }

    @Test
    fun c2_spoolPromoteBeforeRoomManifest_restartRebindsSidecarWithoutSourceReopen() = runTest {
        val session = joinedReplicaSession().copy(pullCursor = 42)
        val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
        val babyId = rig.babies.seed(
            localReplicaBaby().copy(syncDirty = false, familyAuthority = true),
        )
        val recordUuid = "00000000-0000-4000-8000-000000004102"
        val mediaUuid = "00000000-0000-4000-8000-000000004103"
        val mediaUri = "/private/h41-$mediaUuid.jpg"
        val bytes = byteArrayOf(4, 1, 4, 2, 0, 4, 2, 4)
        rig.mediaFiles.preparedUploadBytes[mediaUri] = bytes
        val recordId = rig.records.seed(
            RecordEntity(
                clientUuid = recordUuid,
                babyId = babyId,
                type = "formula",
                timestamp = 4201,
                payloadJson = """{"amount_ml":90}""",
                schemaVersion = 2,
                updatedAt = 4201,
                syncDirty = true,
                baseVersion = "v-base-42",
            ),
        )
        rig.media.seed(
            MediaAssetEntity(
                recordId = recordId,
                clientUuid = mediaUuid,
                kind = "log",
                localUri = mediaUri,
                mime = "image/jpeg",
                createdAt = 4201,
                updatedAt = 4201,
                syncDirty = true,
            ),
        )

        val root = ownedDirectory("h41-spool-")
        val firstSpool = FileImmutableMediaSpool(
            mediaFiles = rig.mediaFiles,
            root = root,
            capacityBytes = 64,
            slotReservationBytes = 8,
        )
        val crashingSpool = CrashAfterFreezeGroupSpool(firstSpool)
        val firstEngine = engineFor(rig, crashingSpool)

        assertThat(runCatching {
            firstEngine.synchronize(session, SyncTrigger.LocalWrite)
        }.exceptionOrNull()).hasMessageThat().contains("h41 spool promote")

        val mutationId = requireNotNull(rig.records.getByClientUuid(recordUuid)?.mutationId)
        assertThat(rig.conflictDetails.getFrozenMediaSpoolManifest(mutationId)).isNull()
        assertThat(firstSpool.recoverGroup(mutationId))
            .isInstanceOf(ImmutableMediaSpoolRecovery.Complete::class.java)

        val restartedSpool = FileImmutableMediaSpool(
            mediaFiles = rig.mediaFiles,
            root = root,
            capacityBytes = 64,
            slotReservationBytes = 8,
        )
        engineFor(rig, restartedSpool).synchronize(session, SyncTrigger.LocalWrite)

        assertThat(rig.mediaFiles.prepareUploadCounts[mediaUri]).isEqualTo(1)
        assertThat(rig.backend.causalMediaPreimageBytes.map { it.first })
            .containsExactly(mediaUuid)
        assertThat(rig.backend.causalMediaPreimageBytes.single().second)
            .isEqualTo(bytes)
        assertThat(rig.backend.causalCommittedUnits.flatten().map { it.mutationId })
            .containsExactly(mutationId)
        assertThat(rig.records.getByClientUuid(recordUuid)?.syncDirty).isFalse()
        assertThat(rig.conflictDetails.getFrozenMediaSpoolManifest(mutationId)).isNull()
        assertThat(restartedSpool.recoverGroup(mutationId)).isNull()
    }

    @Test
    fun c3_pagePromoteInterrupted_restartKeepsCompleteCacheAndResumesContinuation() = runTest {
        val summaries = MemoryConflictSummaryDao()
        val rows = MemoryConflictSnapshotCacheDao()
        val transactions = com.lezi.babylog.sync.RecordingTransactionRunner()
        val projection = ConflictSnapshotProjection(summaries, rows, transactions)
        val conflictId = "00000000-0000-4000-8000-000000004104"
        val oldComplete = pageSnapshot(conflictId, "v-old", "b-old", 0, true, null)
        projection.replaceComplete(oldComplete)
        val first = pageSnapshot(conflictId, "v-new", "b-1", 0, false, H41_CONTINUATION)
        val second = pageSnapshot(conflictId, "v-new", "b-2", 1, true, null)
        var fetchCount = 0

        val failure = runCatching {
            projection.loadComplete(conflictId) {
                when (fetchCount++) {
                    0 -> FetchedConflictSnapshotPage(first, encodedBytes = 12_041)
                    else -> {
                        transactions.failBeforeNextBlock = IOException("h41 page promote")
                        FetchedConflictSnapshotPage(second, encodedBytes = 12_042)
                    }
                }
            }
        }.exceptionOrNull()

        assertThat(failure).hasMessageThat().contains("h41 page promote")
        assertThat(projection.read(conflictId)).isEqualTo(oldComplete)
        assertThat(rows.getTransportJournal(conflictSnapshotStageCacheKey(conflictId))).isNotNull()
        assertThat(runCatching { projection.replaceComplete(first) }.isFailure).isTrue()

        val requests = mutableListOf<ConflictSnapshotPageRequest>()
        val restarted = ConflictSnapshotProjection(summaries, rows, transactions)
        val complete = restarted.loadComplete(conflictId) { request ->
            requests += request
            FetchedConflictSnapshotPage(second, encodedBytes = 12_042)
        }

        assertThat(requests).containsExactly(
            ConflictSnapshotPageRequest.Continuation(
                snapshotToken = H41_TOKEN,
                continuation = H41_CONTINUATION,
            ),
        )
        assertThat(complete.branchVersionIds).containsExactly("b-1", "b-2").inOrder()
        assertThat(restarted.read(conflictId)).isEqualTo(complete)
        assertThat(rows.getTransportJournal(conflictSnapshotStageCacheKey(conflictId))).isNull()
    }

    @Test
    fun c4_resolutionDurableBeforeResponse_rebuiltBackendReplaysOneTerminal() = runTest {
        val server = DurableResolutionServer()
        val session = joinedReplicaSession().copy(pullCursor = 44)
        val request = ConflictResolveRequest(
            snapshotToken = H41_TOKEN,
            resolutionMutationId = "00000000-0000-4000-8000-000000004105",
            choices = listOf(
                ConflictResolutionChoice(path = "/note", choiceId = H41_CHOICE_ID),
            ),
        )
        val firstBackend = CrashAfterDurableResolutionBackend(server)

        assertThat(runCatching {
            firstBackend.resolveConflict(session, H41_CONFLICT_ID, request)
        }.exceptionOrNull()).hasMessageThat().contains("h41 resolution response")

        val restartedBackend = RebuiltResolutionBackend(server)
        val replay = restartedBackend.resolveConflict(session, H41_CONFLICT_ID, request)

        assertThat(replay).isEqualTo(
            ConflictResolveResult.Accepted(
                stableVersionId = "v-h41-resolution",
                resolutionMutationId = request.resolutionMutationId,
                replay = true,
            ),
        )
        assertThat(server.requests).containsExactly(request, request).inOrder()
        assertThat(server.terminalCount).isEqualTo(1)
    }

    private fun engineFor(
        rig: ReplicaEngineRig,
        spool: ImmutableMediaSpool,
    ): ReplicaSyncEngine = ReplicaSyncEngine(
        backend = rig.backend,
        preferences = rig.preferences,
        recordDao = rig.records,
        carePlanDao = rig.carePlans,
        babyDao = rig.babies,
        mediaDao = rig.media,
        customItemDao = rig.customItems,
        familyDao = rig.families,
        clock = object : com.lezi.babylog.sync.session.PolicyClock {
            override fun nowMillis(): Long = 1_000
        },
        mediaFiles = rig.mediaFiles,
        immutableMediaSpool = spool,
        mediaFileCleanup = rig.mediaFileCleanup,
        transactionRunner = rig.transactions,
        carePlanAppliedListener = CarePlanFamilyAppliedListener { },
        familyBabyAppliedListener = FamilyBabyAuthorityAppliedListener { },
        fulfillmentCandidateDao = rig.fulfillmentCandidates,
        fulfillmentAuthoritySettlement = rig.fulfillmentAuthoritySettlement,
        requireRemoteAllowed = {},
        wakeObservationDao = rig.wakeObservations,
        conflictSummaryDao = rig.conflictSummaries,
        conflictSnapshotCacheDao = rig.conflictDetails,
        sourceRelationDao = rig.sourceRelations,
    )

    private fun ownedDirectory(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().also(ownedDirectories::add)

    private fun pageSnapshot(
        conflictId: String,
        stableVersion: String,
        branchVersion: String,
        pageIndex: Int,
        complete: Boolean,
        continuation: String?,
    ): ConflictSnapshot = ConflictSnapshotCodec.decode(
        """
        {
          "contract":"conflict_snapshot_v2",
          "conflict_id":"$conflictId",
          "entity_type":"record",
          "client_uuid":"$H41_CLIENT_UUID",
          "snapshot_token":"$H41_TOKEN",
          "expires_at":2000000,
          "stable":{
            "version_id":"$stableVersion","base_version":null,
            "root":$H41_ROOT,"media":[],"deleted":false,
            "mutation_id":"$H41_MUTATION_ID","actor_id":"member-a",
            "device_id":"device-a","received_at":100
          },
          "branches":[{
            "version_id":"$branchVersion","base_version":"$stableVersion",
            "root":$H41_ROOT,"media":[],"deleted":false,
            "mutation_id":"$H41_BRANCH_MUTATION_ID","actor_id":"member-b",
            "device_id":"device-b","received_at":101
          }],
          "conflicting":[{
            "path":"/note","candidates":[
              {"choice_id":"$H41_CHOICE_ID","outcome":{"op":"set","value":null},"sources":[
                {"version_id":"$stableVersion","mutation_id":"$H41_MUTATION_ID","actor_id":"member-a","device_id":"device-a","received_at":100}
              ]},
              {"choice_id":"${"d".repeat(43)}","outcome":{"op":"set","value":"other"},"sources":[
                {"version_id":"$H41_CONFLICT_SOURCE_VERSION","mutation_id":"$H41_BRANCH_MUTATION_ID","actor_id":"member-b","device_id":"device-b","received_at":101}
              ]}
            ]
          }],
          "auto_merged":[],
          "page_index":$pageIndex,
          "continuation":${continuation?.let { "\"$it\"" } ?: "null"},
          "complete":$complete
        }
        """.trimIndent(),
    )

    private class CrashAfterFreezeGroupSpool(
        private val delegate: ImmutableMediaSpool,
    ) : ImmutableMediaSpool by delegate {
        private var crashed = false

        override suspend fun freezeGroup(
            mutationId: String,
            sources: List<ImmutableMediaSpoolSource>,
        ): ImmutableMediaSpoolGroup {
            val group = delegate.freezeGroup(mutationId, sources)
            if (!crashed) {
                crashed = true
                error("h41 spool promote")
            }
            return group
        }
    }

    private class DurableResolutionServer {
        val requests = mutableListOf<ConflictResolveRequest>()
        var terminalCount = 0
            private set
        private var accepted: ConflictResolveResult.Accepted? = null

        fun resolve(request: ConflictResolveRequest): ConflictResolveResult.Accepted {
            requests += request
            val existing = accepted
            if (existing != null) {
                require(existing.resolutionMutationId == request.resolutionMutationId)
                return existing.copy(replay = true)
            }
            terminalCount += 1
            return ConflictResolveResult.Accepted(
                stableVersionId = "v-h41-resolution",
                resolutionMutationId = request.resolutionMutationId,
                replay = false,
            ).also { accepted = it }
        }
    }

    private class CrashAfterDurableResolutionBackend(
        private val server: DurableResolutionServer,
    ) : SyncBackend by FakeSyncBackend() {
        override suspend fun resolveConflict(
            session: SyncSession,
            conflictId: String,
            request: ConflictResolveRequest,
        ): ConflictResolveResult {
            server.resolve(request)
            error("h41 resolution response")
        }
    }

    private class RebuiltResolutionBackend(
        private val server: DurableResolutionServer,
    ) : SyncBackend by FakeSyncBackend() {
        override suspend fun resolveConflict(
            session: SyncSession,
            conflictId: String,
            request: ConflictResolveRequest,
        ): ConflictResolveResult = server.resolve(request)
    }

    private companion object {
        const val H41_SEED = 0x41_20_41L
        val H41_KILL_POINTS = listOf(
            "envelope-durable-before-http",
            "spool-promote-before-room-manifest",
            "page-transaction-before-complete-promote",
            "resolution-durable-before-response",
        )
        const val H41_CONFLICT_ID = "00000000-0000-4000-8000-000000004106"
        const val H41_CLIENT_UUID = "00000000-0000-4000-8000-000000004107"
        const val H41_MUTATION_ID = "00000000-0000-4000-8000-000000004108"
        const val H41_BRANCH_MUTATION_ID = "00000000-0000-4000-8000-000000004109"
        const val H41_CONFLICT_SOURCE_VERSION = "v-conflict-source"
        const val H41_TOKEN = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val H41_CONTINUATION = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val H41_CHOICE_ID = "ccccccccccccccccccccccccccccccccccccccccccc"
        val H41_ROOT =
            """{"baby_client_uuid":"00000000-0000-4000-8000-000000004110","type":"formula","custom_item_client_uuid":null,"timestamp":100,"end_timestamp":null,"note":null,"payload_json":{"amount_ml":60},"schema_version":2,"created_by_membership_id":"member-a","updated_at":100}"""
    }
}
