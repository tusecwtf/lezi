package com.lezi.babylog.domain

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.MediaAssetEntity
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PhotoAttachmentReconcilerTest {
    @Test
    fun recordReconcileNormalizesPathsAndCreatesOnlyRecordOwnedRows() = runTest {
        val media = FakeMediaAssetDao()
        val generatedUuids = ArrayDeque(listOf("media-a", "media-b"))
        val reconciler = PhotoAttachmentReconciler(media) { generatedUuids.removeFirst() }

        reconciler.reconcile(
            owner = PhotoAttachmentOwner.Record(7L),
            photoLocalPaths = listOf(" photos/a.jpg ", "", "photos/a.jpg", " photos/b.jpg"),
            at = 100L,
        )

        assertThat(media.listActiveForRecord(7L))
            .containsExactly(
                mediaAsset(
                    id = 1L,
                    clientUuid = "media-a",
                    recordId = 7L,
                    localUri = "photos/a.jpg",
                    at = 100L,
                ),
                mediaAsset(
                    id = 2L,
                    clientUuid = "media-b",
                    recordId = 7L,
                    localUri = "photos/b.jpg",
                    at = 100L,
                ),
            ).inOrder()
        assertThat(media.listActiveForCarePlan(7L)).isEmpty()
        assertThat(generatedUuids).isEmpty()
    }

    @Test
    fun recordReplaceRevivesByNormalizedPathAndTombstonesRemovedRows() = runTest {
        val media = FakeMediaAssetDao()
        media.seed(
            mediaAsset(
                id = 1L,
                clientUuid = "removed-uuid",
                recordId = 7L,
                localUri = "photos/removed.jpg",
                at = 120L,
            ).copy(syncDirty = false),
        )
        media.seed(
            mediaAsset(
                id = 2L,
                clientUuid = "revived-uuid",
                recordId = 7L,
                localUri = "photos/revived.jpg",
                at = 80L,
            ).copy(deletedAt = 90L, updatedAt = 130L, syncDirty = false),
        )
        var generatedUuidCount = 0
        val reconciler = PhotoAttachmentReconciler(media) {
            generatedUuidCount += 1
            "new-uuid-$generatedUuidCount"
        }

        val mutation = reconciler.reconcile(
            owner = PhotoAttachmentOwner.Record(7L),
            photoLocalPaths = listOf(" photos/revived.jpg ", "photos/new.jpg"),
            at = 100L,
        )

        assertThat(mutation.changed).isTrue()
        assertThat(mutation.tombstonedClientUuids).containsExactly("removed-uuid")

        val byPath = media.listForRecord(7L).associateBy(MediaAssetEntity::localUri)
        assertThat(byPath.getValue("photos/removed.jpg"))
            .isEqualTo(
                mediaAsset(
                    id = 1L,
                    clientUuid = "removed-uuid",
                    recordId = 7L,
                    localUri = "photos/removed.jpg",
                    at = 120L,
                ).copy(deletedAt = 100L, updatedAt = 121L, syncDirty = true),
            )
        assertThat(byPath.getValue("photos/revived.jpg"))
            .isEqualTo(
                mediaAsset(
                    id = 2L,
                    clientUuid = "revived-uuid",
                    recordId = 7L,
                    localUri = "photos/revived.jpg",
                    at = 80L,
                ).copy(deletedAt = null, updatedAt = 131L, syncDirty = true),
            )
        assertThat(byPath.getValue("photos/new.jpg"))
            .isEqualTo(
                mediaAsset(
                    id = 3L,
                    clientUuid = "new-uuid-1",
                    recordId = 7L,
                    localUri = "photos/new.jpg",
                    at = 100L,
                ),
            )
        assertThat(generatedUuidCount).isEqualTo(1)
        assertThat(byPath.values.all { it.recordId == 7L && it.carePlanId == null }).isTrue()
    }

    @Test
    fun carePlanTombstoneDoesNotTouchRecordRowSharingTheSamePath() = runTest {
        val media = FakeMediaAssetDao()
        val sharedPath = "photos/shared.jpg"
        media.seed(
            mediaAsset(
                id = 1L,
                clientUuid = "record-shared",
                recordId = 7L,
                localUri = sharedPath,
                at = 60L,
            ).copy(syncDirty = false),
        )
        media.seed(
            mediaAsset(
                id = 2L,
                clientUuid = "plan-shared",
                carePlanId = 9L,
                localUri = sharedPath,
                at = 60L,
            ).copy(syncDirty = false),
        )
        media.seed(
            mediaAsset(
                id = 3L,
                clientUuid = "plan-only",
                carePlanId = 9L,
                localUri = "photos/plan-only.jpg",
                at = 20L,
            ).copy(syncDirty = false),
        )
        val reconciler = PhotoAttachmentReconciler(media) {
            error("tombstone must not generate a UUID")
        }

        val planMutation = reconciler.tombstone(
            owner = PhotoAttachmentOwner.CarePlan(9L),
            deletedAt = 50L,
        )

        assertThat(planMutation.changed).isTrue()
        assertThat(planMutation.tombstonedClientUuids)
            .containsExactly("plan-shared", "plan-only")

        assertThat(media.listForRecord(7L))
            .containsExactly(
                mediaAsset(
                    id = 1L,
                    clientUuid = "record-shared",
                    recordId = 7L,
                    localUri = sharedPath,
                    at = 60L,
                ).copy(syncDirty = false),
            )
        assertThat(media.listForCarePlan(9L))
            .containsExactly(
                mediaAsset(
                    id = 2L,
                    clientUuid = "plan-shared",
                    carePlanId = 9L,
                    localUri = sharedPath,
                    at = 60L,
                ).copy(deletedAt = 50L, updatedAt = 61L, syncDirty = true),
                mediaAsset(
                    id = 3L,
                    clientUuid = "plan-only",
                    carePlanId = 9L,
                    localUri = "photos/plan-only.jpg",
                    at = 20L,
                ).copy(deletedAt = 50L, updatedAt = 50L, syncDirty = true),
            ).inOrder()
        val recordMutation = reconciler.tombstone(
            owner = PhotoAttachmentOwner.Record(7L),
            deletedAt = 70L,
        )
        assertThat(recordMutation.tombstonedClientUuids).containsExactly("record-shared")
        assertThat(media.listForRecord(7L).single())
            .isEqualTo(
                mediaAsset(
                    id = 1L,
                    clientUuid = "record-shared",
                    recordId = 7L,
                    localUri = sharedPath,
                    at = 60L,
                ).copy(deletedAt = 70L, updatedAt = 70L, syncDirty = true),
            )
        assertThat(
            media.listAllIncludingDeleted().all {
                (it.recordId != null) xor (it.carePlanId != null)
            },
        ).isTrue()
    }

    @Test
    fun recordAndCarePlanApplyTheSameNormalizedLimitAndExplicitClear() = runTest {
        val media = FakeMediaAssetDao()
        var uuidCount = 0
        val reconciler = PhotoAttachmentReconciler(media) {
            uuidCount += 1
            "media-$uuidCount"
        }
        val owners = listOf(
            PhotoAttachmentOwner.Record(7L),
            PhotoAttachmentOwner.CarePlan(9L),
        )

        owners.forEach { owner ->
            reconciler.reconcile(
                owner = owner,
                photoLocalPaths = listOf(" a.jpg ", "b.jpg", "a.jpg", " c.jpg ", ""),
                at = 20L,
            )
        }

        assertThat(media.listActiveForRecord(7L).map(MediaAssetEntity::localUri))
            .containsExactly("a.jpg", "b.jpg", "c.jpg").inOrder()
        assertThat(media.listActiveForCarePlan(9L).map(MediaAssetEntity::localUri))
            .containsExactly("a.jpg", "b.jpg", "c.jpg").inOrder()
        assertThat(uuidCount).isEqualTo(6)

        owners.forEach { owner ->
            val error = runCatching {
                reconciler.reconcile(
                    owner = owner,
                    photoLocalPaths = listOf("a.jpg", "b.jpg", "c.jpg", "d.jpg"),
                    at = 30L,
                )
            }.exceptionOrNull()
            assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        }
        assertThat(uuidCount).isEqualTo(6)

        owners.forEach { owner ->
            reconciler.reconcile(owner = owner, photoLocalPaths = emptyList(), at = 40L)
        }
        assertThat(media.listActiveForRecord(7L)).isEmpty()
        assertThat(media.listActiveForCarePlan(9L)).isEmpty()
        assertThat(media.listAllIncludingDeleted().all { it.deletedAt == 40L && it.syncDirty })
            .isTrue()
    }

    @Test
    fun ownerTypesRejectNonPositiveIds() {
        assertThat(runCatching { PhotoAttachmentOwner.Record(0L) }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(runCatching { PhotoAttachmentOwner.CarePlan(-1L) }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}

private fun mediaAsset(
    id: Long,
    clientUuid: String,
    localUri: String,
    at: Long,
    recordId: Long? = null,
    carePlanId: Long? = null,
): MediaAssetEntity = MediaAssetEntity(
    id = id,
    recordId = recordId,
    carePlanId = carePlanId,
    clientUuid = clientUuid,
    kind = "log",
    babyId = null,
    localUri = localUri,
    createdAt = at,
    updatedAt = at,
    syncDirty = true,
)
