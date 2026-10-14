package com.lezi.babylog.sync.media

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.sync.backend.CausalCommitRejectedException
import com.lezi.babylog.sync.SyncTrigger
import com.lezi.babylog.sync.engine.CausalMediaSettlementPhase
import com.lezi.babylog.sync.engine.decodeCausalMediaSettlementOrNull
import java.io.IOException
import org.junit.Test
import kotlinx.coroutines.test.runTest

/** Real engine and HTTP backend against an isolated server; no production data or wall-clock wait. */
class RealServerMediaRenewalSeamTest {
    @Test
    fun consumedMedia_otherMemberPreservedPhotoEdit_usesOnlyEmptyPut() = runTest {
        RealServerMediaReceiptFaultFixture.open().use { fixture ->
            val ownerSession = fixture.session
            fixture.switchToNewMember()
            val seeded = fixture.seedRecordMedia(
                recordUuid = h44MediaUuid("c701"),
                mediaUuid = h44MediaUuid("a701"),
                localUri = "content://media-member-preserved",
                bytes = byteArrayOf(7, 0, 1, 4),
            )
            val uploaderMembership = fixture.session.membershipId
            fixture.engine.synchronize(fixture.session, SyncTrigger.LocalWrite)
            assertThat(fixture.stagingCount("consumed")).isEqualTo(1)
            val stagingBefore = fixture.sqlite(
                "SELECT membership_id || '|' || created_at || '|' || expires_at || '|' || " +
                    "consumed_at || '|' || sha256 || '|' || byte_size FROM causal_media_staging " +
                    "WHERE media_uuid = '${seeded.mediaUuid}';",
            )
            // Record ACL allows its author or owner to edit. The owner reuses a member's media.
            fixture.session = ownerSession.copy(pullCursor = fixture.preferences.current().pullCursor)
            fixture.preferences.saveSession(fixture.session)
            assertThat(fixture.session.membershipId).isNotEqualTo(uploaderMembership)
            // LocalWrite settles the upload but does not pull its published remote receipt.
            // Establish the owner's actual published-content identity through the normal pull.
            fixture.newEngine().synchronize(fixture.session, SyncTrigger.PullToRefresh)
            fixture.session = fixture.preferences.current()
            val record = requireNotNull(fixture.records.getByClientUuid(seeded.recordUuid))
            val asset = fixture.media.listForRecord(record.id).single()
            assertThat(asset.remoteUri).isNotNull()
            assertThat(fixture.media.hasPublishedContentIdentity(requireNotNull(asset.sha256))).isTrue()
            fixture.records.update(
                record.copy(
                    payloadJson = """{"amount_ml":90}""",
                    updatedAt = record.updatedAt + 1,
                    syncDirty = true,
                    mutationId = null,
                ),
            )
            val uploadsBefore = fixture.prepareForwards()
            val bodiesBefore = fixture.proxy.prepareBodySizes.size
            val opensBefore = fixture.immutableMediaSpool.openCounts.values.sum()

            fixture.newEngine().synchronize(fixture.session, SyncTrigger.LocalWrite)

            assertThat(fixture.prepareForwards() - uploadsBefore).isEqualTo(1)
            assertThat(fixture.proxy.prepareBodySizes.toList().drop(bodiesBefore)).containsExactly(0)
            assertThat(fixture.immutableMediaSpool.openCounts.values.sum()).isEqualTo(opensBefore)
            assertThat(fixture.records.getByClientUuid(seeded.recordUuid)?.syncDirty).isFalse()
            assertThat(fixture.recordVersionCount()).isEqualTo(2)
            assertThat(fixture.sqlite(
                "SELECT membership_id || '|' || created_at || '|' || expires_at || '|' || " +
                    "consumed_at || '|' || sha256 || '|' || byte_size FROM causal_media_staging " +
                    "WHERE media_uuid = '${seeded.mediaUuid}';",
            )).isEqualTo(stagingBefore)
        }
    }

