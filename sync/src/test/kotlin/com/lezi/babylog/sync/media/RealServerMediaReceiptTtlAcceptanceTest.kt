package com.lezi.babylog.sync.media

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.sync.SyncTrigger
import com.lezi.babylog.sync.engine.decodeCausalMediaSettlementOrNull
import java.io.File
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** US018: test-only server clock, real 24h staging TTL and production startup GC. */
class RealServerMediaReceiptTtlAcceptanceTest {
    @Test
    fun partialReceiptExpiryConfirmsAbsentCommitThenRestagesOriginalSpool() = runTest {
        RealServerMediaReceiptFaultFixture.open(controlledReceiptClock = true).use { fixture ->
            val first = fixture.seedRecordMedia(
                UUID.randomUUID().toString(), "18000000-0000-4000-8000-000000000001",
                "content://synthetic-ttl/first", byteArrayOf(18, 1, 2, 3),
            )
            val record = requireNotNull(fixture.records.getByClientUuid(first.recordUuid))
            val secondUuid = "18000000-0000-4000-8000-000000000002"
            val secondUri = "content://synthetic-ttl/second"
            fixture.mediaFiles.preparedUploadBytes[secondUri] = byteArrayOf(18, 4, 5, 6)
            fixture.media.seed(MediaAssetEntity(
                clientUuid = secondUuid, recordId = record.id, kind = "log",
                localUri = secondUri, createdAt = record.updatedAt, updatedAt = record.updatedAt,
            ))
            val clock = File(fixture.server.dataRoot, "receipt-test-clock")
            val initialTime = clock.readText().toLong()
            val firstPrepared = java.util.concurrent.CountDownLatch(1)
            fixture.proxy.beforePrepareForward = { path ->
                if (path.endsWith(secondUuid)) {
                    check(firstPrepared.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
                        "first real media preparation did not reach the durable response boundary"
                    }
                }
            }
            fixture.proxy.afterPrepareForward = {
                // Uploads are concurrent. Hold the second request before forwarding,
                // until the first real response has durably bound its earlier clock.
                fixture.server.setReceiptClock(initialTime + 1)
                firstPrepared.countDown()
            }
            fixture.proxy.disconnectBeforeCommitForward = true
            assertThat(runCatching {
                fixture.engine.synchronize(fixture.session, SyncTrigger.LocalWrite)
            }.exceptionOrNull()).isNotNull()
            fixture.proxy.afterPrepareForward = null
            fixture.proxy.beforePrepareForward = null
            val pending = requireNotNull(decodeCausalMediaSettlementOrNull(
                fixture.conflictDetails.listFrozenMediaSpoolManifests().single().payloadJson,
            ))
            assertThat(pending.receipts).hasSize(2)
            assertThat(pending.receipts.single { it.mediaUuid == first.mediaUuid }.expiresAtEpochSeconds)
                .isEqualTo(initialTime + 24 * 60 * 60)
            assertThat(pending.receipts.single { it.mediaUuid == secondUuid }.expiresAtEpochSeconds)
                .isEqualTo(initialTime + 24 * 60 * 60 + 1)
            assertThat(fixture.recordVersionCount()).isEqualTo(0)
            assertThat(fixture.stagingCount("staged")).isEqualTo(2)
            val originalBody = fixture.proxy.commitBodies.last()
            fixture.server.advanceReceiptClockAndRestart(initialTime + 24 * 60 * 60)
            assertThat(fixture.stagingCount("staged")).isEqualTo(1)
            assertThat(fixture.sqlite("SELECT COUNT(*) FROM causal_media_staging WHERE media_uuid = '${first.mediaUuid}';").toInt())
                .isEqualTo(0)
            assertThat(File(fixture.server.dataRoot,
                "media/.causal-stage/${fixture.session.familyId}/${first.mediaUuid}").exists()).isFalse()
            fixture.refreshAfterClockAdvance()
            fixture.mediaFiles.prepareUploadFailures += listOf(first.localUri, secondUri)
            fixture.proxy.disconnectBeforeCommitForward = false
            fixture.proxy.forwardedPaths.clear()
            fixture.proxy.commitBodies.clear()
            fixture.newEngine().synchronize(fixture.session, SyncTrigger.LocalWrite)
            val paths = fixture.proxy.forwardedPaths.toList()
            val firstCommit = paths.indexOf("POST /v1/causal/commit")
            val firstPrepare = paths.indexOfFirst { it.startsWith("PUT /v1/causal/media/") }
            assertThat(firstCommit).isAtLeast(0)
            assertThat(firstPrepare).isGreaterThan(firstCommit)
            assertThat(fixture.proxy.commitBodies.toList()).isNotEmpty()
            fixture.proxy.commitBodies.forEach { assertThat(it).isEqualTo(originalBody) }
            assertThat(fixture.recordVersionCount()).isEqualTo(1)
            assertThat(fixture.mediaFiles.prepareUploadCounts[first.localUri]).isEqualTo(1)
            assertThat(fixture.mediaFiles.prepareUploadCounts[secondUri]).isEqualTo(1)
            assertThat(fixture.conflictDetails.listFrozenMediaSpoolManifests()).isEmpty()
        }
    }

