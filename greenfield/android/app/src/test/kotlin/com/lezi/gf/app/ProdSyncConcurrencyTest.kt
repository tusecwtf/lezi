package com.lezi.gf.app

import com.google.common.truth.Truth.assertThat
import com.lezi.gf.app.sync.ForegroundSyncCoordinator
import com.lezi.gf.care.CareRecord
import com.lezi.gf.care.CareService
import com.lezi.gf.care.EntityMerge
import com.lezi.gf.care.PhotoRef
import com.lezi.gf.care.RecordType
import com.lezi.gf.family.FamilyService
import com.lezi.gf.kernel.GfResult
import com.lezi.gf.syncsession.FakeWireTransport
import com.lezi.gf.syncsession.SyncSessionService
import com.lezi.gf.syncsession.WirePhoto
import com.lezi.gf.syncsession.WireRecordBundle
import com.lezi.gf.syncsession.WireReconcileRequest
import org.junit.Test
import java.io.File

/**
 * Criterion 2: multi-device LWW, atomic incomplete reject, SPKI hard-block.
 * Drives shipped [EntityMerge], [ForegroundSyncCoordinator], [FakeWireTransport], [SyncSessionService].
 */
class ProdSyncConcurrencyTest {
    @Test
    fun twoDevicesSameUuidLwwWinnerByUpdatedAt() {
        val fake = FakeWireTransport()
        // Seed server with older revision of entity
        fake.records.add(
            WireRecordBundle(
                client_uuid = "shared-r",
                baby_client_uuid = "b",
                type_key = "pee",
                timestamp_ms = 10,
                note = "device-a",
                payload_json = """{"pee_amount":1}""",
                updated_at_ms = 100,
            ),
        )
        // Device B pushes newer note for same uuid
        val syncB = SyncSessionService(http = fake)
        syncB.setAccessToken("tok")
        val push = WireReconcileRequest(
            push_records = listOf(
                WireRecordBundle(
                    client_uuid = "shared-r",
                    baby_client_uuid = "b",
                    type_key = "pee",
                    timestamp_ms = 10,
                    note = "device-b-newer",
                    payload_json = """{"pee_amount":2}""",
                    updated_at_ms = 500,
                ),
            ),
        )
        val res = syncB.reconcile(push)
        assertThat(res).isInstanceOf(GfResult.Ok::class.java)
        val winner = fake.records.first { it.client_uuid == "shared-r" }
        assertThat(winner.note).isEqualTo("device-b-newer")
        assertThat(winner.updated_at_ms).isEqualTo(500)

        // Older push must not overwrite
        val older = WireReconcileRequest(
            push_records = listOf(
                WireRecordBundle(
                    client_uuid = "shared-r",
                    baby_client_uuid = "b",
                    type_key = "pee",
                    timestamp_ms = 10,
                    note = "stale",
                    payload_json = """{"pee_amount":3}""",
                    updated_at_ms = 50,
                ),
            ),
        )
        syncB.reconcile(older)
        assertThat(fake.records.first { it.client_uuid == "shared-r" }.note)
            .isEqualTo("device-b-newer")
    }

    @Test
    fun incompleteAtomicPackageRejected() {
        val fake = FakeWireTransport()
        val sync = SyncSessionService(http = fake)
        sync.setAccessToken("tok")
        val bad = WireReconcileRequest(
            push_records = listOf(
                WireRecordBundle(
                    client_uuid = "r-photo",
                    baby_client_uuid = "b",
                    type_key = "diary",
                    timestamp_ms = 1,
                    photos = listOf(
                        WirePhoto(media_uuid = "m1", byte_size = 100, content_base64 = null),
                    ),
                ),
            ),
        )
        val res = sync.reconcile(bad)
        assertThat(res).isInstanceOf(GfResult.Err::class.java)
        assertThat(fake.records.none { it.client_uuid == "r-photo" }).isTrue()
    }