    @Test
    fun cachedExpiredReceipt_repreparesOnce_thenRestartCommitsWithoutReplacingNewerEdit() = runTest {
        RealServerMediaReceiptFaultFixture.open().use { fixture ->
            val seeded = fixture.seedRecordMedia(
                recordUuid = h44MediaUuid("c703"),
                mediaUuid = h44MediaUuid("a703"),
                localUri = "content://media-cached-expired-receipt",
                bytes = byteArrayOf(7, 0, 3, 4),
            )
            fixture.proxy.afterPrepareForward = {
                fixture.proxy.afterPrepareForward = null
                fixture.sqlite(
                    "UPDATE causal_media_staging SET expires_at = 1 " +
                        "WHERE media_uuid = '${seeded.mediaUuid}';",
                )
            }
            val failure = runCatching {
                fixture.engine.synchronize(fixture.session, SyncTrigger.LocalWrite)
            }.exceptionOrNull()
            assertThat(failure).isInstanceOf(CausalCommitRejectedException::class.java)
            assertThat((failure as CausalCommitRejectedException).code)
                .isEqualTo("media_preimage_expired")
            assertThat(fixture.prepareForwards()).isEqualTo(2)
            assertThat(fixture.recordVersionCount()).isEqualTo(0)
            val journal = requireNotNull(decodeCausalMediaSettlementOrNull(
                fixture.conflictDetails.listFrozenMediaSpoolManifests().single().payloadJson,
            ))
            assertThat(journal.phase).isEqualTo(CausalMediaSettlementPhase.CommitUnknown)
            assertThat(journal.receipts).hasSize(1)
            val record = requireNotNull(fixture.records.getByClientUuid(seeded.recordUuid))
            fixture.records.update(record.copy(
                updatedAt = record.updatedAt + 1,
                payloadJson = """{"amount_ml":120}""",
                syncDirty = true,
            ))
            fixture.mediaFiles.prepareUploadFailures += seeded.localUri

            fixture.newEngine().synchronize(fixture.session, SyncTrigger.LocalWrite)

            assertThat(fixture.prepareForwards()).isEqualTo(2)
            assertThat(fixture.recordVersionCount()).isEqualTo(1)
            assertThat(fixture.recordReceiptDump()).contains(journal.mutation.mutationId)
            assertThat(fixture.immutableMediaSpool.discardedMutationIds)
                .containsExactly(journal.mutation.mutationId)
            val current = requireNotNull(fixture.records.getByClientUuid(seeded.recordUuid))
            assertThat(current.payloadJson).isEqualTo("""{"amount_ml":120}""")
            assertThat(current.updatedAt).isEqualTo(record.updatedAt + 1)
            assertThat(current.syncDirty).isTrue()
        }
    }

    @Test
    fun lostPrepareReceipt_expiredStaging_reuploadsFrozenBytesAndCommitsSameMutation() = runTest {
        RealServerMediaReceiptFaultFixture.open().use { fixture ->
            val seeded = fixture.seedRecordMedia(
                recordUuid = h44MediaUuid("c702"),
                mediaUuid = h44MediaUuid("a702"),
                localUri = "content://media-expired-lost-receipt",
                bytes = byteArrayOf(7, 0, 2, 4),
            )
            fixture.proxy.dropAfterDurable =
                DeterministicHttpsFaultProxy.DropAfterDurable.NextPrepareResponse
            val failure = runCatching {
                fixture.engine.synchronize(fixture.session, SyncTrigger.LocalWrite)
            }.exceptionOrNull()
            assertThat(failure is IOException ||
                (failure is IllegalArgumentException && failure.message.orEmpty().contains("JSON 响应为空")))
                .isTrue()
            val pending = requireNotNull(decodeCausalMediaSettlementOrNull(
                fixture.conflictDetails.listFrozenMediaSpoolManifests().single().payloadJson,
            ))
            assertThat(pending.phase).isEqualTo(CausalMediaSettlementPhase.Pending)
            assertThat(pending.receipts).isEmpty()
            val mutationId = pending.mutation.mutationId
            val createdAt = fixture.sqlite(
                "SELECT created_at FROM causal_media_staging WHERE media_uuid = '${seeded.mediaUuid}';",
            )
            // Expire only the isolated unconsumed row. This reaches the >TTL branch without sleeping.
            fixture.sqlite(
                "UPDATE causal_media_staging SET expires_at = 1 WHERE media_uuid = '${seeded.mediaUuid}';",
            )
            fixture.mediaFiles.prepareUploadFailures += seeded.localUri
            fixture.mediaFiles.preparedUploadBytes[seeded.localUri] = byteArrayOf(9, 9)
            val bodiesBefore = fixture.proxy.prepareBodySizes.size

            fixture.newEngine().synchronize(fixture.session, SyncTrigger.LocalWrite)

            assertThat(fixture.proxy.prepareBodySizes.toList().drop(bodiesBefore))
                .containsExactly(seeded.frozenBytes.size)
            assertThat(fixture.mediaFiles.prepareUploadCounts[seeded.localUri]).isEqualTo(1)
            assertThat(fixture.immutableMediaSpool.openCounts[mutationId]).isAtLeast(2)
            assertThat(fixture.stagingCount("consumed")).isEqualTo(1)
            assertThat(fixture.sqlite(
                "SELECT created_at FROM causal_media_staging WHERE media_uuid = '${seeded.mediaUuid}';",
            )).isEqualTo(createdAt)
            assertThat(fixture.sqlite(
                "SELECT expires_at FROM causal_media_staging WHERE media_uuid = '${seeded.mediaUuid}';",
            ).toLong()).isGreaterThan(1L)
            assertThat(fixture.recordVersionCount()).isEqualTo(1)
            assertThat(fixture.recordReceiptDump()).contains(mutationId)
            assertThat(fixture.immutableMediaSpool.discardedMutationIds).containsExactly(mutationId)
            assertThat(fixture.records.getByClientUuid(seeded.recordUuid)?.syncDirty).isFalse()
        }
    }
}
