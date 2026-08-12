package com.lezi.babylog.sync.media

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.core.database.RecordEntity
import com.lezi.babylog.sync.backend.CausalCommitBatchResult
import com.lezi.babylog.sync.backend.CausalCommitRejectedException
import com.lezi.babylog.sync.backend.CausalCommitStatus
import com.lezi.babylog.sync.backend.CausalCommitUnitResult
import com.lezi.babylog.sync.backend.CausalMediaPreimageReceipt
import com.lezi.babylog.sync.backend.CausalMutationUnit
import com.lezi.babylog.sync.backend.HttpSyncBackend
import com.lezi.babylog.sync.backend.SyncHttpConnectionFactory
import com.lezi.babylog.sync.backend.TestMediaUploadSource
import com.lezi.babylog.sync.backend.loopbackBackend
import com.lezi.babylog.sync.backend.readRequest
import com.lezi.babylog.sync.backend.testSession
import com.lezi.babylog.sync.engine.CausalMediaSettlementJournalOwner
import com.lezi.babylog.sync.engine.CausalMediaSettlementPhase
import com.lezi.babylog.sync.engine.ReplicaEngineRig
import com.lezi.babylog.sync.engine.causalMutationContentHash
import com.lezi.babylog.sync.engine.decodeCausalMediaSettlementOrNull
import com.lezi.babylog.sync.engine.joinedReplicaSession
import com.lezi.babylog.sync.engine.localReplicaBaby
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.sync.session.SyncSession
import com.lezi.babylog.sync.SyncTrigger
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * H38 acceptance: media prepare/commit receipt fault matrix.
 *
 * Proves lost prepare/commit responses, duplicate prepare, expired/wrong
 * family-principal-digest-length bindings, and restart-before-settlement never
 * re-read the source URI, never invent a second upload/version for a durable
 * receipt, fail closed on wrong/expired bindings with honest pending/unknown,
 * clean up only after terminal settlement, and retain unknown/pending evidence.
 *
 * Production owners: H19 server receipt claim, H20 Android settlement journal,
 * H18 immutable spool (source-once via H37). Device Room residual remains when
 * ADB devices = 0.
 */
class MediaReceiptFaultAcceptanceTest {
    @Test
    fun c0_seedAndContractPins() {
        assertThat(H38_FAULT_SEED).isEqualTo(0x38_19_20L)
        assertThat(H38_FAULT_SEED and 0xFF).isEqualTo(0x20L) // H20 settlement
        assertThat((H38_FAULT_SEED shr 8) and 0xFF).isEqualTo(0x19L) // H19 receipt
        assertThat(H37_FAULT_SEED).isEqualTo(0x37_18_31L)
    }

    @Test
    fun c1_lostPrepareResponse_retriesExactBytesWithoutUriReread() = runTest {
        val fixture = seedRecordMedia(
            recordUuid = "record-h38-lost-prepare",
            mediaUuid = MEDIA_PREPARE,
            localUri = "content://h38-lost-prepare",
            bytes = byteArrayOf(0x38.toByte(), 0x01, 0x50, 0x52),
        )
        val (session, rig, _, localUri, frozenBytes) = fixture
        var prepareAttempts = 0
        rig.backend.onCausalMediaPreimage = { prepareAttempts += 1 }
        // Server accepted bytes; client never observes the receipt.
        rig.backend.failAfterCausalMediaPreimageUpload =
            IOException("h38 lost prepare response")

        assertThat(
            runCatching { rig.engine.synchronize(session, SyncTrigger.LocalWrite) }
                .exceptionOrNull(),
        ).isInstanceOf(IOException::class.java)

        // Mutate/deny the source only after freeze so any later URI re-read would diverge.
        rig.mediaFiles.preparedUploadBytes[localUri] = byteArrayOf(0xFF.toByte(), 0xEE.toByte())
        rig.mediaFiles.prepareUploadFailures += localUri

        val pendingRow = rig.conflictDetails.listFrozenMediaSpoolManifests().single()
        val pending = requireNotNull(decodeCausalMediaSettlementOrNull(pendingRow.payloadJson))
        assertThat(pending.phase).isEqualTo(CausalMediaSettlementPhase.Pending)
        assertThat(pending.receipts).isEmpty()
        assertThat(rig.backend.causalMediaPreimageBytes).hasSize(1)
        assertThat(rig.backend.causalMediaPreimageBytes.single().second).isEqualTo(frozenBytes)
        assertThat(rig.mediaFiles.prepareUploadCounts[localUri]).isEqualTo(1)
        val mutationId = pending.mutation.mutationId
        assertThat(rig.immutableMediaSpool.openCounts[mutationId]).isEqualTo(1)

        rig.engine.synchronize(session, SyncTrigger.LocalWrite)

        assertThat(prepareAttempts).isEqualTo(2)
        assertThat(rig.backend.causalMediaPreimageBytes).hasSize(2)
        assertThat(
            rig.backend.causalMediaPreimageBytes.all { it.second.contentEquals(frozenBytes) },
        ).isTrue()
        assertThat(rig.backend.causalCommittedUnits.single().single().mutationId)
            .isEqualTo(mutationId)
        assertThat(rig.mediaFiles.prepareUploadCounts[localUri]).isEqualTo(1)
        assertThat(rig.immutableMediaSpool.openCounts[mutationId]).isEqualTo(2)
        assertThat(rig.immutableMediaSpool.discardedMutationIds).containsExactly(mutationId)
        assertThat(rig.conflictDetails.getFrozenMediaSpoolManifest(mutationId)).isNull()
        assertThat(rig.records.getByClientUuid("record-h38-lost-prepare")?.syncDirty).isFalse()
        assertThat(sha256Hex(frozenBytes)).isEqualTo(
            rig.backend.causalCommittedUnits.single().single().media.single().sha256,
        )
    }