    @Test
    fun successfulCommitLostResponseAfterTtlAndGcReplaysWithoutAnyPrepare() = runTest {
        RealServerMediaReceiptFaultFixture.open(controlledReceiptClock = true).use { fixture ->
            val seeded = fixture.seedRecordMedia(
                UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                "content://synthetic-ttl/accepted", byteArrayOf(18, 7, 8, 9),
            )
            val initialTime = File(fixture.server.dataRoot, "receipt-test-clock").readText().toLong()
            fixture.proxy.dropAfterDurable = DeterministicHttpsFaultProxy.DropAfterDurable.NextCommitResponse
            assertThat(runCatching {
                fixture.engine.synchronize(fixture.session, SyncTrigger.LocalWrite)
            }.exceptionOrNull()).isNotNull()
            assertThat(fixture.recordVersionCount()).isEqualTo(1)
            val body = fixture.proxy.commitBodies.last()
            fixture.server.advanceReceiptClockAndRestart(initialTime + 24 * 60 * 60 + 1)
            // Live/version-referenced consumed rows must survive GC; only the private
            // preimage has already been promoted out of staging by real commit.
            assertThat(fixture.stagingCount("consumed")).isEqualTo(1)
            assertThat(File(fixture.server.dataRoot,
                "media/.causal-stage/${fixture.session.familyId}/${seeded.mediaUuid}").exists()).isFalse()
            assertThat(File(fixture.server.dataRoot,
                "media/${fixture.session.familyId}/${seeded.mediaUuid}").readBytes()).isEqualTo(seeded.frozenBytes)
            fixture.refreshAfterClockAdvance()
            fixture.mediaFiles.prepareUploadFailures += seeded.localUri
            val prepares = fixture.prepareForwards()
            fixture.proxy.commitBodies.clear()
            fixture.newEngine().synchronize(fixture.session, SyncTrigger.LocalWrite)
            assertThat(fixture.prepareForwards()).isEqualTo(prepares)
            assertThat(fixture.recordVersionCount()).isEqualTo(1)
            fixture.proxy.commitBodies.forEach { assertThat(it).isEqualTo(body) }
            assertThat(fixture.records.getByClientUuid(seeded.recordUuid)?.syncDirty).isFalse()
            assertThat(fixture.conflictDetails.listFrozenMediaSpoolManifests()).isEmpty()
        }
    }

    private suspend fun RealServerMediaReceiptFaultFixture.refreshAfterClockAdvance() {
        val refreshed = backend.refresh(proxy.endpoint, session.refreshToken, UUID.randomUUID().toString())
        session = session.copy(
            accessToken = refreshed.accessToken,
            refreshToken = refreshed.refreshToken,
            accessExpiresAtEpochSeconds = refreshed.accessExpiresAtEpochSeconds,
        )
        preferences.saveSession(session)
    }
}
