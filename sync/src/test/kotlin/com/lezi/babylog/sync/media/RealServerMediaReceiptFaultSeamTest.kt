package com.lezi.babylog.sync.media

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.sync.backend.CausalCommitRejectedException
import com.lezi.babylog.sync.engine.CausalMediaSettlementPhase
import com.lezi.babylog.sync.engine.decodeCausalMediaSettlementOrNull
import com.lezi.babylog.sync.SyncTrigger
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * H44 acceptance: ReplicaSyncEngine → HttpSyncBackend → deterministic HTTPS
 * fault proxy → isolated lezi-sync.
 */
class RealServerMediaReceiptFaultSeamTest {
    @Test
    fun c0_seedAndContractPins() {
        assertThat(H44_FAULT_SEED).isEqualTo(0x44_38_19L)
        assertThat(H44_FAULT_SEED and 0xFF).isEqualTo(0x19L)
        assertThat((H44_FAULT_SEED shr 8) and 0xFF).isEqualTo(0x38L)
        assertThat(H38_FAULT_SEED).isEqualTo(0x38_19_20L)
    }

    @Test
    fun c1_lostPrepareResponse_retriesExactBytesWithoutUriReread() = runTest {
        RealServerMediaReceiptFaultFixture.open().use { fixture ->
            val seeded = fixture.seedRecordMedia(
                recordUuid = RECORD_PREPARE,
                mediaUuid = MEDIA_PREPARE,
                localUri = "content://h44-lost-prepare",
                bytes = byteArrayOf(0x44, 0x01, 0x50, 0x52),
            )
            fixture.proxy.dropAfterDurable =
                DeterministicHttpsFaultProxy.DropAfterDurable.NextPrepareResponse

            assertLostDurableResponse(
                runCatching {
                    fixture.engine.synchronize(fixture.session, SyncTrigger.LocalWrite)
                }.exceptionOrNull(),
            )

            fixture.mediaFiles.preparedUploadBytes[seeded.localUri] =
                byteArrayOf(0xFF.toByte(), 0xEE.toByte())
            fixture.mediaFiles.prepareUploadFailures += seeded.localUri

            assertThat(fixture.proxy.droppedResponses.get()).isAtLeast(1)
            assertThat(fixture.stagingCount("staged")).isEqualTo(1)
            assertThat(fixture.stagingSha256(MEDIA_PREPARE)).isEqualTo(digest(seeded.frozenBytes))
            assertThat(fixture.recordVersionCount()).isEqualTo(0)
            assertThat(fixture.mediaFiles.prepareUploadCounts[seeded.localUri]).isEqualTo(1)
            val pending = requireNotNull(
                decodeCausalMediaSettlementOrNull(
                    fixture.conflictDetails.listFrozenMediaSpoolManifests().single().payloadJson,
                ),
            )
            assertThat(pending.phase).isEqualTo(CausalMediaSettlementPhase.Pending)
            assertThat(pending.receipts).isEmpty()
            val mutationId = pending.mutation.mutationId
            assertThat(fixture.immutableMediaSpool.openCounts[mutationId] ?: 0).isAtLeast(1)

            fixture.engine.synchronize(fixture.session, SyncTrigger.LocalWrite)

            assertThat(fixture.prepareForwards()).isAtLeast(2)
            assertThat(fixture.prepareForwards()).isAtMost(4)
            assertThat(fixture.commitForwards()).isAtLeast(1)
            assertThat(fixture.commitForwards()).isAtMost(4)
            assertThat(fixture.stagingCount("staged")).isEqualTo(0)
            check(fixture.recordVersionCount() == 1) {
                "c1 versions=${fixture.recordVersionCount()} dump=${fixture.recordVersionDump()} " +
                    "receipts=${fixture.recordReceiptDump()} commits=${fixture.commitForwards()}"
            }
            assertThat(fixture.recordReceiptCount()).isAtLeast(1)
            assertThat(fixture.recordReceiptDump()).contains(seeded.recordUuid)
            assertThat(fixture.mediaFiles.prepareUploadCounts[seeded.localUri]).isEqualTo(1)
            assertThat(fixture.immutableMediaSpool.openCounts[mutationId]).isAtLeast(2)
            assertThat(fixture.immutableMediaSpool.discardedMutationIds).containsExactly(mutationId)
            assertThat(fixture.conflictDetails.getFrozenMediaSpoolManifest(mutationId)).isNull()
            assertThat(fixture.records.getByClientUuid(seeded.recordUuid)?.syncDirty).isFalse()
        }
    }

