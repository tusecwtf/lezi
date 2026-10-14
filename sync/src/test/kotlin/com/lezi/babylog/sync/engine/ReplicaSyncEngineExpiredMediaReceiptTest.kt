package com.lezi.babylog.sync.engine

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.core.database.RecordEntity
import com.lezi.babylog.sync.SyncTrigger
import com.lezi.babylog.sync.backend.CausalCommitRejectedException
import com.lezi.babylog.sync.session.FamilyRole
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ReplicaSyncEngineExpiredMediaReceiptTest {
    @Test
    fun definitiveExpiry_repreparesOnce_thenNextCycleCommitsExactMutation() = runTest {
        val fixture = fixture()
        val rig = fixture.rig
        rig.backend.onCausalCommit = { units ->
            rig.backend.onCausalCommit = null
            throw CausalCommitRejectedException(units.single().mutationId, "media_preimage_expired")
        }

        val failure = runCatching { rig.engine.synchronize(fixture.session, SyncTrigger.LocalWrite) }
            .exceptionOrNull()

        assertThat(failure).isInstanceOf(CausalCommitRejectedException::class.java)
        assertThat(rig.backend.causalCommittedUnits).hasSize(1)
        assertThat(rig.backend.causalMediaPreimageBytes).hasSize(2)
        val before = journal(rig)
        assertThat(before.phase).isEqualTo(CausalMediaSettlementPhase.CommitUnknown)
        rig.mediaFiles.prepareUploadFailures += URI
        rig.newEngine().synchronize(fixture.session, SyncTrigger.LocalWrite)
        assertThat(rig.backend.causalCommittedUnits[1]).isEqualTo(rig.backend.causalCommittedUnits[0])
        assertThat(rig.backend.causalMediaPreimageBytes).hasSize(2)
        assertThat(rig.mediaFiles.prepareUploadCounts[URI]).isEqualTo(1)
        assertThat(rig.records.getByClientUuid(RECORD)?.syncDirty).isFalse()
    }

    @Test
    fun timeoutAndUnboundExpiry_doNotReprepareOrChangeUnknownJournal() = runTest {
        for (failure in listOf(
            IOException("lost commit response"),
            CausalCommitRejectedException(null, "media_preimage_expired"),
            CausalCommitRejectedException("unrelated-mutation", "media_preimage_expired"),
        )) {
            val fixture = fixture()
            fixture.rig.backend.onCausalCommit = { throw failure }
            repeat(2) {
                val observed = runCatching {
                    fixture.rig.newEngine().synchronize(fixture.session, SyncTrigger.LocalWrite)
                }.exceptionOrNull()
                assertThat(observed).isInstanceOf(failure.javaClass)
                if (failure is CausalCommitRejectedException) {
                    assertThat((observed as CausalCommitRejectedException).code).isEqualTo(failure.code)
                    assertThat(observed.mutationId).isEqualTo(failure.mutationId)
                }
            }
            assertThat(fixture.rig.backend.causalMediaPreimageBytes).hasSize(1)
            assertThat(journal(fixture.rig).phase).isEqualTo(CausalMediaSettlementPhase.CommitUnknown)
            assertThat(fixture.rig.immutableMediaSpool.discardedMutationIds).isEmpty()
        }
    }

    @Test
    fun newerLocalEdit_survivesExpiryRepairAndRestartOfOldUnknownCommit() = runTest {
        val fixture = fixture()
        val rig = fixture.rig
        rig.backend.onCausalCommit = { throw IOException("unknown commit") }
        assertThat(runCatching { rig.engine.synchronize(fixture.session, SyncTrigger.LocalWrite) }
            .exceptionOrNull()).isInstanceOf(IOException::class.java)
        val frozen = journal(rig)
        val original = requireNotNull(rig.records.getByClientUuid(RECORD))
        rig.records.update(original.copy(updatedAt = original.updatedAt + 1,
            payloadJson = """{"amount_ml":120}""", syncDirty = true))
        rig.backend.onCausalCommit = { units ->
            rig.backend.onCausalCommit = null
            throw CausalCommitRejectedException(units.single().mutationId, "media_preimage_expired")
        }
        assertThat(runCatching { rig.newEngine().synchronize(fixture.session, SyncTrigger.LocalWrite) }
            .exceptionOrNull()).isInstanceOf(CausalCommitRejectedException::class.java)
        val repaired = journal(rig)
        assertThat(repaired.binding).isEqualTo(frozen.binding)
        assertThat(repaired.mutation).isEqualTo(frozen.mutation)
        assertThat(repaired.manifest).isEqualTo(frozen.manifest)
        assertThat(repaired.phase).isEqualTo(CausalMediaSettlementPhase.CommitUnknown)
        rig.mediaFiles.prepareUploadFailures += URI

        rig.newEngine().synchronize(fixture.session, SyncTrigger.LocalWrite)

        assertThat(rig.backend.causalCommittedUnits.take(3).map { it.single() })
            .containsExactly(frozen.mutation, frozen.mutation, frozen.mutation).inOrder()
        val current = requireNotNull(rig.records.getByClientUuid(RECORD))
        assertThat(current.payloadJson).isEqualTo("""{"amount_ml":120}""")
        assertThat(current.updatedAt).isEqualTo(original.updatedAt + 1)
        assertThat(current.syncDirty).isTrue()
        assertThat(rig.conflictDetails.getFrozenMediaSpoolManifest(frozen.mutation.mutationId)).isNull()
    }

    @Test
    fun rejectedUnitOnly_isRepreparedWhilePeerJournalRemainsUntouched() = runTest {
        val fixture = fixture()
        val rig = fixture.rig
        val first = requireNotNull(rig.records.getByClientUuid(RECORD))
        val peerRecord = "00000000-0000-4000-8000-000000000754"
        val peerId = rig.records.seed(first.copy(id = 0, clientUuid = peerRecord))
        rig.media.seed(rig.media.listForRecord(first.id).single().copy(
            id = 0, recordId = peerId, clientUuid = MEDIA_TWO,
        ))
        rig.backend.onCausalCommit = { units ->
            rig.backend.onCausalCommit = null
            rig.backend.causalMediaReceiptFactory = { uuid, bytes, sha ->
                com.lezi.babylog.sync.backend.CausalMediaPreimageReceipt(
                    mediaUuid = uuid, status = "staged", byteSize = bytes.size.toLong(),
                    sha256 = sha, expiresAtEpochSeconds = 20_000,
                )
            }
            throw CausalCommitRejectedException(
                units.single { it.clientUuid == RECORD }.mutationId, "media_preimage_expired",
            )
        }
        assertThat(runCatching { rig.engine.synchronize(fixture.session, SyncTrigger.LocalWrite) }
            .exceptionOrNull()).isInstanceOf(CausalCommitRejectedException::class.java)
        val journals = rig.conflictDetails.listFrozenMediaSpoolManifests().map {
            requireNotNull(decodeCausalMediaSettlementOrNull(it.payloadJson))
        }
        assertThat(journals).hasSize(2)
        assertThat(journals.single { it.binding.clientUuid == RECORD }.receipts.single()
            .expiresAtEpochSeconds).isEqualTo(20_000L)
        assertThat(journals.single { it.binding.clientUuid == peerRecord }.receipts.single()
            .expiresAtEpochSeconds).isEqualTo(Long.MAX_VALUE)
        assertThat(rig.backend.causalMediaPreimageBytes.count { it.first == MEDIA }).isEqualTo(2)
        assertThat(rig.backend.causalMediaPreimageBytes.count { it.first == MEDIA_TWO }).isEqualTo(1)
        assertThat(journals.map { it.phase }.distinct())
            .containsExactly(CausalMediaSettlementPhase.CommitUnknown)
    }

    @Test
    fun partialRenewalFailure_keepsEntireOldJournal_andRestartCommitsFirst() = runTest {
        val fixture = fixture(twoMedia = true)
        val rig = fixture.rig
        rig.backend.onCausalCommit = { throw IOException("unknown first commit") }
        runCatching { rig.engine.synchronize(fixture.session, SyncTrigger.LocalWrite) }
        val original = journal(rig)
        rig.backend.onCausalCommit = { units ->
            throw CausalCommitRejectedException(units.single().mutationId, "media_preimage_expired")
        }
        rig.backend.onCausalMediaPreimage = { uuid ->
            if (uuid == MEDIA_TWO) throw IOException("second renewal response lost")
        }
        val failure = runCatching { rig.newEngine().synchronize(fixture.session, SyncTrigger.LocalWrite) }
            .exceptionOrNull()
        assertThat(failure).isInstanceOf(IOException::class.java)
        assertThat(journal(rig)).isEqualTo(original)
        assertThat(rig.backend.causalMediaPreimageBytes).hasSize(3)
        rig.backend.onCausalMediaPreimage = null
        val orderStart = rig.backend.syncOrder.size
        rig.backend.onCausalCommit = { units ->
            rig.backend.onCausalCommit = null
            throw CausalCommitRejectedException(units.single().mutationId, "media_preimage_expired")
        }
        runCatching { rig.newEngine().synchronize(fixture.session, SyncTrigger.LocalWrite) }
        val recoveryOrder = rig.backend.syncOrder.drop(orderStart)
            .filter { it.startsWith("causal_commit:") || it.startsWith("causal_media_preimage_started:") }
        assertThat(recoveryOrder.first()).isEqualTo("causal_commit:1")
        assertThat(rig.backend.causalCommittedUnits.map { it.single() }.distinct())
            .containsExactly(original.mutation)
        rig.newEngine().synchronize(fixture.session, SyncTrigger.LocalWrite)
        assertThat(rig.records.getByClientUuid(RECORD)?.syncDirty).isFalse()
    }

    private suspend fun journal(rig: ReplicaEngineRig): CausalMediaSettlementJournal =
        requireNotNull(decodeCausalMediaSettlementOrNull(
            rig.conflictDetails.listFrozenMediaSpoolManifests().single().payloadJson,
        ))

    private fun fixture(twoMedia: Boolean = false): Fixture {
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner, pullCursor = 80)
        val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
        val babyId = rig.babies.seed(localReplicaBaby().copy(
            syncDirty = false, familyAuthority = true, baseVersion = "v-baby",
        ))
        val recordId = rig.records.seed(RecordEntity(
            clientUuid = RECORD, babyId = babyId, type = "formula", timestamp = 100,
            payloadJson = """{"amount_ml":60}""", schemaVersion = 2,
            updatedAt = 100, syncDirty = true, baseVersion = "v0",
        ))
        rig.mediaFiles.preparedUploadBytes[URI] = byteArrayOf(1, 2, 3, 4)
        for (uuid in if (twoMedia) listOf(MEDIA, MEDIA_TWO) else listOf(MEDIA)) {
            rig.media.seed(MediaAssetEntity(
                recordId = recordId, clientUuid = uuid, kind = "log", localUri = URI,
                mime = "image/jpeg", byteSize = 4, createdAt = 100, updatedAt = 100,
                syncDirty = true,
            ))
        }
        return Fixture(session, rig)
    }

    private data class Fixture(
        val session: com.lezi.babylog.sync.session.SyncSession,
        val rig: ReplicaEngineRig,
    )

    private companion object {
        const val RECORD = "00000000-0000-4000-8000-000000000751"
        const val MEDIA = "00000000-0000-4000-8000-000000000752"
        const val MEDIA_TWO = "00000000-0000-4000-8000-000000000753"
        const val URI = "/private/expired-receipt.jpg"
    }
}