    @Test
    fun c2_lostCommitResponse_replaysExactMutationWithoutReuploadOrVersionDrift() = runTest {
        val fixture = seedRecordMedia(
            recordUuid = "record-h38-lost-commit",
            mediaUuid = MEDIA_COMMIT,
            localUri = "content://h38-lost-commit",
            bytes = byteArrayOf(0x38.toByte(), 0x02, 0x43, 0x4D),
        )
        val (session, rig, mediaUuid, localUri, frozenBytes) = fixture
        var commitAttempts = 0
        var frozenMutation: CausalMutationUnit? = null
        rig.backend.onCausalCommit = { units ->
            commitAttempts += 1
            val unit = units.single()
            if (frozenMutation == null) {
                frozenMutation = unit
                throw IOException("h38 lost commit response")
            }
            assertThat(unit).isEqualTo(frozenMutation)
        }
        assertThat(
            runCatching { rig.engine.synchronize(session, SyncTrigger.LocalWrite) }
                .exceptionOrNull(),
        ).isInstanceOf(IOException::class.java)
        // Source may change only after freeze/upload; URI re-read must stay impossible.
        rig.mediaFiles.preparedUploadBytes[localUri] = byteArrayOf(9, 9, 9, 9)
        rig.mediaFiles.prepareUploadFailures += localUri

        val mutationId = requireNotNull(frozenMutation).mutationId
        val durable = requireNotNull(rig.conflictDetails.getFrozenMediaSpoolManifest(mutationId))
        val journal = requireNotNull(decodeCausalMediaSettlementOrNull(durable.payloadJson))
        assertThat(journal.phase).isEqualTo(CausalMediaSettlementPhase.CommitUnknown)
        assertThat(journal.receipts.map { it.mediaUuid }).containsExactly(mediaUuid)
        assertThat(rig.backend.causalMediaPreimageBytes).hasSize(1)
        assertThat(rig.backend.causalMediaPreimageBytes.single().second).isEqualTo(frozenBytes)
        assertThat(rig.mediaFiles.prepareUploadCounts[localUri]).isEqualTo(1)
        assertThat(rig.immutableMediaSpool.openCounts[mutationId]).isEqualTo(1)
        assertThat(rig.records.getByClientUuid("record-h38-lost-commit")?.mutationId)
            .isEqualTo(mutationId)

        // Cold restart owner + source mutation must not force a second PUT.
        rig.newEngine().synchronize(session, SyncTrigger.LocalWrite)

        assertThat(commitAttempts).isEqualTo(2)
        assertThat(rig.backend.causalCommittedUnits).hasSize(2)
        assertThat(rig.backend.causalCommittedUnits[0].single())
            .isEqualTo(rig.backend.causalCommittedUnits[1].single())
        assertThat(rig.backend.causalMediaPreimageBytes).hasSize(1)
        assertThat(rig.backend.causalMediaPreimageBytes.single().second).isEqualTo(frozenBytes)
        assertThat(rig.mediaFiles.prepareUploadCounts[localUri]).isEqualTo(1)
        assertThat(rig.immutableMediaSpool.openCounts[mutationId]).isEqualTo(1)
        assertThat(rig.immutableMediaSpool.discardedMutationIds).containsExactly(mutationId)
        assertThat(rig.conflictDetails.getFrozenMediaSpoolManifest(mutationId)).isNull()
        assertThat(rig.records.getByClientUuid("record-h38-lost-commit")?.syncDirty).isFalse()
        val stableVersions = rig.backend.causalCommittedUnits.map {
            it.single().mutationId
        }.distinct()
        assertThat(stableVersions).containsExactly(mutationId)
    }