    @Test
    fun c2_lostCommitResponse_replaysExactMutationWithoutReuploadOrVersionDrift() = runTest {
        RealServerMediaReceiptFaultFixture.open().use { fixture ->
            val seeded = fixture.seedRecordMedia(
                recordUuid = RECORD_COMMIT,
                mediaUuid = MEDIA_COMMIT,
                localUri = "content://h44-lost-commit",
                bytes = byteArrayOf(0x44, 0x02, 0x43, 0x4D),
            )
            fixture.proxy.dropAfterDurable =
                DeterministicHttpsFaultProxy.DropAfterDurable.NextCommitResponse

            assertLostDurableResponse(
                runCatching {
                    fixture.engine.synchronize(fixture.session, SyncTrigger.LocalWrite)
                }.exceptionOrNull(),
            )

            fixture.mediaFiles.preparedUploadBytes[seeded.localUri] = byteArrayOf(9, 9, 9, 9)
            fixture.mediaFiles.prepareUploadFailures += seeded.localUri

            assertThat(fixture.proxy.droppedResponses.get()).isAtLeast(1)
            assertThat(fixture.prepareForwards()).isEqualTo(1)
            assertThat(fixture.commitForwards()).isAtLeast(1)
            assertThat(fixture.commitForwards()).isAtMost(4)
            assertThat(fixture.stagingCount("staged")).isEqualTo(0)
            assertThat(fixture.recordVersionCount()).isEqualTo(1)
            assertThat(fixture.recordReceiptCount()).isAtLeast(1)
            assertThat(fixture.recordReceiptDump()).contains(RECORD_COMMIT)
            val mutationId = requireNotNull(
                fixture.records.getByClientUuid(seeded.recordUuid)?.mutationId,
            )
            val journal = requireNotNull(
                decodeCausalMediaSettlementOrNull(
                    requireNotNull(
                        fixture.conflictDetails.getFrozenMediaSpoolManifest(mutationId),
                    ).payloadJson,
                ),
            )
            assertThat(journal.phase).isEqualTo(CausalMediaSettlementPhase.CommitUnknown)
            assertThat(journal.receipts.map { it.mediaUuid }).containsExactly(MEDIA_COMMIT)
            assertThat(fixture.mediaFiles.prepareUploadCounts[seeded.localUri]).isEqualTo(1)
            assertThat(fixture.immutableMediaSpool.openCounts[mutationId]).isEqualTo(1)

            fixture.newEngine().synchronize(fixture.session, SyncTrigger.LocalWrite)

            assertThat(fixture.prepareForwards()).isEqualTo(1)
            assertThat(fixture.commitForwards()).isAtLeast(2)
            assertThat(fixture.commitForwards()).isAtMost(4)
            assertThat(fixture.recordVersionCount()).isEqualTo(1)
            assertThat(fixture.recordReceiptCount()).isAtLeast(1)
            assertThat(fixture.mediaFiles.prepareUploadCounts[seeded.localUri]).isEqualTo(1)
            assertThat(fixture.immutableMediaSpool.openCounts[mutationId]).isEqualTo(1)
            assertThat(fixture.immutableMediaSpool.discardedMutationIds).containsExactly(mutationId)
            assertThat(fixture.conflictDetails.getFrozenMediaSpoolManifest(mutationId)).isNull()
            assertThat(fixture.records.getByClientUuid(seeded.recordUuid)?.syncDirty).isFalse()
        }
    }

