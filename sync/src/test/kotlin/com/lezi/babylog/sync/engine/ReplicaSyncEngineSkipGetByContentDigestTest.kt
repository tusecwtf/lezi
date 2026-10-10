package com.lezi.babylog.sync.engine

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.core.database.RecordEntity
import com.lezi.babylog.sync.SyncTrigger
import com.lezi.babylog.sync.backend.CausalMediaItem
import com.lezi.babylog.sync.backend.PullResult
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.sync.session.receiptFor
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Public seam: [ReplicaSyncEngine.synchronize] plus recorded
 * [com.lezi.babylog.sync.RecordingSyncBackend.getMedia].
 *
 * Ticket 07: this-page 记录同步包 / 计划同步包 and the historical
 * missing-media queue share one skip/reuse owner keyed by causal
 * `sha256` + `byte_size`.
 */
class ReplicaSyncEngineSkipGetByContentDigestTest {

    @Test
    fun thisPageSkipsGetWhenUpdatedAtRisesButSha256Unchanged() = runTest {
        val file = File.createTempFile("digest-skip-same-uuid", ".jpg")
        try {
            file.writeBytes(KNOWN_BYTES_1234)
            val session = joinedReplicaSession().copy(role = FamilyRole.Owner, pullCursor = 1)
            val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
            val babyUuid = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeee30"
            val babyId = rig.babies.seed(
                localReplicaBaby().copy(
                    clientUuid = babyUuid,
                    syncDirty = false,
                    familyAuthority = true,
                    baseVersion = "v-baby",
                ),
            )
            val recordUuid = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeee31"
            val recordId = rig.records.seed(
                RecordEntity(
                    clientUuid = recordUuid,
                    babyId = babyId,
                    type = "formula",
                    timestamp = 100,
                    payloadJson = """{"amount_ml":90}""",
                    schemaVersion = 2,
                    updatedAt = 210,
                    syncDirty = false,
                    baseVersion = "v-r0",
                ),
            )
            val mediaUuid = "11111111-1111-4111-8111-111111111130"
            rig.media.seed(
                MediaAssetEntity(
                    recordId = recordId,
                    clientUuid = mediaUuid,
                    kind = "log",
                    localUri = file.absolutePath,
                    remoteUri = session.receiptFor(mediaUuid),
                    mime = "image/jpeg",
                    byteSize = 4,
                    createdAt = 210,
                    updatedAt = 210,
                    syncDirty = false,
                    sha256 = KNOWN_BYTES_1234_SHA256,
                ),
            )
            rig.backend.mediaBytes = KNOWN_BYTES_1234
            rig.backend.nextPull = PullResult(
                entities = listOf(
                    remoteReplicaRecord(recordUuid, babyClientUuid = babyUuid).copy(
                        updatedAt = 310,
                        media = listOf(causalLogMedia(mediaUuid).copy(mime = "image/png", width = 640, height = 480)),
                    ),
                    remoteReplicaMedia(mediaUuid, recordUuid).copy(
                        updatedAt = 310,
                        payloadJson = """{"kind":"log","record_client_uuid":"$recordUuid","care_plan_client_uuid":null,"baby_client_uuid":null,"mime":"image/png","width":640,"height":480,"byte_size":4}""",
                    ).withAuthenticatedMediaBytes(KNOWN_BYTES_1234),
                ),
                cursor = 2,
                generation = session.pullGeneration,
                hasMore = false,
            )

            rig.engine.synchronize(session, SyncTrigger.Foreground)

            assertThat(rig.backend.mediaGets).isEmpty()
            val stored = requireNotNull(rig.media.getByClientUuid(mediaUuid))
            assertThat(stored.localUri).isEqualTo(file.absolutePath)
            assertThat(stored.sha256).isEqualTo(KNOWN_BYTES_1234_SHA256)
            assertThat(stored.updatedAt).isEqualTo(310)
            assertThat(stored.mime).isEqualTo("image/png")
            assertThat(stored.width).isEqualTo(640)
            assertThat(stored.height).isEqualTo(480)
            assertThat(stored.recordId).isEqualTo(recordId)
            assertThat(file.readBytes()).isEqualTo(KNOWN_BYTES_1234)
        } finally {
            file.delete()
        }
    }