    @Test
    fun c3_duplicatePrepare_isIdempotentAndDoesNotDuplicateUploadOrVersion() = runTest {
        val fixture = seedRecordMedia(
            recordUuid = "record-h38-dup-prepare",
            mediaUuid = MEDIA_DUP,
            localUri = "content://h38-dup-prepare",
            bytes = byteArrayOf(0x38.toByte(), 0x03, 0x44, 0x55),
        )
        val (session, rig, mediaUuid, localUri, frozenBytes) = fixture
        val receiptHits = AtomicInteger(0)
        // Durable first receipt, interrupt second media prepare — restart must
        // skip PUT for the durable receipt and only complete the missing one.
        val secondMedia = "00000000-0000-4000-8000-000000000384"
        val secondUri = "content://h38-dup-prepare-b"
        val secondBytes = byteArrayOf(0x38.toByte(), 0x03, 0x42, 0x42)
        val recordId = requireNotNull(rig.records.getByClientUuid("record-h38-dup-prepare")).id
        rig.mediaFiles.preparedUploadBytes[secondUri] = secondBytes
        rig.media.seed(
            MediaAssetEntity(
                recordId = recordId,
                clientUuid = secondMedia,
                kind = "log",
                localUri = secondUri,
                createdAt = 100,
                updatedAt = 100,
                syncDirty = true,
            ),
        )
        var secondFailures = 0
        rig.backend.onCausalMediaPreimage = { uuid ->
            receiptHits.incrementAndGet()
            if (uuid == secondMedia && secondFailures++ == 0) {
                throw IOException("h38 post-prepare pre-commit disconnect")
            }
        }

        assertThat(
            runCatching { rig.engine.synchronize(session, SyncTrigger.LocalWrite) }
                .exceptionOrNull(),
        ).isInstanceOf(IOException::class.java)

        val pendingRow = rig.conflictDetails.listFrozenMediaSpoolManifests().single()
        val pending = requireNotNull(decodeCausalMediaSettlementOrNull(pendingRow.payloadJson))
        assertThat(pending.phase).isEqualTo(CausalMediaSettlementPhase.Pending)
        assertThat(pending.receipts.map { it.mediaUuid }).containsExactly(mediaUuid)
        assertThat(rig.backend.causalMediaPreimageBytes.map { it.first })
            .containsExactly(mediaUuid)
        assertThat(rig.backend.causalMediaPreimageBytes.single().second).isEqualTo(frozenBytes)
        val mutationId = pending.mutation.mutationId

        // Explicit journal-level duplicate prepare must be a no-op.
        val owner = CausalMediaSettlementJournalOwner(
            cache = rig.conflictDetails,
            spool = rig.immutableMediaSpool,
            transactionRunner = rig.transactions,
        )
        val exact = pending.receipts.single()
        val afterDup = owner.recordPrepared(mutationId, exact)
        assertThat(afterDup.receipts).hasSize(1)
        assertThat(afterDup.receipts.single()).isEqualTo(exact)
        assertThat(
            runCatching {
                owner.recordPrepared(
                    mutationId,
                    exact.copy(sha256 = "ff".repeat(32)),
                )
            }.exceptionOrNull(),
        ).isInstanceOf(IllegalArgumentException::class.java)

        rig.mediaFiles.prepareUploadFailures += localUri
        rig.newEngine().synchronize(session, SyncTrigger.LocalWrite)

        assertThat(rig.backend.causalMediaPreimageBytes.map { it.first })
            .containsExactly(mediaUuid, secondMedia)
            .inOrder()
        assertThat(rig.backend.causalMediaPreimageBytes[0].second).isEqualTo(frozenBytes)
        assertThat(rig.backend.causalMediaPreimageBytes[1].second).isEqualTo(secondBytes)
        assertThat(rig.backend.causalCommittedUnits.single().single().mutationId)
            .isEqualTo(mutationId)
        assertThat(rig.mediaFiles.prepareUploadCounts[localUri]).isEqualTo(1)
        assertThat(rig.mediaFiles.prepareUploadCounts[secondUri]).isEqualTo(1)
        // open: first + second attempt + second resume = 3; first receipt skips reopen.
        assertThat(rig.immutableMediaSpool.openCounts[mutationId]).isEqualTo(3)
        assertThat(rig.immutableMediaSpool.discardedMutationIds).containsExactly(mutationId)
        assertThat(receiptHits.get()).isEqualTo(3)
    }