    @Test
    fun c3_realServerBindingAndExpiry_failClosedRetainsPendingOrUnknown() = runTest {
        data class Case(
            val name: String,
            val suffix: String,
            val mutate: (RealServerMediaReceiptFaultFixture, String) -> Unit,
            val expectedCode: String,
        )
        val cases = listOf(
            Case("expired", "e1", { fixture, mediaUuid ->
                fixture.sqlite(
                    "UPDATE causal_media_staging SET expires_at = 1 WHERE media_uuid = '$mediaUuid';",
                )
            }, "media_preimage_expired"),
            Case("wrong-digest", "e2", { fixture, mediaUuid ->
                fixture.sqlite(
                    "UPDATE causal_media_staging SET sha256 = '${"ab".repeat(32)}' " +
                        "WHERE media_uuid = '$mediaUuid';",
                )
            }, "media_sha256_mismatch"),
            Case("wrong-length", "e3", { fixture, mediaUuid ->
                fixture.sqlite(
                    "UPDATE causal_media_staging SET byte_size = byte_size + 1 " +
                        "WHERE media_uuid = '$mediaUuid';",
                )
            }, "media_byte_size_mismatch"),
            Case("wrong-principal", "e4", { fixture, mediaUuid ->
                fixture.sqlite(
                    "UPDATE causal_media_staging SET membership_id = " +
                        "'00000000-0000-4000-8000-00000000ffff' " +
                        "WHERE media_uuid = '$mediaUuid';",
                )
            }, "media_membership_mismatch"),
            // Staging is family-keyed. A foreign family_id is a lookup miss,
            // not H38's synthetic media_family_mismatch hook.
            Case("wrong-family", "e5", { fixture, mediaUuid ->
                fixture.sqlite(
                    "INSERT OR IGNORE INTO families(id, created_at, name) " +
                        "VALUES ('00000000-0000-4000-8000-00000000eeee', 1, 'other');" +
                        "UPDATE causal_media_staging SET family_id = " +
                        "'00000000-0000-4000-8000-00000000eeee' " +
                        "WHERE media_uuid = '$mediaUuid';",
                )
            }, "missing_media_bytes"),
        )
        cases.forEach { case ->
            RealServerMediaReceiptFaultFixture.open().use { fixture ->
                val mediaUuid = h44MediaUuid(case.suffix)
                val seeded = fixture.seedRecordMedia(
                    recordUuid = h44MediaUuid("c${case.suffix}"),
                    mediaUuid = mediaUuid,
                    localUri = "content://h44-bind-${case.name}",
                    bytes = byteArrayOf(0x44, 0x03, case.suffix.hashCode().toByte(), 0x42),
                )
                fixture.proxy.afterPrepareForward = { case.mutate(fixture, mediaUuid) }

                val failure = runCatching {
                    fixture.engine.synchronize(fixture.session, SyncTrigger.LocalWrite)
                }.exceptionOrNull()

                assertThat(failure).isInstanceOf(CausalCommitRejectedException::class.java)
                assertThat((failure as CausalCommitRejectedException).code)
                    .isEqualTo(case.expectedCode)
                assertThat(fixture.prepareForwards()).isAtLeast(1)
                assertThat(fixture.commitForwards()).isAtLeast(1)
                assertThat(fixture.mediaFiles.prepareUploadCounts[seeded.localUri]).isEqualTo(1)
                assertThat(fixture.immutableMediaSpool.discardedMutationIds).isEmpty()
                assertThat(fixture.records.getByClientUuid(seeded.recordUuid)?.syncDirty).isTrue()
                val journal = requireNotNull(
                    decodeCausalMediaSettlementOrNull(
                        fixture.conflictDetails.listFrozenMediaSpoolManifests().single().payloadJson,
                    ),
                )
                assertThat(journal.phase).isEqualTo(CausalMediaSettlementPhase.CommitUnknown)
                assertThat(journal.receipts).isNotEmpty()
            }
        }
    }

    @Test
    fun c4_restartReplay_doesNotDuplicateUploadOrVersion() = runTest {
        RealServerMediaReceiptFaultFixture.open().use { fixture ->
            val seeded = fixture.seedRecordMedia(
                recordUuid = RECORD_RESTART,
                mediaUuid = MEDIA_RESTART,
                localUri = "content://h44-restart",
                bytes = byteArrayOf(0x44, 0x04, 0x52, 0x53),
            )
            fixture.proxy.dropAfterDurable =
                DeterministicHttpsFaultProxy.DropAfterDurable.NextPrepareResponse
            assertLostDurableResponse(
                runCatching {
                    fixture.engine.synchronize(fixture.session, SyncTrigger.LocalWrite)
                }.exceptionOrNull(),
            )
            fixture.mediaFiles.prepareUploadFailures += seeded.localUri
            val mutationId = requireNotNull(
                decodeCausalMediaSettlementOrNull(
                    fixture.conflictDetails.listFrozenMediaSpoolManifests().single().payloadJson,
                ),
            ).mutation.mutationId
            assertThat(fixture.stagingCount("staged")).isEqualTo(1)

            fixture.newEngine().synchronize(fixture.session, SyncTrigger.LocalWrite)

            assertThat(fixture.prepareForwards()).isAtLeast(2)
            assertThat(fixture.prepareForwards()).isAtMost(4)
            assertThat(fixture.commitForwards()).isAtLeast(1)
            assertThat(fixture.commitForwards()).isAtMost(4)
            assertThat(fixture.recordVersionCount()).isEqualTo(1)
            assertThat(fixture.mediaFiles.prepareUploadCounts[seeded.localUri]).isEqualTo(1)
            assertThat(fixture.immutableMediaSpool.discardedMutationIds).containsExactly(mutationId)
        }
    }