    @Test
    fun thisPageSkipsGetWhenSameUuidShaMatchesAndFileIsReadable() = runTest {
        val file = File.createTempFile("digest-skip-same-uuid-equal-rev", ".jpg")
        try {
            file.writeBytes(KNOWN_BYTES_1234)
            val session = joinedReplicaSession().copy(role = FamilyRole.Owner, pullCursor = 3)
            val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
            val babyUuid = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeee32"
            val babyId = rig.babies.seed(
                localReplicaBaby().copy(
                    clientUuid = babyUuid,
                    syncDirty = false,
                    familyAuthority = true,
                    baseVersion = "v-baby",
                ),
            )
            val recordUuid = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeee33"
            val recordId = rig.records.seed(
                RecordEntity(
                    clientUuid = recordUuid,
                    babyId = babyId,
                    type = "formula",
                    timestamp = 100,
                    payloadJson = """{"amount_ml":90}""",
                    schemaVersion = 2,
                    updatedAt = 210,
                    syncDirty = false,
                    baseVersion = "v-r0",
                ),
            )
            val mediaUuid = "11111111-1111-4111-8111-111111111131"
            rig.media.seed(
                MediaAssetEntity(
                    recordId = recordId,
                    clientUuid = mediaUuid,
                    kind = "log",
                    localUri = file.absolutePath,
                    remoteUri = session.receiptFor(mediaUuid),
                    mime = "image/jpeg",
                    byteSize = 4,
                    createdAt = 210,
                    updatedAt = 210,
                    syncDirty = false,
                    sha256 = KNOWN_BYTES_1234_SHA256,
                ),
            )
            rig.backend.mediaBytes = KNOWN_BYTES_1234
            rig.backend.nextPull = PullResult(
                entities = listOf(
                    remoteReplicaRecord(recordUuid, babyClientUuid = babyUuid).copy(
                        media = listOf(causalLogMedia(mediaUuid)),
                    ),
                    remoteReplicaMedia(mediaUuid, recordUuid),
                ),
                cursor = 3,
                generation = session.pullGeneration,
                hasMore = false,
            )

            rig.engine.synchronize(session, SyncTrigger.Foreground)

            assertThat(rig.backend.mediaGets).isEmpty()
            val stored = requireNotNull(rig.media.getByClientUuid(mediaUuid))
            assertThat(stored.localUri).isEqualTo(file.absolutePath)
            assertThat(stored.sha256).isEqualTo(KNOWN_BYTES_1234_SHA256)
        } finally {
            file.delete()
        }
    }

    @Test
    fun fulfillmentCloneReusesLocalUriAndSkipsGet() = runTest {
        val file = File.createTempFile("digest-reuse-fulfillment", ".jpg")
        try {
            file.writeBytes(KNOWN_BYTES_1234)
            val session = joinedReplicaSession().copy(role = FamilyRole.Owner, pullCursor = 4)
            val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
            val babyUuid = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeee34"
            val babyId = rig.babies.seed(
                localReplicaBaby().copy(
                    clientUuid = babyUuid,
                    syncDirty = false,
                    familyAuthority = true,
                    baseVersion = "v-baby",
                ),
            )
            val planUuid = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeee35"
            val planMediaUuid = "22222222-2222-4222-8222-222222222230"
            val planId = rig.carePlans.seed(
                localReplicaCarePlan(planUuid, "membership-a", 210).copy(
                    babyId = babyId,
                    syncDirty = false,
                    baseVersion = "v-plan",
                ),
            )
            rig.media.seed(
                MediaAssetEntity(
                    carePlanId = planId,
                    clientUuid = planMediaUuid,
                    kind = "log",
                    localUri = file.absolutePath,
                    remoteUri = session.receiptFor(planMediaUuid),
                    mime = "image/jpeg",
                    byteSize = 4,
                    createdAt = 210,
                    updatedAt = 210,
                    syncDirty = false,
                    sha256 = KNOWN_BYTES_1234_SHA256,
                ),
            )
            val recordUuid = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeee36"
            val recordMediaUuid = "33333333-3333-4333-8333-333333333330"
            rig.backend.mediaBytes = KNOWN_BYTES_1234
            rig.backend.nextPull = PullResult(
                entities = listOf(
                    remoteReplicaRecord(recordUuid, babyClientUuid = babyUuid).copy(
                        media = listOf(causalLogMedia(recordMediaUuid)),
                    ),
                    remoteReplicaMedia(recordMediaUuid, recordUuid).withAuthenticatedMediaBytes(KNOWN_BYTES_1234),
                ),
                cursor = 5,
                generation = session.pullGeneration,
                hasMore = false,
            )

            rig.engine.synchronize(session, SyncTrigger.Foreground)

            assertThat(rig.backend.mediaGets).isEmpty()
            val cloned = requireNotNull(rig.media.getByClientUuid(recordMediaUuid))
            val source = requireNotNull(rig.media.getByClientUuid(planMediaUuid))
            assertThat(cloned.localUri).isEqualTo(file.absolutePath)
            assertThat(source.localUri).isEqualTo(file.absolutePath)
            assertThat(cloned.sha256).isEqualTo(KNOWN_BYTES_1234_SHA256)
            assertThat(cloned.clientUuid).isNotEqualTo(source.clientUuid)
        } finally {
            file.delete()
        }
    }