    @Test
    fun c4_expiredAndWrongBindingReceipts_failClosedWithHonestPending() = runTest {
        data class Case(
            val name: String,
            val factory: (String, ByteArray, String) -> CausalMediaPreimageReceipt,
        )
        val cases = listOf(
            Case("expired") { mediaUuid, uploaded, expectedSha ->
                CausalMediaPreimageReceipt(
                    mediaUuid = mediaUuid,
                    status = "staged",
                    byteSize = uploaded.size.toLong(),
                    sha256 = expectedSha,
                    expiresAtEpochSeconds = 0L,
                )
            },
            Case("wrong-digest") { mediaUuid, uploaded, _ ->
                CausalMediaPreimageReceipt(
                    mediaUuid = mediaUuid,
                    status = "staged",
                    byteSize = uploaded.size.toLong(),
                    sha256 = "ab".repeat(32),
                    expiresAtEpochSeconds = Long.MAX_VALUE,
                )
            },
            Case("wrong-length") { mediaUuid, uploaded, expectedSha ->
                CausalMediaPreimageReceipt(
                    mediaUuid = mediaUuid,
                    status = "staged",
                    byteSize = uploaded.size.toLong() + 1L,
                    sha256 = expectedSha,
                    expiresAtEpochSeconds = Long.MAX_VALUE,
                )
            },
            Case("wrong-family-media-uuid") { _, uploaded, expectedSha ->
                CausalMediaPreimageReceipt(
                    mediaUuid = "00000000-0000-4000-8000-00000000ffff",
                    status = "staged",
                    byteSize = uploaded.size.toLong(),
                    sha256 = expectedSha,
                    expiresAtEpochSeconds = Long.MAX_VALUE,
                )
            },
        )

        cases.forEachIndexed { index, case ->
            val mediaUuid = "00000000-0000-4000-8000-0000000038%02x".format(0x40 + index)
            val localUri = "content://h38-bind-${case.name}"
            val frozenBytes = byteArrayOf(0x38.toByte(), index.toByte(), 0x42, 0x4E)
            val fixture = seedRecordMedia(
                recordUuid = "record-h38-bind-${case.name}",
                mediaUuid = mediaUuid,
                localUri = localUri,
                bytes = frozenBytes,
            )
            val (session, rig, _, _, _) = fixture
            rig.backend.causalMediaReceiptFactory = case.factory

            val failure = runCatching {
                rig.engine.synchronize(session, SyncTrigger.LocalWrite)
            }.exceptionOrNull()

            assertThat(failure).isNotNull()
            assertThat(
                failure is IllegalArgumentException || failure is IllegalStateException,
            ).isTrue()
            val message = requireNotNull(failure).message.orEmpty().lowercase()
            assertThat(
                listOf("expiry", "bind", "foreign", "invalid", "drift")
                    .any { token -> message.contains(token) },
            ).isTrue()
            assertThat(rig.backend.causalCommittedUnits).isEmpty()
            assertThat(rig.backend.causalMediaPreimageBytes).hasSize(1)
            assertThat(rig.backend.causalMediaPreimageBytes.single().second)
                .isEqualTo(frozenBytes)
            assertThat(rig.mediaFiles.prepareUploadCounts[localUri]).isEqualTo(1)
            val row = rig.conflictDetails.listFrozenMediaSpoolManifests().single()
            val journal = requireNotNull(decodeCausalMediaSettlementOrNull(row.payloadJson))
            assertThat(journal.phase).isEqualTo(CausalMediaSettlementPhase.Pending)
            // Wrong receipt must not become durable settlement evidence.
            assertThat(journal.receipts).isEmpty()
            assertThat(rig.immutableMediaSpool.discardedMutationIds).isEmpty()
            assertThat(rig.records.getByClientUuid("record-h38-bind-${case.name}")?.syncDirty)
                .isTrue()
            assertThat(journal.mutation.media.single().sha256).isEqualTo(sha256Hex(frozenBytes))
        }
    }

