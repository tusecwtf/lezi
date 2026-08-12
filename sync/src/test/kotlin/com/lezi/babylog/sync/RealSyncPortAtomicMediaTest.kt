package com.lezi.babylog.sync

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.core.model.RootPublicationState
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Test
import com.lezi.babylog.sync.backend.PullResult
import com.lezi.babylog.sync.backend.SyncEntity
import com.lezi.babylog.sync.backend.SyncHttpException
import com.lezi.babylog.sync.session.FamilyRole

// Split from RealSyncPortTest kitchen sink by contract cluster (ticket 05).
class RealSyncPortAtomicMediaTest {
    @Test
    fun pullDeletedBabyWithAvatarTombstoneDoesNotRevivePointer() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        val avatarUuid = "66666666-6666-6666-6666-666666666666"
        val babyId = rig.babies.seed(
            localBaby().copy(
                clientUuid = "baby-local",
                avatarMediaUuid = avatarUuid,
                avatarPath = "baby_avatars/live.jpg",
                updatedAt = 100,
                syncDirty = false,
            ),
        )
        rig.media.seed(
            MediaAssetEntity(
                clientUuid = avatarUuid,
                kind = "avatar",
                babyId = babyId,
                localUri = "baby_avatars/live.jpg",
                remoteUri = rig.preferences.current().expectedMediaReceipt(avatarUuid),
                mime = "image/jpeg",
                byteSize = 12,
                createdAt = 100,
                updatedAt = 100,
                syncDirty = false,
            ),
        )
        val remoteDeletedAt = 400L
        rig.backend.nextPull = PullResult(
            entities = listOf(
                remoteBaby().copy(
                    clientUuid = "baby-local",
                    payloadJson = """
                        {
                          "nickname":"服务器宝宝",
                          "sex":null,
                          "birthday":"2024-01-01",
                          "birth_weight_grams":null,
                          "avatar_media_uuid":null
                        }
                    """.trimIndent(),
                    updatedAt = remoteDeletedAt,
                    deletedAt = remoteDeletedAt,
                ),
                SyncEntity(
                    type = "media",
                    clientUuid = avatarUuid,
                    payloadJson = """
                        {
                          "kind":"avatar",
                          "record_client_uuid":null,
                          "care_plan_client_uuid":null,
                          "baby_client_uuid":"baby-local",
                          "mime":"image/jpeg",
                          "width":null,
                          "height":null,
                          "byte_size":0
                        }
                    """.trimIndent(),
                    updatedAt = remoteDeletedAt,
                    deletedAt = remoteDeletedAt,
                ),
            ),
            cursor = 1,
            generation = "current-generation",
            hasMore = false,
        )

        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isSuccess).isTrue()

        val baby = requireNotNull(rig.babies.getIncludingDeleted(babyId))
        assertThat(baby.deletedAt).isEqualTo(remoteDeletedAt)
        assertThat(baby.avatarMediaUuid).isNull()
        val avatar = requireNotNull(rig.media.getByClientUuid(avatarUuid))
        assertThat(avatar.deletedAt).isEqualTo(remoteDeletedAt)
        assertThat(avatar.syncDirty).isFalse()
    }

    @Test
    fun avatarMaterializationNeverOverwritesAProfileChangedAfterSnapshot() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        val babyId = rig.babies.seed(
            localBaby().copy(avatarPath = "baby_avatars/local.jpg"),
        )
        rig.mediaFiles.afterInspect = {
            val current = requireNotNull(rig.babies.getIncludingDeleted(babyId))
            rig.babies.update(
                current.copy(
                    nickname = "并发改名",
                    updatedAt = current.updatedAt + 1,
                    syncDirty = true,
                ),
            )
        }

        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()

        val baby = requireNotNull(rig.babies.getIncludingDeleted(babyId))
        assertThat(baby.nickname).isEqualTo("并发改名")
        assertThat(baby.updatedAt).isEqualTo(101)
        // Materialize skipped the avatar after the concurrent profile edit; wire
        // still published the re-read concurrent baby root (no avatar pointer).
        assertThat(baby.avatarMediaUuid).isNull()
        // Synthetic root ack uses the content epoch at push time (101), so the
        // concurrent rename is confirmed clean rather than left spuriously dirty.
        assertThat(baby.syncDirty).isFalse()
    }

    @Test
    fun recordMediaSnapshotUsesMediaAssetRowsOnly() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        val recordId = rig.records.seed(
            localRecord(babyId).copy(
                payloadJson = """{"amount_ml":120}""",
            ),
        )
        val mediaUuid = "32323232-3232-3232-3232-323232323232"
        rig.media.seed(
            MediaAssetEntity(
                recordId = recordId,
                clientUuid = mediaUuid,
                kind = "log",
                localUri = "photos/user-new.jpg",
                remoteUri = rig.preferences.current().expectedMediaReceipt(mediaUuid),
                mime = "image/jpeg",
                byteSize = 12,
                createdAt = 100,
                updatedAt = 100,
                syncDirty = false,
            ),
        )
        rig.backend.remember("baby", "baby-local")
        rig.backend.remember("record", "record-local")
        rig.backend.remember("media", mediaUuid)
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()

        val record = requireNotNull(rig.records.getIncludingDeleted(recordId))
        assertThat(record.payloadJson).isEqualTo("""{"amount_ml":120}""")
        assertThat(record.note).isNull()
        assertThat(record.updatedAt).isEqualTo(120)
        assertThat(record.syncDirty).isFalse()
        assertThat(rig.media.getByClientUuid(mediaUuid)?.deletedAt).isNull()
        assertThat(rig.media.listAllIncludingDeleted().map(MediaAssetEntity::localUri))
            .containsExactly("photos/user-new.jpg")
    }

    @Test
    fun downloadedPhotoRefreshPreservesAConcurrentRecordEdit() = runTest {
        val session = joinedSession("family-a")
        val rig = SyncRig(session = session)
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        val recordId = rig.records.seed(
            localRecord(babyId).copy(
                payloadJson = """{"amount_ml":120}""",
                syncDirty = false,
            ),
        )
        val mediaUuid = "33333333-3333-3333-3333-333333333333"
        rig.media.seed(
            MediaAssetEntity(
                recordId = recordId,
                clientUuid = mediaUuid,
                kind = "log",
                localUri = "",
                remoteUri = rig.preferences.current().expectedMediaReceipt(mediaUuid),
                mime = "image/jpeg",
                byteSize = 12,
                createdAt = 100,
                updatedAt = 100,
                syncDirty = false,
            ),
        )
        rig.backend.remember("baby", "baby-local")
        rig.backend.remember("record", "record-local")
        rig.backend.remember("media", mediaUuid)
        rig.backend.beforeGetMediaReturn = {
            val current = requireNotNull(rig.records.getIncludingDeleted(recordId))
            rig.records.update(
                current.copy(
                    note = "并发补充说明",
                    updatedAt = current.updatedAt + 1,
                    syncDirty = true,
                ),
            )
        }
        rig.backend.nextPull = PullResult(
            emptyList(),
            cursor = 1,
            generation = "current-generation",
            hasMore = false,
        )

        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isSuccess).isTrue()

        val record = requireNotNull(rig.records.getIncludingDeleted(recordId))
        assertThat(record.note).isEqualTo("并发补充说明")
        assertThat(record.updatedAt).isEqualTo(121)
        assertThat(record.syncDirty).isFalse()
        assertThat(rig.backend.causalCommittedUnits.flatten().map { it.clientUuid })
            .contains("record-local")
        assertThat(record.payloadJson).isEqualTo("""{"amount_ml":120}""")
        assertThat(rig.media.getByClientUuid(mediaUuid)?.localUri)
            .isEqualTo("downloaded/$mediaUuid")
    }

    @Test
    fun pullWindowPhotoEditStaysAuthoritativeWhenDownloadStartsLater() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        val recordId = rig.records.seed(
            localRecord(babyId).copy(syncDirty = false),
        )
        val mediaUuid = "34343434-3434-3434-3434-343434343434"
        rig.media.seed(
            MediaAssetEntity(
                recordId = recordId,
                clientUuid = mediaUuid,
                kind = "log",
                localUri = "",
                remoteUri = rig.preferences.current().expectedMediaReceipt(mediaUuid),
                mime = "image/jpeg",
                byteSize = 12,
                createdAt = 100,
                updatedAt = 100,
                syncDirty = false,
            ),
        )
        rig.backend.beforePullReturn = {
            val current = requireNotNull(rig.records.getIncludingDeleted(recordId))
            rig.records.update(
                current.copy(
                    note = "拉取期间编辑",
                    updatedAt = current.updatedAt + 1,
                    syncDirty = true,
                ),
            )
        }
        rig.backend.nextPull = PullResult(
            emptyList(),
            cursor = 1,
            generation = "current-generation",
            hasMore = false,
        )

        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isSuccess).isTrue()

        val record = requireNotNull(rig.records.getIncludingDeleted(recordId))
        assertThat(record.payloadJson).isEqualTo("""{"amount_ml":120}""")
        assertThat(record.note).isEqualTo("拉取期间编辑")
        assertThat(rig.media.getByClientUuid(mediaUuid)?.localUri)
            .isEqualTo("downloaded/$mediaUuid")
    }

    @Test
    fun photoEditBetweenTwoDownloadsPreventsTheSecondDerivedRefresh() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        val recordId = rig.records.seed(
            localRecord(babyId).copy(syncDirty = false),
        )
        val mediaUuids = listOf(
            "35353535-3535-3535-3535-353535353535",
            "36363636-3636-3636-3636-363636363636",
        )
        mediaUuids.forEach { mediaUuid ->
            rig.media.seed(
                MediaAssetEntity(
                    recordId = recordId,
                    clientUuid = mediaUuid,
                    kind = "log",
                    localUri = "",
                    remoteUri = rig.preferences.current().expectedMediaReceipt(mediaUuid),
                    mime = "image/jpeg",
                    byteSize = 12,
                    createdAt = 100,
                    updatedAt = 100,
                    syncDirty = false,
                ),
            )
        }
        rig.mediaFiles.afterSaveDownloaded = {
            val current = requireNotNull(rig.media.getByClientUuid(mediaUuids.last()))
            rig.media.update(
                current.copy(
                    localUri = "photos/between-downloads.jpg",
                    updatedAt = current.updatedAt + 1,
                    syncDirty = true,
                ),
            )
        }
        rig.backend.nextPull = PullResult(
            emptyList(),
            cursor = 1,
            generation = "current-generation",
            hasMore = false,
        )

        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isSuccess).isTrue()

        assertThat(rig.records.getIncludingDeleted(recordId)?.payloadJson)
            .isEqualTo("""{"amount_ml":120}""")
        assertThat(rig.media.getByClientUuid(mediaUuids.first())?.localUri)
            .isEqualTo("downloaded/${mediaUuids.first()}")
        assertThat(rig.media.getByClientUuid(mediaUuids.last())?.localUri)
            .isEqualTo("photos/between-downloads.jpg")
    }

    @Test
    fun downloadedAvatarRefreshPreservesAProfileEditDuringFileSave() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        val mediaUuid = "44444444-4444-4444-4444-444444444444"
        val babyId = rig.babies.seed(
            localBaby().copy(
                avatarMediaUuid = mediaUuid,
                avatarPath = null,
                syncDirty = false,
            ),
        )
        rig.media.seed(
            MediaAssetEntity(
                clientUuid = mediaUuid,
                kind = "avatar",
                babyId = babyId,
                localUri = "",
                remoteUri = rig.preferences.current().expectedMediaReceipt(mediaUuid),
                mime = "image/jpeg",
                byteSize = 12,
                createdAt = 100,
                updatedAt = 100,
                syncDirty = false,
            ),
        )
        rig.backend.remember("baby", "baby-local")
        rig.backend.remember("media", mediaUuid)
        rig.mediaFiles.afterSaveDownloaded = {
            val current = requireNotNull(rig.babies.getIncludingDeleted(babyId))
            rig.babies.update(
                current.copy(
                    avatarPath = "baby_avatars/user-new.jpg",
                    updatedAt = current.updatedAt + 1,
                    syncDirty = true,
                ),
            )
        }
        rig.backend.nextPull = PullResult(
            emptyList(),
            cursor = 1,
            generation = "current-generation",
            hasMore = false,
        )

        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isSuccess).isTrue()

        val baby = requireNotNull(rig.babies.getIncludingDeleted(babyId))
        assertThat(baby.avatarPath).isEqualTo("baby_avatars/user-new.jpg")
        assertThat(baby.updatedAt).isEqualTo(101)
        assertThat(baby.syncDirty).isFalse()
        assertThat(rig.backend.causalCommittedUnits.flatten().map { it.clientUuid })
            .contains("baby-local")
        assertThat(rig.media.getByClientUuid(mediaUuid)?.localUri).isEmpty()
        assertThat(rig.mediaFiles.deleted).contains("downloaded/$mediaUuid")
    }

    @Test
    fun noteOnlyEditAcceptsDownloadedPhotoAndNeverTombstonesItNextSync() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        val recordId = rig.records.seed(
            localRecord(babyId).copy(syncDirty = false),
        )
        val mediaUuid = "45454545-4545-4545-4545-454545454545"
        rig.media.seed(
            MediaAssetEntity(
                recordId = recordId,
                clientUuid = mediaUuid,
                kind = "log",
                localUri = "",
                remoteUri = rig.preferences.current().expectedMediaReceipt(mediaUuid),
                mime = "image/jpeg",
                byteSize = 12,
                createdAt = 100,
                updatedAt = 100,
                syncDirty = false,
            ),
        )
        rig.backend.remember("baby", "baby-local")
        rig.backend.remember("record", "record-local")
        rig.backend.remember("media", mediaUuid)
        rig.backend.beforeGetMediaReturn = {
            val current = requireNotNull(rig.records.getIncludingDeleted(recordId))
            rig.records.update(
                current.copy(
                    note = "只改备注",
                    updatedAt = current.updatedAt + 1,
                    syncDirty = true,
                ),
            )
        }
        rig.backend.nextPull = PullResult(
            emptyList(),
            cursor = 1,
            generation = "current-generation",
            hasMore = false,
        )

        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isSuccess).isTrue()
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()

        val record = requireNotNull(rig.records.getIncludingDeleted(recordId))
        assertThat(record.note).isEqualTo("只改备注")
        assertThat(record.payloadJson).isEqualTo("""{"amount_ml":120}""")
        val localMedia = requireNotNull(rig.media.getByClientUuid(mediaUuid))
        assertThat(localMedia.deletedAt).isNull()
        assertThat(localMedia.localUri).isEqualTo("downloaded/$mediaUuid")
        // A live republished photo remains in the root's causal media manifest.
        val committedMedia = rig.backend.causalCommittedUnits.flatten()
            .flatMap { it.media }
            .filter { it.mediaUuid == mediaUuid }
        assertThat(committedMedia).isNotEmpty()
    }

    @Test
    fun nicknameOnlyEditAcceptsDownloadedAvatarAndNeverTombstonesItNextSync() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        val mediaUuid = "46464646-4646-4646-4646-464646464646"
        val babyId = rig.babies.seed(
            localBaby().copy(
                avatarMediaUuid = mediaUuid,
                avatarPath = null,
                syncDirty = false,
            ),
        )
        rig.media.seed(
            MediaAssetEntity(
                clientUuid = mediaUuid,
                kind = "avatar",
                babyId = babyId,
                localUri = "",
                remoteUri = rig.preferences.current().expectedMediaReceipt(mediaUuid),
                mime = "image/jpeg",
                byteSize = 12,
                createdAt = 100,
                updatedAt = 100,
                syncDirty = false,
            ),
        )
        rig.backend.remember("baby", "baby-local")
        rig.backend.remember("media", mediaUuid)
        rig.mediaFiles.afterSaveDownloaded = {
            val current = requireNotNull(rig.babies.getIncludingDeleted(babyId))
            rig.babies.update(
                current.copy(
                    nickname = "只改昵称",
                    updatedAt = current.updatedAt + 1,
                    syncDirty = true,
                ),
            )
        }
        rig.backend.nextPull = PullResult(
            emptyList(),
            cursor = 1,
            generation = "current-generation",
            hasMore = false,
        )

        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isSuccess).isTrue()
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()

        val baby = requireNotNull(rig.babies.getIncludingDeleted(babyId))
        assertThat(baby.nickname).isEqualTo("只改昵称")
        assertThat(baby.avatarPath).isEqualTo("downloaded/$mediaUuid")
        assertThat(rig.media.getByClientUuid(mediaUuid)?.deletedAt).isNull()
        val babyMutation = rig.backend.causalCommittedUnits.flatten()
            .last { it.entityType == "baby" }
        val committedMedia = rig.backend.causalCommittedUnits.flatten()
            .flatMap { it.media }
            .filter { it.mediaUuid == mediaUuid }
        assertThat(babyMutation.rootJson).contains(mediaUuid)
        assertThat(committedMedia).isNotEmpty()
    }

    @Test
    fun babyAvatarPointerWinsOverANewerUnreferencedAvatarRow() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        val selectedUuid = "55555555-5555-5555-5555-555555555555"
        val newerUuid = "66666666-6666-6666-6666-666666666666"
        val babyId = rig.babies.seed(
            localBaby().copy(
                clientUuid = "baby-remote",
                avatarMediaUuid = newerUuid,
                avatarPath = "avatars/newer.jpg",
                syncDirty = false,
            ),
        )
        rig.media.seed(
            MediaAssetEntity(
                clientUuid = selectedUuid,
                kind = "avatar",
                babyId = babyId,
                localUri = "avatars/selected.jpg",
                remoteUri = rig.preferences.current().expectedMediaReceipt(selectedUuid),
                createdAt = 100,
                updatedAt = 100,
                syncDirty = false,
            ),
        )
        rig.media.seed(
            MediaAssetEntity(
                clientUuid = newerUuid,
                kind = "avatar",
                babyId = babyId,
                localUri = "avatars/newer.jpg",
                remoteUri = rig.preferences.current().expectedMediaReceipt(newerUuid),
                createdAt = 200,
                updatedAt = 200,
                syncDirty = false,
            ),
        )
        rig.backend.nextPull = PullResult(
            entities = listOf(
                remoteBaby().copy(
                    payloadJson = """
                        {
                          "nickname":"远端宝宝",
                          "sex":null,
                          "birthday":"2024-01-01",
                          "birth_weight_grams":null,
                          "avatar_media_uuid":"$selectedUuid"
                        }
                    """.trimIndent(),
                    updatedAt = 300,
                ),
            ),
            cursor = 1,
            generation = "current-generation",
            hasMore = false,
        )

        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isSuccess).isTrue()

        val baby = rig.babies.getByClientUuid("baby-remote")
        assertThat(baby?.avatarMediaUuid).isEqualTo(selectedUuid)
        assertThat(baby?.avatarPath).isEqualTo("avatars/selected.jpg")
    }

    @Test
    fun memberNeverPushesLocalAvatarBytesAndSettlesRejectedMetadata() = runTest {
        val rig = SyncRig(
            session = joinedSession("family-a").copy(role = FamilyRole.Member),
        )
        val babyId = rig.babies.seed(
            localBaby().copy(
                avatarPath = "baby_avatars/member-local.jpg",
                familyAuthority = true,
            ),
        )
        val avatarUuid = "11111111-1111-1111-1111-111111111111"
        rig.media.seed(
            MediaAssetEntity(
                clientUuid = avatarUuid,
                kind = "avatar",
                babyId = babyId,
                localUri = "baby_avatars/member-local.jpg",
                mime = "image/jpeg",
                byteSize = 12,
                createdAt = 100,
                updatedAt = 100,
            ),
        )
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()

        assertThat(rig.backend.causalCommittedUnits).isEmpty()
        assertThat(rig.backend.causalMediaPreimageBytes).isEmpty()
        assertThat(rig.babies.getByClientUuid("baby-local")?.syncDirty).isFalse()
        assertThat(rig.media.getByClientUuid(avatarUuid)?.syncDirty).isFalse()
    }

    @Test
    fun ownerPublishesLocalAvatarBytesThroughCausalCommit() = runTest {
        val rig = SyncRig(session = joinedSession("family-a").copy(role = FamilyRole.Owner))
        val avatarUuid = "12121212-1212-4212-8212-121212121212"
        val babyId = rig.babies.seed(
            localBaby().copy(
                avatarMediaUuid = avatarUuid,
                avatarPath = "baby_avatars/owner-local.jpg",
                familyAuthority = true,
            ),
        )
        rig.media.seed(
            MediaAssetEntity(
                clientUuid = avatarUuid,
                kind = "avatar",
                babyId = babyId,
                localUri = "baby_avatars/owner-local.jpg",
                mime = "image/jpeg",
                byteSize = 1,
                createdAt = 100,
                updatedAt = 100,
            ),
        )

        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()

        assertThat(rig.backend.causalMediaPreimageBytes.map { it.first })
            .containsExactly(avatarUuid)
        assertThat(rig.backend.causalCommittedUnits.flatten().single().media.map { it.mediaUuid })
            .containsExactly(avatarUuid)
    }

    @Test
    fun memberRejoiningSameFamilyPullsCanonicalAvatarWithoutRepublishingBaby() = runTest {
        val session = joinedSession("family-a").copy(role = FamilyRole.Member)
        val rig = SyncRig(session = session)
        val avatarUuid = "11111111-1111-1111-1111-111111111111"
        val babyId = rig.babies.seed(
            localBaby().copy(
                avatarMediaUuid = avatarUuid,
                avatarPath = "baby_avatars/remote.jpg",
                syncDirty = false,
            ),
        )
        rig.media.seed(
            MediaAssetEntity(
                clientUuid = avatarUuid,
                kind = "avatar",
                babyId = babyId,
                localUri = "baby_avatars/remote.jpg",
                remoteUri = rig.preferences.current().expectedMediaReceipt(avatarUuid),
                createdAt = 100,
                updatedAt = 100,
                syncDirty = false,
            ),
        )
        rig.backend.remember("media", avatarUuid)

        assertThat(rig.port.leave().isSuccess).isTrue()
        rig.preferences.saveSession(session.copy(accessToken = "replacement-token"))
        rig.backend.nextPull = PullResult(
            entities = listOf(
                remoteBaby().copy(
                    clientUuid = "baby-local",
                    payloadJson = """
                        {
                          "nickname":"服务器宝宝",
                          "sex":null,
                          "birthday":"2024-01-01",
                          "birth_weight_grams":null,
                          "avatar_media_uuid":"$avatarUuid"
                        }
                    """.trimIndent(),
                    updatedAt = 200,
                ),
            ),
            cursor = 1,
            generation = "current-generation",
            hasMore = false,
        )

        val result = rig.port.sync(SyncTrigger.PullToRefresh)
        assertThat(result.exceptionOrNull()).isNull()

        assertThat(rig.backend.causalCommittedUnits).isEmpty()
        assertThat(rig.backend.causalMediaPreimageBytes).isEmpty()
        assertThat(rig.babies.getByClientUuid("baby-local")?.avatarMediaUuid).isEqualTo(avatarUuid)
        assertThat(rig.babies.getByClientUuid("baby-local")?.avatarPath)
            .isEqualTo("baby_avatars/remote.jpg")
        assertThat(rig.babies.getByClientUuid("baby-local")?.familyAuthority).isTrue()
        assertThat(rig.media.getByClientUuid(avatarUuid)?.remoteUri)
            .isEqualTo(rig.preferences.current().expectedMediaReceipt(avatarUuid))
    }

    @Test
    fun localRecordPublishLabelUsesRootReceiptAndTruthfulZeroPhotoCopy() {
        assertThat(
            localRecordPublishLabel(
                syncDirty = true,
                familyJoined = true,
                lastSyncFailed = false,
                publicationState = RootPublicationState.NEVER_PUBLISHED,
            ),
        ).isEqualTo("仅本机 · 等待家庭同步")
        assertThat(
            localRecordPublishLabel(
                syncDirty = true,
                familyJoined = true,
                lastSyncFailed = true,
                publicationState = RootPublicationState.NEVER_PUBLISHED,
            ),
        ).isEqualTo("仅本机 · 同步失败")
        assertThat(
            localRecordPublishLabel(
                syncDirty = true,
                familyJoined = true,
                lastSyncFailed = false,
                publicationState = RootPublicationState.PREVIOUS_VERSION_PUBLISHED,
            ),
        ).isEqualTo("仅本机 · 等待更新同步")
        assertThat(
            localRecordPublishLabel(
                syncDirty = true,
                familyJoined = true,
                lastSyncFailed = true,
                publicationState = RootPublicationState.PREVIOUS_VERSION_PUBLISHED,
            ),
        ).isEqualTo("仅本机 · 更新同步失败")
        assertThat(
            localRecordPublishDetail(
                lastSyncFailed = true,
                publicationState = RootPublicationState.PREVIOUS_VERSION_PUBLISHED,
            ),
        ).contains("上一完整版本")
        assertThat(
            localRecordPublishDetail(
                lastSyncFailed = false,
                publicationState = RootPublicationState.NEVER_PUBLISHED,
            ),
        ).isEqualTo("其他成员暂不可见，记录发布成功后才会出现。")
        assertThat(
            localRecordPublishLabel(
                syncDirty = false,
                familyJoined = true,
                lastSyncFailed = false,
                publicationState = RootPublicationState.NEVER_PUBLISHED,
            ),
        ).isNull()
        assertThat(
            localRecordPublishLabel(
                syncDirty = true,
                familyJoined = false,
                lastSyncFailed = false,
                publicationState = RootPublicationState.NEVER_PUBLISHED,
            ),
        ).isNull()
        assertThat(
            localRecordPublishLabel(
                syncDirty = true,
                familyJoined = true,
                lastSyncFailed = false,
                publicationState = RootPublicationState.CURRENT_VERSION_PUBLISHED,
            ),
        ).isNull()
    }

    @Test
    fun atomicMutationIncompletePackageKeepsPriorVersionAndCursor() = runTest {
        val rig = SyncRig(
            session = joinedSession("family-a").copy(
                pullCursor = 50,
                pullGeneration = "g0",
            ),
        )
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        rig.preferences.saveSession(
            rig.preferences.current().copy(pullCursor = 50, pullGeneration = "g0"),
        )
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        // Prior complete version already on device.
        val recordId = rig.records.seed(
            localRecord(babyId).copy(
                clientUuid = "record-prior",
                updatedAt = 100,
                payloadJson = """{"amount_ml":80}""",
                note = "旧完整",
                syncDirty = false,
            ),
        )
        rig.media.seed(
            MediaAssetEntity(
                recordId = recordId,
                clientUuid = testMediaUuid("media-old"),
                kind = "log",
                localUri = "old.jpg",
                mime = "image/jpeg",
                byteSize = 4,
                createdAt = 100,
                updatedAt = 100,
                syncDirty = false,
                remoteUri = rig.preferences.current()
                    .expectedMediaReceipt(testMediaUuid("media-old")),
            ),
        )
        // Incomplete mutation package: record meta + missing media download.
        rig.backend.nextPull = PullResult(
            entities = listOf(
                SyncEntity(
                    type = "record",
                    clientUuid = "record-prior",
                    payloadJson =
                        """{"baby_client_uuid":"baby-local","created_by_membership_id":"member-b","type":"formula","custom_item_client_uuid":null,"timestamp":1000,"end_timestamp":null,"note":"新版本","payload_json":{"amount_ml":90},"schema_version":2}""",
                    updatedAt = 200,
                ),
                SyncEntity(
                    type = "media",
                    clientUuid = testMediaUuid("media-new"),
                    payloadJson =
                        """{"kind":"log","record_client_uuid":"record-prior","care_plan_client_uuid":null,"baby_client_uuid":null,"mime":"image/jpeg","width":null,"height":null,"byte_size":4}""",
                    updatedAt = 200,
                ),
            ),
            cursor = 60,
            generation = "g1",
            hasMore = false,
        )
        rig.backend.getMediaFailure = IllegalStateException("download aborted")
        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isSuccess).isFalse()
        // Prior complete version retained.
        val kept = rig.records.getByClientUuid("record-prior")!!
        assertThat(kept.note).isEqualTo("旧完整")
        assertThat(kept.updatedAt).isEqualTo(100)
        assertThat(rig.media.listActiveForRecord(recordId).map { it.clientUuid })
            .containsExactly(testMediaUuid("media-old"))
        assertThat(rig.preferences.current().pullCursor).isEqualTo(50)
    }

    @Test
    fun atomicDownloadFailureKeepsNewRecordInvisibleAndCursorUnmoved() = runTest {
        val rig = SyncRig(
            session = joinedSession("family-a").copy(
                pullCursor = 10,
                pullGeneration = "g0",
            ),
        )
        // Warm capability probe (empty pull) then restore the durable cursor/generation.
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        rig.preferences.saveSession(
            rig.preferences.current().copy(pullCursor = 10, pullGeneration = "g0"),
        )
        val mediaUuid = testMediaUuid("media-dl-fail")
        val recordUuid = "record-dl-fail"
        rig.backend.nextPull = PullResult(
            entities = listOf(
                SyncEntity(
                    type = "record",
                    clientUuid = recordUuid,
                    payloadJson =
                        """{"baby_client_uuid":"baby-local","created_by_membership_id":"member-b","type":"formula","custom_item_client_uuid":null,"timestamp":1000,"end_timestamp":null,"note":null,"payload_json":{"amount_ml":80},"schema_version":2}""",
                    updatedAt = 200,
                ),
                SyncEntity(
                    type = "media",
                    clientUuid = mediaUuid,
                    payloadJson =
                        """{"kind":"log","record_client_uuid":"$recordUuid","care_plan_client_uuid":null,"baby_client_uuid":null,"mime":"image/jpeg","width":null,"height":null,"byte_size":4}""",
                    updatedAt = 200,
                ),
            ),
            cursor = 20,
            generation = "current-generation",
            hasMore = false,
        )
        // Baby must exist for record apply dependency chain when download succeeds;
        // failure happens before apply, so seed baby for a realistic package.
        rig.babies.seed(localBaby().copy(syncDirty = false))
        rig.backend.getMediaFailure = IllegalStateException("download aborted")

        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isSuccess).isFalse()
        assertThat(rig.records.getByClientUuid(recordUuid)).isNull()
        assertThat(rig.media.getByClientUuid(mediaUuid)).isNull()
        assertThat(rig.preferences.current().pullCursor).isEqualTo(10)
    }

    @Test
    fun atomicApplyStageFailureDoesNotExposePartialRecord() = runTest {
        val rig = SyncRig(
            session = joinedSession("family-a").copy(
                pullCursor = 5,
                pullGeneration = "g0",
            ),
        )
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        rig.preferences.saveSession(
            rig.preferences.current().copy(pullCursor = 5, pullGeneration = "g0"),
        )
        val mediaUuid = testMediaUuid("media-apply-fail")
        val recordUuid = "record-apply-fail"
        // No baby on device → record apply fails after media bytes are staged.
        rig.backend.nextPull = PullResult(
            entities = listOf(
                SyncEntity(
                    type = "record",
                    clientUuid = recordUuid,
                    payloadJson =
                        """{"baby_client_uuid":"missing-baby","created_by_membership_id":"member-b","type":"formula","custom_item_client_uuid":null,"timestamp":1000,"end_timestamp":null,"note":null,"payload_json":{"amount_ml":80},"schema_version":2}""",
                    updatedAt = 200,
                ),
                SyncEntity(
                    type = "media",
                    clientUuid = mediaUuid,
                    payloadJson =
                        """{"kind":"log","record_client_uuid":"$recordUuid","care_plan_client_uuid":null,"baby_client_uuid":null,"mime":"image/jpeg","width":null,"height":null,"byte_size":4}""",
                    updatedAt = 200,
                ),
            ),
            cursor = 15,
            generation = "current-generation",
            hasMore = false,
        )

        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isSuccess).isFalse()
        assertThat(rig.records.getByClientUuid(recordUuid)).isNull()
        assertThat(rig.media.getByClientUuid(mediaUuid)).isNull()
        assertThat(rig.preferences.current().pullCursor).isEqualTo(5)
    }

    @Test
    fun syntheticRootReceiptIsMonotonicAcrossMultipleStandaloneMediaGroups() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        rig.records.seed(
            localRecord(babyId).copy(
                clientUuid = "record-mono-receipt",
                updatedAt = 1_000,
                familyPublishedUpdatedAt = 1_000,
                syncDirty = false,
            ),
        )

        // Newer receipt first, then a stale older synthetic ack must not regress.
        assertThat(
            rig.records.acknowledgeSyntheticRootPublication(
                clientUuid = "record-mono-receipt",
                expectedLocalUpdatedAt = 1_000,
                publishedUpdatedAt = 1_002,
            ),
        ).isTrue()
        assertThat(rig.records.getByClientUuid("record-mono-receipt")?.familyPublishedUpdatedAt)
            .isEqualTo(1_002)
        assertThat(rig.records.getByClientUuid("record-mono-receipt")?.updatedAt)
            .isEqualTo(1_002)

        // Concurrent-path older receipt (expected epoch already left behind).
        assertThat(
            rig.records.acknowledgeSyntheticRootPublication(
                clientUuid = "record-mono-receipt",
                expectedLocalUpdatedAt = 1_000,
                publishedUpdatedAt = 1_001,
            ),
        ).isFalse()
        assertThat(rig.records.getByClientUuid("record-mono-receipt")?.familyPublishedUpdatedAt)
            .isEqualTo(1_002)
        assertThat(rig.records.getByClientUuid("record-mono-receipt")?.updatedAt)
            .isEqualTo(1_002)
    }

    @Test
    fun clientUuidAloneIsNotAcceptedAsMediaReceipt() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        val recordId = rig.records.seed(localRecord(babyId).copy(syncDirty = false))
        val mediaUuid = "55555555-5555-5555-5555-555555555555"
        rig.media.seed(
            MediaAssetEntity(
                recordId = recordId,
                clientUuid = mediaUuid,
                kind = "log",
                localUri = "",
                remoteUri = mediaUuid,
                mime = "image/jpeg",
                byteSize = 12,
                createdAt = 100,
                updatedAt = 100,
                syncDirty = false,
            ),
        )
        rig.backend.getMediaFailure = SyncHttpException(401, "must not download")
        rig.backend.nextPull = PullResult(
            emptyList(),
            cursor = 42,
            generation = "current-generation",
            hasMore = false,
        )

        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isSuccess).isTrue()

        assertThat(rig.preferences.current().pullCursor).isEqualTo(42)
        assertThat(rig.preferences.current().pullGeneration)
            .isEqualTo("current-generation")
        assertThat(rig.media.getByClientUuid(mediaUuid)?.localUri).isEmpty()
        assertThat(rig.media.listMissingLocalBytes().map(MediaAssetEntity::clientUuid))
            .containsExactly(mediaUuid)
    }

    @Test
    fun nonUuidLocalMediaFailsBeforeBundleNetworkIo() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        val recordId = rig.records.seed(localRecord(babyId).copy(syncDirty = true))
        rig.media.seed(
            MediaAssetEntity(
                recordId = recordId,
                clientUuid = "not-a-uuid",
                kind = "log",
                localUri = "photos/not-portable.jpg",
                mime = "image/jpeg",
                byteSize = 12,
                createdAt = 100,
                updatedAt = 100,
                syncDirty = true,
            ),
        )

        val result = rig.port.sync(SyncTrigger.LocalWrite)

        assertThat(result.isFailure).isTrue()
        assertThat(rig.backend.stagedBundles).isEmpty()
        assertThat(rig.backend.causalCommittedUnits).isEmpty()
        assertThat(rig.media.getByClientUuid("not-a-uuid")).isNotNull()
    }

    @Test
    fun mediaGetAuthFailureFailsSyncWithoutAdvancingCursorOrMarkingSuccess() = runTest {
        listOf(401, 403).forEach { statusCode ->
            val rig = SyncRig(session = joinedSession("family-a"))
            val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
            val recordId = rig.records.seed(localRecord(babyId).copy(syncDirty = false))
            val mediaUuid = testMediaUuid("auth-media-$statusCode")
            rig.media.seed(
                MediaAssetEntity(
                    recordId = recordId,
                    clientUuid = mediaUuid,
                    kind = "log",
                    localUri = "",
                    remoteUri = rig.preferences.current().expectedMediaReceipt(mediaUuid),
                    mime = "image/jpeg",
                    byteSize = 12,
                    createdAt = 100,
                    updatedAt = 100,
                    syncDirty = false,
                ),
            )
            rig.backend.getMediaFailure = SyncHttpException(statusCode, "auth failed")
            rig.backend.nextPull = PullResult(
                emptyList(),
                cursor = 42,
                generation = "current-generation",
                hasMore = false,
            )

            val result = rig.port.sync(SyncTrigger.PullToRefresh)

            assertThat(result.isFailure).isTrue()
            assertThat(result.exceptionOrNull()).isInstanceOf(SyncHttpException::class.java)
            assertThat((result.exceptionOrNull() as SyncHttpException).statusCode)
                .isEqualTo(statusCode)
            assertThat(rig.preferences.current().pullCursor).isEqualTo(0)
            assertThat(rig.preferences.current().pullGeneration)
                .isEqualTo("current-generation")
            assertThat(rig.preferences.current().lastSuccessAt).isNull()
        }
    }

    @Test
    fun invalidMediaBytesDoNotBlockPullCursorAdvance() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        val recordId = rig.records.seed(localRecord(babyId).copy(syncDirty = false))
        val mediaUuid = "56565656-5656-5656-5656-565656565656"
        rig.media.seed(
            MediaAssetEntity(
                recordId = recordId,
                clientUuid = mediaUuid,
                kind = "log",
                localUri = "",
                remoteUri = rig.preferences.current().expectedMediaReceipt(mediaUuid),
                mime = "image/jpeg",
                byteSize = 12,
                createdAt = 100,
                updatedAt = 100,
                syncDirty = false,
            ),
        )
        rig.backend.mediaBytes = byteArrayOf()
        rig.backend.nextPull = PullResult(
            emptyList(),
            cursor = 43,
            generation = "current-generation",
            hasMore = false,
        )

        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isSuccess).isTrue()

        assertThat(rig.preferences.current().pullCursor).isEqualTo(43)
        assertThat(rig.preferences.current().pullGeneration)
            .isEqualTo("current-generation")
        assertThat(rig.media.getByClientUuid(mediaUuid)?.localUri).isEmpty()
        assertThat(rig.media.listMissingLocalBytes().map(MediaAssetEntity::clientUuid))
            .containsExactly(mediaUuid)
    }

}