    @Test
    fun c5_terminalAcceptedCleansSpool_unknownRetainsEvidence() = runTest {
        RealServerMediaReceiptFaultFixture.open().use { accepted ->
            val seeded = accepted.seedRecordMedia(
                recordUuid = RECORD_ACCEPTED,
                mediaUuid = MEDIA_ACCEPTED,
                localUri = "content://h44-accepted",
                bytes = byteArrayOf(0x44, 0x05, 0x41, 0x43),
            )
            accepted.engine.synchronize(accepted.session, SyncTrigger.LocalWrite)
            assertThat(accepted.immutableMediaSpool.discardedMutationIds).isNotEmpty()
            val mutationId = accepted.immutableMediaSpool.discardedMutationIds.single()
            assertThat(accepted.conflictDetails.getFrozenMediaSpoolManifest(mutationId)).isNull()
            assertThat(accepted.records.getByClientUuid(seeded.recordUuid)?.syncDirty).isFalse()
            assertThat(accepted.prepareForwards()).isEqualTo(1)
            assertThat(accepted.commitForwards()).isEqualTo(1)
            assertThat(accepted.recordVersionCount()).isEqualTo(1)
        }

        RealServerMediaReceiptFaultFixture.open().use { unknown ->
            val seeded = unknown.seedRecordMedia(
                recordUuid = RECORD_UNKNOWN,
                mediaUuid = MEDIA_UNKNOWN,
                localUri = "content://h44-unknown",
                bytes = byteArrayOf(0x44, 0x05, 0x55, 0x4E),
            )
            unknown.proxy.dropAfterDurable =
                DeterministicHttpsFaultProxy.DropAfterDurable.NextCommitResponse
            assertLostDurableResponse(
                runCatching {
                    unknown.engine.synchronize(unknown.session, SyncTrigger.LocalWrite)
                }.exceptionOrNull(),
            )
            val mutationId = requireNotNull(
                unknown.records.getByClientUuid(seeded.recordUuid)?.mutationId,
            )
            assertThat(unknown.immutableMediaSpool.discardedMutationIds).isEmpty()
            val journal = requireNotNull(
                decodeCausalMediaSettlementOrNull(
                    requireNotNull(
                        unknown.conflictDetails.getFrozenMediaSpoolManifest(mutationId),
                    ).payloadJson,
                ),
            )
            assertThat(journal.phase).isEqualTo(CausalMediaSettlementPhase.CommitUnknown)
            assertThat(unknown.records.getByClientUuid(seeded.recordUuid)?.syncDirty).isTrue()
        }
    }

    private companion object {
        const val RECORD_PREPARE = "00000000-0000-4000-8000-000000000541"
        const val RECORD_COMMIT = "00000000-0000-4000-8000-000000000542"
        const val RECORD_RESTART = "00000000-0000-4000-8000-000000000544"
        const val RECORD_ACCEPTED = "00000000-0000-4000-8000-000000000545"
        const val RECORD_UNKNOWN = "00000000-0000-4000-8000-000000000546"
        const val MEDIA_PREPARE = "00000000-0000-4000-8000-000000000441"
        const val MEDIA_COMMIT = "00000000-0000-4000-8000-000000000442"
        const val MEDIA_RESTART = "00000000-0000-4000-8000-000000000444"
        const val MEDIA_ACCEPTED = "00000000-0000-4000-8000-000000000445"
        const val MEDIA_UNKNOWN = "00000000-0000-4000-8000-000000000446"

        fun digest(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }

        fun assertLostDurableResponse(error: Throwable?) {
            assertThat(error).isNotNull()
            val lost = error is IOException ||
                (
                    error is IllegalArgumentException &&
                        error.message.orEmpty().contains("JSON 响应为空")
                    )
            assertThat(lost).isTrue()
        }
    }
}