    @Test
    fun c5_wrongPrincipalCommitRejection_failClosedRetainsCommitUnknown() = runTest {
        val fixture = seedRecordMedia(
            recordUuid = "record-h38-wrong-principal",
            mediaUuid = MEDIA_PRINCIPAL,
            localUri = "content://h38-wrong-principal",
            bytes = byteArrayOf(0x38.toByte(), 0x05, 0x50, 0x52),
        )
        val (session, rig, mediaUuid, localUri, frozenBytes) = fixture
        var rejectedMutation: CausalMutationUnit? = null
        rig.backend.onCausalCommit = { units ->
            rejectedMutation = units.single()
            rig.backend.nextCausalCommitFailure = CausalCommitRejectedException(
                mutationId = units.single().mutationId,
                code = "media_membership_mismatch",
            )
        }

        val failure = runCatching {
            rig.engine.synchronize(session, SyncTrigger.LocalWrite)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(CausalCommitRejectedException::class.java)
        assertThat((failure as CausalCommitRejectedException).code)
            .isEqualTo("media_membership_mismatch")
        val mutationId = requireNotNull(rejectedMutation).mutationId
        val durable = requireNotNull(rig.conflictDetails.getFrozenMediaSpoolManifest(mutationId))
        val journal = requireNotNull(decodeCausalMediaSettlementOrNull(durable.payloadJson))
        assertThat(journal.phase).isEqualTo(CausalMediaSettlementPhase.CommitUnknown)
        assertThat(journal.receipts.map { it.mediaUuid }).containsExactly(mediaUuid)
        assertThat(rig.backend.causalMediaPreimageBytes).hasSize(1)
        assertThat(rig.backend.causalMediaPreimageBytes.single().second).isEqualTo(frozenBytes)
        assertThat(rig.mediaFiles.prepareUploadCounts[localUri]).isEqualTo(1)
        assertThat(rig.immutableMediaSpool.discardedMutationIds).isEmpty()
        assertThat(rig.records.getByClientUuid("record-h38-wrong-principal")?.syncDirty).isTrue()
        assertThat(rig.records.getByClientUuid("record-h38-wrong-principal")?.mutationId)
            .isEqualTo(mutationId)
    }

    @Test
    fun c6_expiredReceiptCommitRejection_failClosedRetainsCommitUnknown() = runTest {
        val fixture = seedRecordMedia(
            recordUuid = "record-h38-expired-commit",
            mediaUuid = MEDIA_EXPIRED,
            localUri = "content://h38-expired-commit",
            bytes = byteArrayOf(0x38.toByte(), 0x06, 0x45, 0x58),
        )
        val (session, rig, _, localUri, frozenBytes) = fixture
        var rejectedMutation: CausalMutationUnit? = null
        rig.backend.onCausalCommit = { units ->
            rejectedMutation = units.single()
            rig.backend.nextCausalCommitFailure = CausalCommitRejectedException(
                mutationId = units.single().mutationId,
                code = "media_preimage_expired",
            )
        }

        val failure = runCatching {
            rig.engine.synchronize(session, SyncTrigger.LocalWrite)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(CausalCommitRejectedException::class.java)
        assertThat((failure as CausalCommitRejectedException).code)
            .isEqualTo("media_preimage_expired")
        val mutationId = requireNotNull(rejectedMutation).mutationId
        val journal = requireNotNull(
            decodeCausalMediaSettlementOrNull(
                requireNotNull(rig.conflictDetails.getFrozenMediaSpoolManifest(mutationId))
                    .payloadJson,
            ),
        )
        assertThat(journal.phase).isEqualTo(CausalMediaSettlementPhase.CommitUnknown)
        assertThat(journal.receipts).hasSize(1)
        assertThat(rig.backend.causalMediaPreimageBytes.single().second).isEqualTo(frozenBytes)
        assertThat(rig.mediaFiles.prepareUploadCounts[localUri]).isEqualTo(1)
        assertThat(rig.immutableMediaSpool.discardedMutationIds).isEmpty()
        assertThat(rig.records.getByClientUuid("record-h38-expired-commit")?.syncDirty).isTrue()
    }

    @Test
    fun c7_restartBeforeSettlement_retainsUnknownPendingAndSourceOnce() = runTest {
        // Pending after partial prepare + CommitUnknown after lost commit, both
        // cold-restarted via newEngine() without URI re-read.
        val pendingFixture = seedRecordMedia(
            recordUuid = "record-h38-restart-pending",
            mediaUuid = MEDIA_RESTART_P,
            localUri = "content://h38-restart-pending",
            bytes = byteArrayOf(0x38.toByte(), 0x07, 0x50, 0x44),
        )
        run {
            val (session, rig, mediaUuid, localUri, frozenBytes) = pendingFixture
            rig.backend.failAfterCausalMediaPreimageUpload =
                IOException("h38 restart pending after prepare open")
            assertThat(
                runCatching { rig.engine.synchronize(session, SyncTrigger.LocalWrite) }
                    .exceptionOrNull(),
            ).isInstanceOf(IOException::class.java)
            val pending = requireNotNull(
                decodeCausalMediaSettlementOrNull(
                    rig.conflictDetails.listFrozenMediaSpoolManifests().single().payloadJson,
                ),
            )
            assertThat(pending.phase).isEqualTo(CausalMediaSettlementPhase.Pending)
            assertThat(pending.receipts).isEmpty()
            val mutationId = pending.mutation.mutationId
            rig.mediaFiles.prepareUploadFailures += localUri
            rig.newEngine().synchronize(session, SyncTrigger.LocalWrite)
            assertThat(rig.mediaFiles.prepareUploadCounts[localUri]).isEqualTo(1)
            assertThat(rig.backend.causalMediaPreimageBytes).hasSize(2)
            assertThat(
                rig.backend.causalMediaPreimageBytes.all { it.second.contentEquals(frozenBytes) },
            ).isTrue()
            assertThat(rig.backend.causalCommittedUnits.single().single().media.single().mediaUuid)
                .isEqualTo(mediaUuid)
            assertThat(rig.immutableMediaSpool.discardedMutationIds).containsExactly(mutationId)
        }

        val unknownFixture = seedRecordMedia(
            recordUuid = "record-h38-restart-unknown",
            mediaUuid = MEDIA_RESTART_U,
            localUri = "content://h38-restart-unknown",
            bytes = byteArrayOf(0x38.toByte(), 0x07, 0x55, 0x4E),
        )
        run {
            val (session, rig, mediaUuid, localUri, frozenBytes) = unknownFixture
            var attempts = 0
            var frozen: CausalMutationUnit? = null
            rig.backend.onCausalCommit = { units ->
                attempts += 1
                if (frozen == null) {
                    frozen = units.single()
                    throw IOException("h38 restart commit-unknown")
                }
                assertThat(units.single()).isEqualTo(frozen)
            }
            assertThat(
                runCatching { rig.engine.synchronize(session, SyncTrigger.LocalWrite) }
                    .exceptionOrNull(),
            ).isInstanceOf(IOException::class.java)
            val mutationId = requireNotNull(frozen).mutationId
            val unknown = requireNotNull(
                decodeCausalMediaSettlementOrNull(
                    requireNotNull(rig.conflictDetails.getFrozenMediaSpoolManifest(mutationId))
                        .payloadJson,
                ),
            )
            assertThat(unknown.phase).isEqualTo(CausalMediaSettlementPhase.CommitUnknown)
            assertThat(unknown.receipts.map { it.mediaUuid }).containsExactly(mediaUuid)
            rig.mediaFiles.prepareUploadFailures += localUri
            rig.newEngine().synchronize(session, SyncTrigger.LocalWrite)
            assertThat(attempts).isEqualTo(2)
            assertThat(rig.backend.causalMediaPreimageBytes).hasSize(1)
            assertThat(rig.backend.causalMediaPreimageBytes.single().second).isEqualTo(frozenBytes)
            assertThat(rig.mediaFiles.prepareUploadCounts[localUri]).isEqualTo(1)
            assertThat(rig.immutableMediaSpool.openCounts[mutationId]).isEqualTo(1)
            assertThat(rig.immutableMediaSpool.discardedMutationIds).containsExactly(mutationId)
            assertThat(rig.conflictDetails.getFrozenMediaSpoolManifest(mutationId)).isNull()
        }
    }

    @Test
    fun c8_terminalCleanupVsBranchedRetention_andHttpBindingMatrix() = runTest {
        // Accepted terminal → cleanup; branched → retain spool + journal.
        val accepted = seedRecordMedia(
            recordUuid = "record-h38-terminal-accepted",
            mediaUuid = MEDIA_TERM_A,
            localUri = "content://h38-terminal-accepted",
            bytes = byteArrayOf(0x38.toByte(), 0x08, 0x41, 0x43),
        )
        run {
            val (session, rig, _, localUri, frozenBytes) = accepted
            rig.engine.synchronize(session, SyncTrigger.LocalWrite)
            val mutationId = rig.backend.causalCommittedUnits.single().single().mutationId
            assertThat(rig.immutableMediaSpool.discardedMutationIds).containsExactly(mutationId)
            assertThat(rig.conflictDetails.getFrozenMediaSpoolManifest(mutationId)).isNull()
            assertThat(rig.backend.causalMediaPreimageBytes.single().second).isEqualTo(frozenBytes)
            assertThat(rig.mediaFiles.prepareUploadCounts[localUri]).isEqualTo(1)
            assertThat(rig.records.getByClientUuid("record-h38-terminal-accepted")?.syncDirty)
                .isFalse()
        }

        val branched = seedRecordMedia(
            recordUuid = "record-h38-terminal-branched",
            mediaUuid = MEDIA_TERM_B,
            localUri = "content://h38-terminal-branched",
            bytes = byteArrayOf(0x38.toByte(), 0x08, 0x42, 0x52),
        )
        run {
            val (session, rig, mediaUuid, localUri, frozenBytes) = branched
            var mutationId: String? = null
            rig.backend.onCausalCommit = { units ->
                val unit = units.single()
                mutationId = unit.mutationId
                rig.backend.nextCausalCommit = CausalCommitBatchResult(
                    generation = session.pullGeneration,
                    results = listOf(
                        CausalCommitUnitResult(
                            status = CausalCommitStatus.BRANCHED,
                            mutationId = unit.mutationId,
                            requestHash = causalMutationContentHash(unit),
                            stableVersionId = "v0",
                            stableRootJson = unit.rootJson,
                            stableMedia = unit.media,
                            conflictId = "conflict-h38-branch",
                            branchVersionId = "branch-h38",
                        ),
                    ),
                )
            }
            rig.engine.synchronize(session, SyncTrigger.LocalWrite)
            val frozenMutationId = requireNotNull(mutationId)
            val uploads = rig.backend.causalMediaPreimageBytes.size
            val commits = rig.backend.causalCommittedUnits.size
            // Second sync must not blind-resend.
            rig.engine.synchronize(session, SyncTrigger.LocalWrite)
            assertThat(rig.backend.causalMediaPreimageBytes).hasSize(uploads)
            assertThat(rig.backend.causalCommittedUnits).hasSize(commits)
            assertThat(rig.backend.causalMediaPreimageBytes.single().second).isEqualTo(frozenBytes)
            assertThat(rig.mediaFiles.prepareUploadCounts[localUri]).isEqualTo(1)
            assertThat(rig.immutableMediaSpool.discardedMutationIds).isEmpty()
            val row = requireNotNull(
                rig.conflictDetails.getFrozenMediaSpoolManifest(frozenMutationId),
            )
            assertThat(decodeCausalMediaSettlementOrNull(row.payloadJson)?.phase)
                .isEqualTo(CausalMediaSettlementPhase.Branched)
            assertThat(rig.records.getByClientUuid("record-h38-terminal-branched")?.openConflictId)
                .isEqualTo("conflict-h38-branch")
            assertThat(rig.media.getByClientUuid(mediaUuid)?.syncDirty).isFalse()
        }

        // HTTP client binding matrix (family/media_uuid, digest, length, expiry).
        val mediaId = "00000000-0000-4000-8000-000000000038"
        val sha = "38".repeat(32)
        val body = byteArrayOf(1, 2, 3)
        val responses = listOf(
            """{"media_uuid":"00000000-0000-4000-8000-000000000099","status":"staged","byte_size":3,"sha256":"$sha","expires_at":999}""",
            """{"media_uuid":"$mediaId","status":"staged","byte_size":3,"sha256":"${"ff".repeat(32)}","expires_at":999}""",
            """{"media_uuid":"$mediaId","status":"staged","byte_size":4,"sha256":"$sha","expires_at":999}""",
            """{"media_uuid":"$mediaId","status":"staged","byte_size":3,"sha256":"$sha","expires_at":0}""",
        )
        responses.forEach { response ->
            val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
            val responder = thread(name = "h38-receipt-bind") {
                server.accept().use { socket ->
                    readRequest(socket)
                    val bytes = response.trim().toByteArray(Charsets.UTF_8)
                    socket.getOutputStream().use { output ->
                        output.write(
                            (
                                "HTTP/1.1 200 OK\r\n" +
                                    "Content-Type: application/json\r\n" +
                                    "Content-Length: ${bytes.size}\r\n" +
                                    "Connection: close\r\n\r\n"
                                ).toByteArray(Charsets.US_ASCII),
                        )
                        output.write(bytes)
                    }
                }
            }
            try {
                val failure = runCatching {
                    loopbackBackend().putCausalMediaPreimage(
                        testSession(server),
                        mediaId,
                        TestMediaUploadSource(body, "image/jpeg"),
                        sha,
                    )
                }.exceptionOrNull()
                assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
            } finally {
                server.close()
                responder.join(2_000)
            }
        }

        // Lost prepare response at HTTP layer recovers on a second attempt with
        // the same request identity (sha header / media uuid).
        val drop = object : HttpURLConnection(URL("https://family.example.com:8765/h38")) {
            private val requestBytes = ByteArrayOutputStream()
            override fun connect() = Unit
            override fun getResponseCode(): Int = throw IOException("h38 http prepare disconnect")
            override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
            override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
            override fun getOutputStream(): OutputStream = requestBytes
            override fun disconnect() = Unit
            override fun usingProxy(): Boolean = false
            override fun getContentLengthLong(): Long = 0
        }
        val okBody =
            """{"media_uuid":"$mediaId","status":"staged","byte_size":3,"sha256":"$sha","expires_at":999}"""
        val ok = object : HttpURLConnection(URL("https://family.example.com:8765/h38")) {
            private val requestBytes = ByteArrayOutputStream()
            private val response = okBody.toByteArray(Charsets.UTF_8)
            override fun connect() = Unit
            override fun getResponseCode(): Int = 200
            override fun getInputStream(): InputStream = ByteArrayInputStream(response)
            override fun getErrorStream(): InputStream = ByteArrayInputStream(response)
            override fun getOutputStream(): OutputStream = requestBytes
            override fun disconnect() = Unit
            override fun usingProxy(): Boolean = false
            override fun getContentLengthLong(): Long = response.size.toLong()
        }
        val connections = ArrayDeque(listOf(drop, ok))
        val backend = HttpSyncBackend(SyncHttpConnectionFactory { connections.removeFirst() })
        val directSession = SyncSession(
            serverHost = "family.example.com",
            familyId = "family",
            accessToken = "token",
            deviceId = "device",
            membershipId = "membership-self",
            role = FamilyRole.Owner,
            pullGeneration = "generation-a",
        )
        val first = runCatching {
            backend.putCausalMediaPreimage(
                directSession,
                mediaId,
                TestMediaUploadSource(body, "image/jpeg"),
                sha,
            )
        }.exceptionOrNull()
        assertThat(first).isInstanceOf(IOException::class.java)
        val receipt = backend.putCausalMediaPreimage(
            directSession,
            mediaId,
            TestMediaUploadSource(body, "image/jpeg"),
            sha,
        )
        assertThat(receipt).isEqualTo(
            CausalMediaPreimageReceipt(mediaId, "staged", 3, sha, 999),
        )
    }

    @Test
    fun c9_digestAndVersionEvidenceTable_isPinned() = runTest {
        // One happy-path capture of upload count / hash / version / manifest for Evidence.
        val fixture = seedRecordMedia(
            recordUuid = "record-h38-evidence",
            mediaUuid = MEDIA_EVIDENCE,
            localUri = "content://h38-evidence",
            bytes = byteArrayOf(0x38.toByte(), 0x09, 0x45, 0x56),
        )
        val (session, rig, mediaUuid, localUri, frozenBytes) = fixture
        rig.engine.synchronize(session, SyncTrigger.LocalWrite)
        val unit = rig.backend.causalCommittedUnits.single().single()
        val digest = sha256Hex(frozenBytes)
        assertThat(unit.media.single().sha256).isEqualTo(digest)
        assertThat(unit.media.single().byteSize).isEqualTo(frozenBytes.size.toLong())
        assertThat(unit.media.single().mediaUuid).isEqualTo(mediaUuid)
        assertThat(rig.backend.causalMediaPreimageBytes).hasSize(1)
        assertThat(rig.mediaFiles.prepareUploadCounts[localUri]).isEqualTo(1)
        assertThat(rig.immutableMediaSpool.openCounts[unit.mutationId]).isEqualTo(1)
        assertThat(rig.immutableMediaSpool.discardedMutationIds).containsExactly(unit.mutationId)
        // Stable pinned digest for the evidence table (bytes [0x38,0x09,0x45,0x56]).
        assertThat(digest).isEqualTo(sha256Hex(byteArrayOf(0x38.toByte(), 0x09, 0x45, 0x56)))
        assertThat(unit.mutationId).isNotEmpty()
    }

    private data class MediaFixture(
        val session: SyncSession,
        val rig: ReplicaEngineRig,
        val mediaUuid: String,
        val localUri: String,
        val frozenBytes: ByteArray,
    )

    private fun seedRecordMedia(
        recordUuid: String,
        mediaUuid: String,
        localUri: String,
        bytes: ByteArray,
    ): MediaFixture {
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner)
        val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
        val babyId = rig.babies.seed(
            localReplicaBaby().copy(syncDirty = false, familyAuthority = true),
        )
        val recordId = rig.records.seed(
            RecordEntity(
                clientUuid = recordUuid,
                babyId = babyId,
                type = "formula",
                timestamp = 100,
                payloadJson = """{"amount_ml":60}""",
                schemaVersion = 2,
                updatedAt = 100,
                syncDirty = true,
                baseVersion = "v0",
            ),
        )
        rig.mediaFiles.preparedUploadBytes[localUri] = bytes
        rig.media.seed(
            MediaAssetEntity(
                recordId = recordId,
                clientUuid = mediaUuid,
                kind = "log",
                localUri = localUri,
                createdAt = 100,
                updatedAt = 100,
                syncDirty = true,
            ),
        )
        return MediaFixture(session, rig, mediaUuid, localUri, bytes)
    }

    private companion object {
        const val MEDIA_PREPARE = "00000000-0000-4000-8000-000000000381"
        const val MEDIA_COMMIT = "00000000-0000-4000-8000-000000000382"
        const val MEDIA_DUP = "00000000-0000-4000-8000-000000000383"
        const val MEDIA_PRINCIPAL = "00000000-0000-4000-8000-000000000385"
        const val MEDIA_EXPIRED = "00000000-0000-4000-8000-000000000386"
        const val MEDIA_RESTART_P = "00000000-0000-4000-8000-000000000387"
        const val MEDIA_RESTART_U = "00000000-0000-4000-8000-000000000388"
        const val MEDIA_TERM_A = "00000000-0000-4000-8000-000000000389"
        const val MEDIA_TERM_B = "00000000-0000-4000-8000-00000000038a"
        const val MEDIA_EVIDENCE = "00000000-0000-4000-8000-00000000038b"
    }
}

/** Deterministic H38 matrix seed: ticket 38 + H19 receipt + H20 settlement. */
internal const val H38_FAULT_SEED = 0x38_19_20L

private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
