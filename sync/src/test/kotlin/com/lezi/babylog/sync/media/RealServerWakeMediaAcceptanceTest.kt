package com.lezi.babylog.sync.media

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.core.database.RecordEntity
import com.lezi.babylog.core.database.causal.WakeObservationEntity
import com.lezi.babylog.sync.SyncTrigger
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/** US014: unmodified Rust wire -> HttpSyncBackend parser -> production ReplicaSyncEngine. */
class RealServerWakeMediaAcceptanceTest {
    @Test
    fun oneTwoAndThreeWakePhotosCarryTrustedIdentityToIndependentReplica() = runTest {
        for (count in 1..3) {
            RealServerMediaReceiptFaultFixture.open().use { sender ->
                val wake = sender.publishWake(count)
                val receiver = sender.freshReceiver()
                receiver.engine.synchronize(receiver.session, SyncTrigger.PullToRefresh)
                assertWake(receiver, wake)
                assertThat(receiver.preferences.current().pullCursor).isGreaterThan(0L)
            }
        }
    }

    @Test
    fun truncatedRealDownloadKeepsWakeInvisibleAndCursorThenRetryConverges() = runTest {
        RealServerMediaReceiptFaultFixture.open().use { sender ->
            val wake = sender.publishWake(3)
            val receiver = sender.freshReceiver()
            val cursor = receiver.preferences.current().pullCursor
            val dropsBefore = sender.proxy.droppedResponses.get()
            sender.proxy.forwardedPaths.clear()
            sender.proxy.truncateMediaDownloads = true
            val error = runCatching {
                receiver.engine.synchronize(receiver.session, SyncTrigger.PullToRefresh)
            }.exceptionOrNull()
            assertThat(error).isNotNull()
            assertThat(sender.proxy.droppedResponses.get()).isGreaterThan(dropsBefore)
            assertThat(sender.proxy.forwardedPaths.any { path ->
                wake.photos.any { (uuid, _) -> path == "GET /v1/media/$uuid" }
            }).isTrue()
            assertThat(receiver.wakeObservations.getByClientUuid(wake.wakeUuid)).isNull()
            wake.photos.forEach { assertThat(receiver.media.getByClientUuid(it.first)).isNull() }
            assertThat(receiver.preferences.current().pullCursor).isEqualTo(cursor)
            sender.proxy.truncateMediaDownloads = false
            receiver.newEngine().synchronize(receiver.preferences.current(), SyncTrigger.PullToRefresh)
            assertWake(receiver, wake)
            assertThat(receiver.preferences.current().pullCursor).isGreaterThan(cursor)
        }
    }

    @Test
    fun sleepAtPageBoundaryAndWakeOnLaterPageConvergeWithoutWirePatching() = runTest {
        RealServerMediaReceiptFaultFixture.open().use { sender ->
            // The real server's negotiated page limit is 200. Baby + 198 records + sleep
            // fill page one; the wake and its media must be received on a later page.
            repeat(198) { index ->
                sender.records.seed(record(UUID.randomUUID().toString(), sender, "formula").copy(
                    timestamp = 1_700_000_000_000L + index,
                    payloadJson = """{"amount_ml":60}""",
                ))
            }
            sender.engine.synchronize(sender.preferences.current(), SyncTrigger.LocalWrite)
            val wake = sender.publishWake(3)
            val receiver = sender.freshReceiver()
            sender.proxy.pullBodies.clear()
            receiver.engine.synchronize(receiver.session, SyncTrigger.PullToRefresh)
            val pages = sender.proxy.pullBodies.map { body ->
                Json.parseToJsonElement(body).jsonObject.getValue("entities").jsonArray.map {
                    it.jsonObject.getValue("client_uuid").jsonPrimitive.content
                }
            }
            assertThat(pages.size).isAtLeast(2)
            assertThat(pages.first()).contains(wake.sleepUuid)
            assertThat(pages.first()).doesNotContain(wake.wakeUuid)
            assertThat(pages.drop(1).flatten()).contains(wake.wakeUuid)
            assertWake(receiver, wake)
            assertThat(receiver.conflictDetails.listPullDiagnosticJournals()).isEmpty()
        }
    }

    private data class PublishedWake(
        val sleepUuid: String,
        val wakeUuid: String,
        val photos: List<Pair<String, ByteArray>>,
    )

    private suspend fun RealServerMediaReceiptFaultFixture.publishWake(count: Int): PublishedWake {
        val sleepUuid = UUID.randomUUID().toString()
        records.seed(record(sleepUuid, this, "sleep"))
        engine.synchronize(preferences.current(), SyncTrigger.LocalWrite)
        val wakeUuid = UUID.randomUUID().toString()
        val wakeId = wakeObservations.seed(WakeObservationEntity(
            clientUuid = wakeUuid,
            sleepRecordClientUuid = sleepUuid,
            wakeTimestamp = 1_700_000_060_000L,
            observerMembershipId = session.membershipId,
            updatedAt = 1_700_000_060_000L,
        ))
        val photos = (1..count).map { index ->
            val uuid = UUID.randomUUID().toString()
            val bytes = ByteArray(16 + index) { offset -> (offset * 11 + index).toByte() }
            val uri = "content://synthetic-wake/$uuid"
            mediaFiles.preparedUploadBytes[uri] = bytes
            media.seed(MediaAssetEntity(
                clientUuid = uuid,
                kind = "wake",
                wakeObservationId = wakeId,
                localUri = uri,
                createdAt = 1_700_000_060_000L,
                updatedAt = 1_700_000_060_000L,
                syncDirty = true,
            ))
            uuid to bytes
        }
        engine.synchronize(preferences.current(), SyncTrigger.LocalWrite)
        assertThat(wakeObservations.getByClientUuid(wakeUuid)?.syncDirty).isFalse()
        return PublishedWake(sleepUuid, wakeUuid, photos)
    }

    private suspend fun assertWake(receiver: RealServerMediaReceiptFaultFixture, expected: PublishedWake) {
        assertThat(receiver.records.getByClientUuid(expected.sleepUuid)).isNotNull()
        val wake = requireNotNull(receiver.wakeObservations.getByClientUuid(expected.wakeUuid))
        assertThat(wake.sleepRecordClientUuid).isEqualTo(expected.sleepUuid)
        assertThat(receiver.media.listActiveForWakeObservation(wake.id)).hasSize(expected.photos.size)
        expected.photos.forEach { (uuid, bytes) ->
            val photo = requireNotNull(receiver.media.getByClientUuid(uuid))
            assertThat(photo.wakeObservationId).isEqualTo(wake.id)
            assertThat(photo.recordId).isNull()
            assertThat(photo.sha256).isEqualTo(MessageDigest.getInstance("SHA-256")
                .digest(bytes).joinToString("") { "%02x".format(it) })
            assertThat(photo.byteSize).isEqualTo(bytes.size.toLong())
            assertThat(receiver.mediaFiles.preparedUploadBytes[photo.localUri]).isEqualTo(bytes)
        }
    }

    private fun record(uuid: String, fixture: RealServerMediaReceiptFaultFixture, type: String) = RecordEntity(
        clientUuid = uuid,
        babyId = fixture.babyId,
        type = type,
        timestamp = 1_700_000_000_000L,
        payloadJson = """{"is_nap":false,"anomaly_flag":false}""",
        schemaVersion = 2,
        updatedAt = 1_700_000_000_000L,
        syncDirty = true,
        createdByMembershipId = fixture.session.membershipId,
    )
}
