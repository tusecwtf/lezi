package com.lezi.babylog.sync.engine

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.causal.frozenMediaSpoolCacheKey
import com.lezi.babylog.sync.MemoryConflictSnapshotCacheDao
import com.lezi.babylog.sync.RecordingTransactionRunner
import com.lezi.babylog.sync.TestImmutableMediaSpool
import com.lezi.babylog.sync.TestMediaFileStore
import com.lezi.babylog.sync.backend.CausalCommitStatus
import com.lezi.babylog.sync.backend.CausalMediaItem
import com.lezi.babylog.sync.backend.CausalMediaPreimageReceipt
import com.lezi.babylog.sync.backend.CausalMutationUnit
import com.lezi.babylog.sync.backend.CausalCommitUnitResult
import com.lezi.babylog.sync.media.CausalMediaRole
import com.lezi.babylog.sync.media.ImmutableMediaSpoolGroup
import com.lezi.babylog.sync.media.ImmutableMediaSpoolItem
import com.lezi.babylog.sync.media.encodeImmutableMediaSpoolGroup
import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Test

/** Public seam: mutation-bound Android media receipt/commit/cleanup state machine. */
class CausalMediaSettlementJournalTest {
    @Test
    fun acceptedCleanupWaitsForDurableTerminalAndRecoversAfterJournalDeleteCrash() = runTest {
        val rig = JournalRig()
        val frozen = rig.bindPending()

        assertThat(rig.owner.finishCleanup(MUTATION_ID)).isFalse()
        assertThat(rig.spool.discardedMutationIds).isEmpty()

        rig.owner.recordPrepared(MUTATION_ID, receipt())
        rig.owner.markCommitUnknown(MUTATION_ID)
        rig.owner.markTerminal(
            MUTATION_ID,
            terminal(frozen, CausalCommitStatus.ACCEPTED),
        )
        rig.cache.deleteFailure = IOException("Room delete interrupted")

        assertThat(runCatching { rig.owner.finishCleanup(MUTATION_ID) }.exceptionOrNull())
            .isInstanceOf(IOException::class.java)
        assertThat(rig.spool.discardedMutationIds).containsExactly(MUTATION_ID)
        assertThat(rig.cache.get(frozenMediaSpoolCacheKey(MUTATION_ID))).isNotNull()

        rig.cache.deleteFailure = null
        rig.owner.finishPendingCleanups()

        assertThat(rig.spool.discardedMutationIds)
            .containsExactly(MUTATION_ID, MUTATION_ID)
            .inOrder()
        assertThat(rig.cache.get(frozenMediaSpoolCacheKey(MUTATION_ID))).isNull()
    }

    @Test
    fun pendingUnknownAndBranchedNeverBecomeCleanupEligible() = runTest {
        val rig = JournalRig()
        val frozen = rig.bindPending()

        assertThat(rig.owner.finishCleanup(MUTATION_ID)).isFalse()
        rig.owner.recordPrepared(MUTATION_ID, receipt())
        rig.owner.markCommitUnknown(MUTATION_ID)
        assertThat(rig.owner.finishCleanup(MUTATION_ID)).isFalse()
        rig.owner.markTerminal(
            MUTATION_ID,
            terminal(
                frozen,
                CausalCommitStatus.BRANCHED,
                conflictId = "conflict-a",
                branchVersionId = "branch-a",
            ),
        )

        rig.owner.finishPendingCleanups()

        assertThat(rig.spool.discardedMutationIds).isEmpty()
        val durable = requireNotNull(
            rig.cache.get(frozenMediaSpoolCacheKey(MUTATION_ID)),
        )
        assertThat(decodeCausalMediaSettlementOrNull(durable.snapshotJson)?.phase)
            .isEqualTo(CausalMediaSettlementPhase.Branched)
    }

    @Test
    fun pendingJournalHasNoImplicitAbandonOrCleanupPath() = runTest {
        val rig = JournalRig()
        rig.bindPending()

        rig.owner.finishPendingCleanups()

        assertThat(rig.spool.discardedMutationIds).isEmpty()
        assertThat(rig.cache.get(frozenMediaSpoolCacheKey(MUTATION_ID))).isNotNull()
    }