    @Test
    fun completePackageApplyMaterializesRecordAndPhotosTogether() {
        val care = CareService()
        val family = FamilyService()
        family.createOfflineBaby("b")
        family.markJoined("f", "家", "m", "owner", "爸", "d", "tok", "ref", "spki")
        val fake = FakeWireTransport()
        fake.familyConfigured = true
        val sync = SyncSessionService(http = fake)
        sync.setAccessToken("tok")
        // Server already has complete package
        fake.records.add(
            WireRecordBundle(
                client_uuid = "r-atom",
                baby_client_uuid = family.currentBaby()!!.clientUuid,
                type_key = "diary",
                timestamp_ms = 1,
                note = "with-photo",
                photos = listOf(
                    WirePhoto(media_uuid = "m1", byte_size = 4, content_base64 = "dGVzdA==", sha256 = "x"),
                ),
                updated_at_ms = 10,
            ),
        )
        // Incomplete on wire must be filtered by coordinator
        fake.records.add(
            WireRecordBundle(
                client_uuid = "r-half",
                baby_client_uuid = family.currentBaby()!!.clientUuid,
                type_key = "diary",
                timestamp_ms = 2,
                photos = listOf(
                    WirePhoto(media_uuid = "m2", byte_size = 9, content_base64 = null),
                ),
                updated_at_ms = 11,
            ),
        )
        val coord = ForegroundSyncCoordinator(care, family, sync)
        // applyRemote directly with fake response shape
        val pull = (sync.reconcile(WireReconcileRequest()) as GfResult.Ok).value
        // Manually inject incomplete into response for apply path
        val incompleteFiltered = pull.copy(
            records = pull.records + WireRecordBundle(
                client_uuid = "r-half2",
                baby_client_uuid = family.currentBaby()!!.clientUuid,
                type_key = "diary",
                timestamp_ms = 3,
                photos = listOf(WirePhoto("m3", byte_size = 5, content_base64 = null)),
            ),
        )
        coord.applyRemote(incompleteFiltered)
        assertThat(care.store().getRecord("r-atom")).isNotNull()
        assertThat(care.store().getRecord("r-atom")!!.photos).isNotEmpty()
        assertThat(care.store().getRecord("r-half2")).isNull()
        assertThat(ForegroundSyncCoordinator.isAtomicComplete(emptyList())).isTrue()
        assertThat(
            ForegroundSyncCoordinator.isAtomicComplete(
                listOf(WirePhoto("m", byte_size = 1, content_base64 = null)),
            ),
        ).isFalse()
    }

    @Test
    fun spkiChangeHardBlocks() {
        val sync = SyncSessionService()
        sync.setTrustedSpki("aaaa")
        val blocked = sync.evaluateSpkiChange("bbbb")
        assertThat(blocked).isInstanceOf(GfResult.Err::class.java)
        assertThat(sync.evaluateSpkiChange("aaaa")).isInstanceOf(GfResult.Ok::class.java)
    }

    @Test
    fun clientMergeLwwWithLocalNewerKeepsLocal() {
        val local = CareRecord(
            clientUuid = "x",
            babyClientUuid = "b",
            typeKey = RecordType.PEE.key,
            timestampMs = 1,
            note = "local-new",
            updatedAtMs = 999,
            photos = listOf(PhotoRef("m", "path", 1)),
        )
        val remote = CareRecord(
            clientUuid = "x",
            babyClientUuid = "b",
            typeKey = RecordType.PEE.key,
            timestampMs = 1,
            note = "remote-old",
            updatedAtMs = 1,
        )
        val merged = EntityMerge.mergeRecords(listOf(local), listOf(remote))
        assertThat(merged.single().note).isEqualTo("local-new")
    }

    @Test
    fun photoWireCodecSendsRealFileBytesNotPlaceholder() {
        val dir = kotlin.io.path.createTempDirectory("gf-photo-wire").toFile()
        try {
            val jpegish = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x01, 0x02, 0x03, 0x04, 0x05)
            val file = File(dir, "real.jpg")
            file.writeBytes(jpegish)
            val photo = PhotoRef(
                mediaUuid = "media-1",
                localPath = file.absolutePath,
                byteSize = jpegish.size.toLong(),
                isDraftOwned = true,
            )
            val wire = com.lezi.gf.app.media.PhotoWireCodec.toWire(photo)
            assertThat(wire.content_base64).isNotNull()
            assertThat(wire.content_base64).isNotEqualTo("dGVzdA==")
            val decoded = java.util.Base64.getDecoder().decode(wire.content_base64)
            assertThat(decoded).isEqualTo(jpegish)
            assertThat(wire.byte_size).isEqualTo(jpegish.size.toLong())
            assertThat(wire.sha256).isNotEmpty()
            // Missing file → incomplete (null content), never fake stub
            val missing = PhotoRef("m2", File(dir, "gone.jpg").absolutePath, byteSize = 10)
            val incomplete = com.lezi.gf.app.media.PhotoWireCodec.toWire(missing)
            assertThat(incomplete.content_base64).isNull()
            assertThat(
                com.lezi.gf.app.media.PhotoWireCodec.isWireComplete(
                    incomplete.byte_size,
                    incomplete.content_base64,
                ),
            ).isFalse()
        } finally {
            dir.deleteRecursively()
        }
    }
}