    @Test
    fun deletedLocalFileWithStoredShaMustGetAndReverify() = runTest {
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner, pullCursor = 6)
        val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
        val babyUuid = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeee37"
        val babyId = rig.babies.seed(
            localReplicaBaby().copy(
                clientUuid = babyUuid,
                syncDirty = false,
                familyAuthority = true,
                baseVersion = "v-baby",
            ),
        )
        val recordUuid = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeee38"
        val recordId = rig.records.seed(
            RecordEntity(
                clientUuid = recordUuid,
                babyId = babyId,
                type = "formula",
                timestamp = 100,
                payloadJson = """{"amount_ml":90}""",
                schemaVersion = 2,
                updatedAt = 210,
                syncDirty = false,
                baseVersion = "v-r0",
            ),
        )
        val mediaUuid = "11111111-1111-4111-8111-111111111132"
        rig.media.seed(
            MediaAssetEntity(
                recordId = recordId,
                clientUuid = mediaUuid,
                kind = "log",
                localUri = "/missing/digest-deleted.jpg",
                remoteUri = session.receiptFor(mediaUuid),
                mime = "image/jpeg",
                byteSize = 4,
                createdAt = 210,
                updatedAt = 210,
                syncDirty = false,
                sha256 = KNOWN_BYTES_1234_SHA256,
            ),
        )
        rig.mediaFiles.missing += "/missing/digest-deleted.jpg"
        rig.backend.mediaBytes = KNOWN_BYTES_1234
        rig.backend.nextPull = PullResult(
            entities = listOf(
                remoteReplicaRecord(recordUuid, babyClientUuid = babyUuid).copy(
                    updatedAt = 310,
                    media = listOf(causalLogMedia(mediaUuid)),
                ),
                remoteReplicaMedia(mediaUuid, recordUuid).copy(updatedAt = 310),
            ),
            cursor = 7,
            generation = session.pullGeneration,
            hasMore = false,
        )

        rig.engine.synchronize(session, SyncTrigger.Foreground)