    @Test
    fun unknownAndTerminalJournalsRejectOmittedDuplicateOrDriftedReceipts() = runTest {
        val rig = JournalRig()
        rig.bindPending()
        rig.owner.recordPrepared(MUTATION_ID, receipt())
        rig.owner.markCommitUnknown(MUTATION_ID)
        val durable = requireNotNull(rig.cache.get(frozenMediaSpoolCacheKey(MUTATION_ID)))
        val root = Json.parseToJsonElement(durable.snapshotJson).jsonObject
        val exactReceipt = requireNotNull(root["receipts"] as? JsonArray).single()
        val corruptions = listOf(
            JsonArray(emptyList()),
            JsonArray(listOf(exactReceipt, exactReceipt)),
            JsonArray(
                listOf(
                    buildJsonObject {
                        (exactReceipt as JsonObject).forEach { (key, value) ->
                            put(key, if (key == "sha256") JsonPrimitive("ff".repeat(32)) else value)
                        }
                    },
                ),
            ),
        )

        corruptions.forEach { receipts ->
            val corrupted = buildJsonObject {
                root.forEach { (key, value) -> put(key, if (key == "receipts") receipts else value) }
            }.toString()
            assertThat(runCatching { decodeCausalMediaSettlementOrNull(corrupted) }.exceptionOrNull())
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun processDeathRestoresPartialReceiptsAndCannotCommitUntilTheGroupIsComplete() = runTest {
        val cache = MemoryConflictSnapshotCacheDao()
        val spool = TestImmutableMediaSpool(TestMediaFileStore())
        val firstOwner = CausalMediaSettlementJournalOwner(
            cache,
            spool,
            RecordingTransactionRunner(),
        )
        val secondItem = ITEM.copy(
            mediaUuid = "00000000-0000-4000-8000-000000000022",
            slot = 1,
            sha256 = "22".repeat(32),
        )
        val manifest = ImmutableMediaSpoolGroup(MUTATION_ID, listOf(ITEM, secondItem))
        val mutation = MUTATION.copy(
            media = listOf(
                MUTATION.media.single(),
                MUTATION.media.single().copy(
                    mediaUuid = secondItem.mediaUuid,
                    sha256 = secondItem.sha256,
                ),
            ),
        )
        cache.putFrozenMediaSpoolManifest(
            MUTATION_ID,
            encodeImmutableMediaSpoolGroup(manifest),
            100,
        )
        firstOwner.bind(mutation, 100, causalMutationContentHash(mutation), manifest)
        firstOwner.recordPrepared(MUTATION_ID, receipt())

        val restoredOwner = CausalMediaSettlementJournalOwner(
            cache,
            spool,
            RecordingTransactionRunner(),
        )

        assertThat(restoredOwner.preparedReceipt(MUTATION_ID, ITEM)).isEqualTo(receipt())
        assertThat(restoredOwner.preparedReceipt(MUTATION_ID, secondItem)).isNull()
        assertThat(runCatching { restoredOwner.markCommitUnknown(MUTATION_ID) }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(spool.discardedMutationIds).isEmpty()

        restoredOwner.recordPrepared(
            MUTATION_ID,
            receipt().copy(
                mediaUuid = secondItem.mediaUuid,
                sha256 = secondItem.sha256,
            ),
        )
        assertThat(restoredOwner.markCommitUnknown(MUTATION_ID).phase)
            .isEqualTo(CausalMediaSettlementPhase.CommitUnknown)
    }

    private class JournalRig {
        val cache = MemoryConflictSnapshotCacheDao()
        val spool = TestImmutableMediaSpool(TestMediaFileStore())
        val owner = CausalMediaSettlementJournalOwner(
            cache = cache,
            spool = spool,
            transactionRunner = RecordingTransactionRunner(),
        )

        suspend fun bindPending(): CausalMutationUnit {
            cache.putFrozenMediaSpoolManifest(
                mutationId = MUTATION_ID,
                canonicalManifestJson = encodeImmutableMediaSpoolGroup(MANIFEST),
                contentEpoch = 100,
            )
            return MUTATION.also { mutation ->
                owner.bind(
                    mutation = mutation,
                    contentEpoch = 100,
                    requestHash = REQUEST_HASH,
                    manifest = MANIFEST,
                )
            }
        }
    }

    private companion object {
        const val MUTATION_ID = "00000000-0000-4000-8000-000000000020"
        const val MEDIA_ID = "00000000-0000-4000-8000-000000000021"
        val SHA = "20".repeat(32)
        val ITEM = ImmutableMediaSpoolItem(
            mediaUuid = MEDIA_ID,
            slot = 0,
            role = CausalMediaRole.Log,
            sha256 = SHA,
            byteSize = 3,
            mime = "image/jpeg",
            width = null,
            height = null,
        )
        val MANIFEST = ImmutableMediaSpoolGroup(MUTATION_ID, listOf(ITEM))
        val MUTATION = CausalMutationUnit(
            mutationId = MUTATION_ID,
            baseVersion = "v0",
            entityType = "record",
            clientUuid = "record-a",
            rootJson = "{}",
            media = listOf(
                CausalMediaItem(
                    mediaUuid = MEDIA_ID,
                    role = "log",
                    sha256 = SHA,
                    byteSize = 3,
                    mime = "image/jpeg",
                ),
            ),
        )
        val REQUEST_HASH = causalMutationContentHash(MUTATION)

        fun receipt() = CausalMediaPreimageReceipt(
            mediaUuid = MEDIA_ID,
            status = "staged",
            byteSize = 3,
            sha256 = SHA,
            expiresAtEpochSeconds = 10_000,
        )

        fun terminal(
            mutation: CausalMutationUnit,
            status: String,
            conflictId: String? = null,
            branchVersionId: String? = null,
        ) = CausalCommitUnitResult(
            status = status,
            mutationId = mutation.mutationId,
            requestHash = REQUEST_HASH,
            stableVersionId = "v1",
            stableRootJson = mutation.rootJson,
            stableMedia = mutation.media,
            conflictId = conflictId,
            branchVersionId = branchVersionId,
        )
    }
}