        assertThat(rig.backend.mediaGets).containsExactly(mediaUuid)
        val stored = requireNotNull(rig.media.getByClientUuid(mediaUuid))
        assertThat(requireNotNull(rig.mediaFiles.readableFile(stored.localUri)).readBytes())
            .isEqualTo(KNOWN_BYTES_1234)
        assertThat(stored.sha256).isEqualTo(KNOWN_BYTES_1234_SHA256)
    }

    @Test
    fun downloadedShaMismatchFailsPageAndLeavesCursorUnmoved() = runTest {
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner, pullCursor = 8)
        val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
        val babyUuid = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeee39"
        rig.babies.seed(
            localReplicaBaby().copy(
                clientUuid = babyUuid,
                syncDirty = false,
                familyAuthority = true,
                baseVersion = "v-baby",
            ),
        )
        val recordUuid = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeee40"
        val mediaUuid = "11111111-1111-4111-8111-111111111133"
        rig.backend.mediaBytes = byteArrayOf(9, 9, 9, 9)
        rig.backend.nextPull = PullResult(
            entities = listOf(
                remoteReplicaRecord(recordUuid, babyClientUuid = babyUuid).copy(
                    media = listOf(causalLogMedia(mediaUuid)),
                ),
                remoteReplicaMedia(mediaUuid, recordUuid),
            ),
            cursor = 9,
            generation = session.pullGeneration,
            hasMore = false,
        )

        val thrown = runCatching {
            rig.engine.synchronize(session, SyncTrigger.Foreground)
        }.exceptionOrNull()
        assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(thrown).hasMessageThat().contains("因果清单")

        assertThat(rig.backend.mediaGets).containsExactly(mediaUuid)
        assertThat(rig.records.getByClientUuid(recordUuid)).isNull()
        assertThat(rig.media.getByClientUuid(mediaUuid)).isNull()
        assertThat(rig.preferences.current().pullCursor).isEqualTo(8)
        assertThat(rig.preferences.current().pullGeneration).isEqualTo(session.pullGeneration)
    }

    @Test
    fun recordAndPlanPackagesStayInvisibleUntilMediaIsStaged() = runTest {
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner, pullCursor = 10)
        val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
        val babyUuid = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeee41"
        rig.babies.seed(
            localReplicaBaby().copy(
                clientUuid = babyUuid,
                syncDirty = false,
                familyAuthority = true,
                baseVersion = "v-baby",
            ),
        )
        val recordUuid = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeee42"
        val planUuid = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeee43"
        val recordMediaUuid = "11111111-1111-4111-8111-111111111134"
        val planMediaUuid = "22222222-2222-4222-8222-222222222231"
        val visibleDuringGets = mutableListOf<Pair<Boolean, Boolean>>()
        rig.backend.onGetMedia = { _ ->
            visibleDuringGets += (
                rig.records.getByClientUuid(recordUuid) != null
            ) to (
                rig.carePlans.getByClientUuid(planUuid) != null
            )
        }
        rig.backend.mediaBytesByUuid[recordMediaUuid] = KNOWN_BYTES_1234
        rig.backend.mediaBytesByUuid[planMediaUuid] = KNOWN_BYTES_8675
        rig.backend.nextPull = PullResult(
            entities = listOf(
                remoteReplicaRecord(recordUuid, babyClientUuid = babyUuid).copy(
                    media = listOf(causalLogMedia(recordMediaUuid)),
                ),
                remoteReplicaMedia(recordMediaUuid, recordUuid),
                remoteReplicaCarePlan(planUuid, babyClientUuid = babyUuid).copy(
                    media = listOf(
                        causalLogMedia(
                            planMediaUuid,
                            sha256 = KNOWN_BYTES_8675_SHA256,
                        ),
                    ),
                ),
                remoteReplicaPlanMedia(planMediaUuid, planUuid),
            ),
            cursor = 11,
            generation = session.pullGeneration,
            hasMore = false,
        )

        rig.engine.synchronize(session, SyncTrigger.Foreground)

        assertThat(rig.backend.mediaGets)
            .containsExactly(recordMediaUuid, planMediaUuid)
            .inOrder()
        assertThat(visibleDuringGets).containsExactly(false to false, false to false)
        assertThat(rig.records.getByClientUuid(recordUuid)).isNotNull()
        assertThat(rig.carePlans.getByClientUuid(planUuid)).isNotNull()
        assertThat(requireNotNull(rig.mediaFiles.readableFile(
            requireNotNull(rig.media.getByClientUuid(recordMediaUuid)).localUri,
        )).readBytes()).isEqualTo(KNOWN_BYTES_1234)
        assertThat(requireNotNull(rig.mediaFiles.readableFile(
            requireNotNull(rig.media.getByClientUuid(planMediaUuid)).localUri,
        )).readBytes()).isEqualTo(KNOWN_BYTES_8675)
    }

    @Test
    fun historicalMissingReusesDonorAndSkipsGet() = runTest {
        val file = File.createTempFile("digest-historical-reuse", ".jpg")
        try {
            file.writeBytes(KNOWN_BYTES_1234)
            val session = joinedReplicaSession().copy(role = FamilyRole.Owner, pullCursor = 12)
            val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
            val babyId = rig.babies.seed(
                localReplicaBaby().copy(
                    syncDirty = false,
                    familyAuthority = true,
                    baseVersion = "v-baby",
                ),
            )
            val donorRecordId = rig.records.seed(
                RecordEntity(
                    clientUuid = "record-historical-donor",
                    babyId = babyId,
                    type = "formula",
                    timestamp = 100,
                    payloadJson = """{"amount_ml":90}""",
                    schemaVersion = 2,
                    updatedAt = 100,
                    syncDirty = false,
                    baseVersion = "v-r0",
                ),
            )
            val missingRecordId = rig.records.seed(
                RecordEntity(
                    clientUuid = "record-historical-missing",
                    babyId = babyId,
                    type = "formula",
                    timestamp = 110,
                    payloadJson = """{"amount_ml":90}""",
                    schemaVersion = 2,
                    updatedAt = 110,
                    syncDirty = false,
                    baseVersion = "v-r1",
                ),
            )
            val donorUuid = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaa10"
            val missingUuid = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbb10"
            rig.media.seed(
                MediaAssetEntity(
                    recordId = donorRecordId,
                    clientUuid = donorUuid,
                    kind = "log",
                    localUri = file.absolutePath,
                    remoteUri = session.receiptFor(donorUuid),
                    mime = "image/jpeg",
                    byteSize = 4,
                    createdAt = 100,
                    updatedAt = 100,
                    syncDirty = false,
                    sha256 = KNOWN_BYTES_1234_SHA256,
                ),
            )
            rig.media.seed(
                MediaAssetEntity(
                    recordId = missingRecordId,
                    clientUuid = missingUuid,
                    kind = "log",
                    localUri = "",
                    remoteUri = session.receiptFor(missingUuid),
                    mime = "image/jpeg",
                    byteSize = 4,
                    createdAt = 110,
                    updatedAt = 110,
                    syncDirty = false,
                    sha256 = KNOWN_BYTES_1234_SHA256,
                ),
            )
            rig.backend.nextPull = PullResult(
                entities = emptyList(),
                cursor = 12,
                generation = session.pullGeneration,
                hasMore = false,
            )

            rig.engine.synchronize(session, SyncTrigger.Foreground)

            assertThat(rig.backend.mediaGets).isEmpty()
            val missing = requireNotNull(rig.media.getByClientUuid(missingUuid))
            assertThat(missing.localUri).isEqualTo(file.absolutePath)
            assertThat(missing.sha256).isEqualTo(KNOWN_BYTES_1234_SHA256)
            assertThat(rig.media.getByClientUuid(donorUuid)?.localUri)
                .isEqualTo(file.absolutePath)
        } finally {
            file.delete()
        }
    }

    @Test
    fun thisPageDoesNotReuseStagedFileWhenByteSizeDiffers() = runTest {
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner, pullCursor = 13)
        val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
        val babyUuid = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeee44"
        rig.babies.seed(
            localReplicaBaby().copy(
                clientUuid = babyUuid,
                syncDirty = false,
                familyAuthority = true,
                baseVersion = "v-baby",
            ),
        )
        val recordUuid = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeee45"
        val firstMediaUuid = "11111111-1111-4111-8111-111111111135"
        val secondMediaUuid = "22222222-2222-4222-8222-222222222232"
        rig.backend.mediaBytes = KNOWN_BYTES_1234
        rig.backend.nextPull = PullResult(
            entities = listOf(
                remoteReplicaRecord(recordUuid, babyClientUuid = babyUuid).copy(
                    media = listOf(
                        causalLogMedia(firstMediaUuid),
                        causalLogMedia(secondMediaUuid, byteSize = 99),
                    ),
                ),
                remoteReplicaMedia(firstMediaUuid, recordUuid),
                remoteReplicaMedia(secondMediaUuid, recordUuid).copy(
                    payloadJson = """
                        {
                          "kind":"log",
                          "record_client_uuid":"$recordUuid",
                          "care_plan_client_uuid":null,
                          "baby_client_uuid":null,
                          "mime":"image/jpeg",
                          "width":null,
                          "height":null,
                          "byte_size":99
                        }
                    """.trimIndent(),
                ),
            ),
            cursor = 14,
            generation = session.pullGeneration,
            hasMore = false,
        )

        val thrown = runCatching {
            rig.engine.synchronize(session, SyncTrigger.Foreground)
        }.exceptionOrNull()
        assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(thrown).hasMessageThat().contains("因果清单")
        assertThat(rig.backend.mediaGets)
            .containsExactly(firstMediaUuid, secondMediaUuid)
            .inOrder()
        assertThat(rig.records.getByClientUuid(recordUuid)).isNull()
        assertThat(rig.preferences.current().pullCursor).isEqualTo(13)
    }

    @Test
    fun thisPageReusesLaterDonorWhenSelfPathIsStale() = runTest {
        val file = File.createTempFile("digest-donor-not-hidden", ".jpg")
        try {
            file.writeBytes(KNOWN_BYTES_1234)
            val session = joinedReplicaSession().copy(role = FamilyRole.Owner, pullCursor = 15)
            val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
            val babyUuid = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeee46"
            val babyId = rig.babies.seed(
                localReplicaBaby().copy(
                    clientUuid = babyUuid,
                    syncDirty = false,
                    familyAuthority = true,
                    baseVersion = "v-baby",
                ),
            )
            val incomingRecordId = rig.records.seed(
                RecordEntity(
                    clientUuid = "record-stale-path",
                    babyId = babyId,
                    type = "formula",
                    timestamp = 100,
                    payloadJson = """{"amount_ml":90}""",
                    schemaVersion = 2,
                    updatedAt = 210,
                    syncDirty = false,
                    baseVersion = "v-r0",
                ),
            )
            val donorRecordId = rig.records.seed(
                RecordEntity(
                    clientUuid = "record-readable-donor",
                    babyId = babyId,
                    type = "formula",
                    timestamp = 110,
                    payloadJson = """{"amount_ml":90}""",
                    schemaVersion = 2,
                    updatedAt = 210,
                    syncDirty = false,
                    baseVersion = "v-r1",
                ),
            )
            val incomingUuid = "11111111-1111-4111-8111-111111111136"
            val donorUuid = "22222222-2222-4222-8222-222222222233"
            rig.media.seed(
                MediaAssetEntity(
                    recordId = incomingRecordId,
                    clientUuid = incomingUuid,
                    kind = "log",
                    localUri = "/missing/stale-self.jpg",
                    remoteUri = session.receiptFor(incomingUuid),
                    mime = "image/jpeg",
                    byteSize = 4,
                    createdAt = 210,
                    updatedAt = 210,
                    syncDirty = false,
                    sha256 = KNOWN_BYTES_1234_SHA256,
                ),
            )
            rig.media.seed(
                MediaAssetEntity(
                    recordId = donorRecordId,
                    clientUuid = donorUuid,
                    kind = "log",
                    localUri = file.absolutePath,
                    remoteUri = session.receiptFor(donorUuid),
                    mime = "image/jpeg",
                    byteSize = 4,
                    createdAt = 210,
                    updatedAt = 210,
                    syncDirty = false,
                    sha256 = KNOWN_BYTES_1234_SHA256,
                ),
            )
            rig.mediaFiles.missing += "/missing/stale-self.jpg"
            rig.backend.mediaBytes = KNOWN_BYTES_1234
            val recordUuid = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeee47"
            rig.records.seed(
                RecordEntity(
                    clientUuid = recordUuid,
                    babyId = babyId,
                    type = "formula",
                    timestamp = 120,
                    payloadJson = """{"amount_ml":90}""",
                    schemaVersion = 2,
                    updatedAt = 210,
                    syncDirty = false,
                    baseVersion = "v-r2",
                ),
            )
            rig.backend.nextPull = PullResult(
                entities = listOf(
                    remoteReplicaRecord(recordUuid, babyClientUuid = babyUuid).copy(
                        updatedAt = 310,
                        media = listOf(causalLogMedia(incomingUuid)),
                    ),
                    remoteReplicaMedia(incomingUuid, recordUuid).withAuthenticatedMediaBytes(KNOWN_BYTES_1234).copy(updatedAt = 310),
                ),
                cursor = 16,
                generation = session.pullGeneration,
                hasMore = false,
            )

            rig.engine.synchronize(session, SyncTrigger.Foreground)

            assertThat(rig.backend.mediaGets).isEmpty()
            val incoming = requireNotNull(rig.media.getByClientUuid(incomingUuid))
            assertThat(incoming.localUri).isEqualTo(file.absolutePath)
            assertThat(incoming.sha256).isEqualTo(KNOWN_BYTES_1234_SHA256)
        } finally {
            file.delete()
        }
    }

    @Test
    fun historicalShaMismatchUsesLocalColumnFailureMessage() = runTest {
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner, pullCursor = 17)
        val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
        val babyId = rig.babies.seed(
            localReplicaBaby().copy(
                syncDirty = false,
                familyAuthority = true,
                baseVersion = "v-baby",
            ),
        )
        val recordId = rig.records.seed(
            RecordEntity(
                clientUuid = "record-historical-mismatch",
                babyId = babyId,
                type = "formula",
                timestamp = 100,
                payloadJson = """{"amount_ml":90}""",
                schemaVersion = 2,
                updatedAt = 100,
                syncDirty = false,
                baseVersion = "v-r0",
            ),
        )
        val mediaUuid = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbb11"
        rig.media.seed(
            MediaAssetEntity(
                recordId = recordId,
                clientUuid = mediaUuid,
                kind = "log",
                localUri = "",
                remoteUri = session.receiptFor(mediaUuid),
                mime = "image/jpeg",
                byteSize = 4,
                createdAt = 100,
                updatedAt = 100,
                syncDirty = false,
                sha256 = KNOWN_BYTES_1234_SHA256,
            ),
        )
        rig.backend.mediaBytes = KNOWN_BYTES_8675
        rig.backend.nextPull = PullResult(
            entities = emptyList(),
            cursor = 17,
            generation = session.pullGeneration,
            hasMore = false,
        )

        val thrown = runCatching {
            rig.engine.synchronize(session, SyncTrigger.Foreground)
        }.exceptionOrNull()
        assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(thrown).hasMessageThat().contains("本机内容身份")
        assertThat(thrown).hasMessageThat().doesNotContain("因果清单")
        assertThat(rig.backend.mediaGets).containsExactly(mediaUuid)
        assertThat(rig.media.getByClientUuid(mediaUuid)?.localUri).isEmpty()
        assertThat(rig.preferences.current().pullCursor).isEqualTo(17)
    }

    @Test
    fun thisPageDownloadsUniqueIdentitiesBoundedTwoInFlightAndSharesDuplicateBytes() = runTest {
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner, pullCursor = 20)
        val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
        val babyUuid = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeee48"
        rig.babies.seed(
            localReplicaBaby().copy(
                clientUuid = babyUuid,
                syncDirty = false,
                familyAuthority = true,
                baseVersion = "v-baby",
            ),
        )
        val recordUuid = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeee49"
        val firstMediaUuid = "11111111-1111-4111-8111-111111111140"
        val duplicateMediaUuid = "22222222-2222-4222-8222-222222222240"
        val thirdMediaUuid = "33333333-3333-4333-8333-333333333340"
        val fourthMediaUuid = "44444444-4444-4444-8444-444444444440"
        val fourthBytes = byteArrayOf(4, 5, 6, 7)
        val fourthSha = sha256Hex(fourthBytes)
        rig.backend.mediaBytesByUuid[firstMediaUuid] = KNOWN_BYTES_1234
        rig.backend.mediaBytesByUuid[duplicateMediaUuid] = KNOWN_BYTES_1234
        rig.backend.mediaBytesByUuid[thirdMediaUuid] = KNOWN_BYTES_8675
        rig.backend.mediaBytesByUuid[fourthMediaUuid] = fourthBytes
        val inFlight = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val arrived = AtomicInteger(0)
        val twoArrived = CompletableDeferred<Unit>()
        rig.backend.onGetMedia = { _ ->
            val now = inFlight.incrementAndGet()
            peak.accumulateAndGet(now, ::maxOf)
            if (arrived.incrementAndGet() >= 2) twoArrived.complete(Unit)
            // Deterministic overlap: the first two GETs only proceed together,
            // so a serial loop (or a parallelism above 2 with three units)
            // would show in peak.
            withTimeout(5_000) { twoArrived.await() }
            inFlight.decrementAndGet()
        }
        rig.backend.nextPull = PullResult(
            entities = listOf(
                remoteReplicaRecord(recordUuid, babyClientUuid = babyUuid).copy(
                    media = listOf(
                        causalLogMedia(firstMediaUuid),
                        causalLogMedia(duplicateMediaUuid),
                        causalLogMedia(thirdMediaUuid, sha256 = KNOWN_BYTES_8675_SHA256),
                        causalLogMedia(fourthMediaUuid, sha256 = fourthSha),
                    ),
                ),
                remoteReplicaMedia(firstMediaUuid, recordUuid),
                remoteReplicaMedia(duplicateMediaUuid, recordUuid),
                remoteReplicaMedia(thirdMediaUuid, recordUuid),
                remoteReplicaMedia(fourthMediaUuid, recordUuid),
            ),
            cursor = 21,
            generation = session.pullGeneration,
            hasMore = false,
        )

        rig.engine.synchronize(session, SyncTrigger.Foreground)

        assertThat(peak.get()).isEqualTo(2)
        // One GET per unique identity, in page order; the duplicate never GETs.
        assertThat(rig.backend.mediaGets)
            .containsExactly(firstMediaUuid, thirdMediaUuid, fourthMediaUuid)
            .inOrder()
        assertThat(rig.records.getByClientUuid(recordUuid)).isNotNull()
        assertThat(requireNotNull(rig.mediaFiles.readableFile(
            requireNotNull(rig.media.getByClientUuid(firstMediaUuid)).localUri,
        )).readBytes()).isEqualTo(KNOWN_BYTES_1234)
        // Same-identity sibling shares the unit's staged file, matching the
        // former serial loop's staged dedupe.
        assertThat(rig.media.getByClientUuid(duplicateMediaUuid)?.localUri)
            .isEqualTo(rig.media.getByClientUuid(firstMediaUuid)?.localUri)
        assertThat(requireNotNull(rig.mediaFiles.readableFile(
            requireNotNull(rig.media.getByClientUuid(thirdMediaUuid)).localUri,
        )).readBytes()).isEqualTo(KNOWN_BYTES_8675)
        assertThat(requireNotNull(rig.mediaFiles.readableFile(
            requireNotNull(rig.media.getByClientUuid(fourthMediaUuid)).localUri,
        )).readBytes()).isEqualTo(fourthBytes)
    }

    private companion object {
        val KNOWN_BYTES_1234 = byteArrayOf(1, 2, 3, 4)
        val KNOWN_BYTES_8675 = byteArrayOf(8, 6, 7, 5)
        const val KNOWN_BYTES_1234_SHA256 =
            "9f64a747e1b97f131fabb6b447296c9b6f0201e79fb3c5356e6c77e89b6a806a"
        const val KNOWN_BYTES_8675_SHA256 =
            "148ad5eadb29c70c19bf3de855a16faff7bf73d9739819d3b553060f12b0ccf1"

        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }

        fun causalLogMedia(
            mediaUuid: String,
            sha256: String = KNOWN_BYTES_1234_SHA256,
            byteSize: Long = 4,
        ) = CausalMediaItem(
            mediaUuid = mediaUuid,
            role = "log",
            sha256 = sha256,
            byteSize = byteSize,
            mime = "image/jpeg",
        )
    }
}
