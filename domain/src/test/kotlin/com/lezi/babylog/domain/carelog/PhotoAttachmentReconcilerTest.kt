package com.lezi.babylog.domain.carelog
import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.core.database.MediaLocalPathGate
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Test


class PhotoAttachmentReconcilerTest {
    @Test
    fun newImportPersistsSixtyFourLowercaseHexWithoutRehashingLater() = runTest {
        val media = FakeMediaAssetDao()
        val file = File.createTempFile("import-sha", ".jpg")
        try {
            file.writeBytes(byteArrayOf(1, 2, 3, 4))
            val reconciler = PhotoAttachmentReconciler(media, MediaLocalPathGate()) {
                "media-imported"
            }

            reconciler.withInvolvedPaths(
                owner = PhotoAttachmentOwner.Record(7L),
                additionalPaths = listOf(file.absolutePath),
            ) {
                reconciler.reconcile(
                    owner = PhotoAttachmentOwner.Record(7L),
                    photoLocalPaths = listOf(file.absolutePath),
                    at = 100L,
                    contentDigests = reconciler.digestReadablePaths(listOf(file.absolutePath)),
                )
            }

            val created = media.listActiveForRecord(7L).single()
            assertThat(created.sha256).isEqualTo(KNOWN_BYTES_1234_SHA256)
            assertThat(created.updatedAt).isEqualTo(100L)
            assertThat(created.syncDirty).isTrue()

            reconciler.withInvolvedPaths(
                owner = PhotoAttachmentOwner.Record(7L),
                additionalPaths = listOf(file.absolutePath),
            ) {
                reconciler.reconcile(
                    owner = PhotoAttachmentOwner.Record(7L),
                    photoLocalPaths = listOf(file.absolutePath),
                    at = 200L,
                    contentDigests = reconciler.digestReadablePaths(listOf(file.absolutePath)),
                )
            }
            val again = media.listActiveForRecord(7L).single()
            assertThat(again.sha256).isEqualTo(KNOWN_BYTES_1234_SHA256)
            assertThat(again.updatedAt).isEqualTo(100L)
        } finally {
            file.delete()
        }
    }

    @Test
    fun concurrentImportsOnSharedReconcilerEachKeepTheirOwnDigest() = runTest {
        val media = FakeMediaAssetDao()
        var uuidCount = 0
        val reconciler = PhotoAttachmentReconciler(media, MediaLocalPathGate()) {
            uuidCount += 1
            "media-$uuidCount"
        }
        val fileA = File.createTempFile("import-sha-a", ".jpg")
        val fileB = File.createTempFile("import-sha-b", ".jpg")
        try {
            fileA.writeBytes(byteArrayOf(1, 2, 3, 4))
            fileB.writeBytes(byteArrayOf(8, 6, 7, 5))
            val startedA = CompletableDeferred<Unit>()
            val releaseA = CompletableDeferred<Unit>()
            val importA = async {
                reconciler.withInvolvedPaths(
                    owner = PhotoAttachmentOwner.Record(7L),
                    additionalPaths = listOf(fileA.absolutePath),
                ) {
                    val digests = reconciler.digestReadablePaths(listOf(fileA.absolutePath))
                    startedA.complete(Unit)
                    releaseA.await()
                    reconciler.reconcile(
                        owner = PhotoAttachmentOwner.Record(7L),
                        photoLocalPaths = listOf(fileA.absolutePath),
                        at = 100L,
                        contentDigests = digests,
                    )
                }
            }
            startedA.await()
            reconciler.withInvolvedPaths(
                owner = PhotoAttachmentOwner.Record(8L),
                additionalPaths = listOf(fileB.absolutePath),
            ) {
                reconciler.reconcile(
                    owner = PhotoAttachmentOwner.Record(8L),
                    photoLocalPaths = listOf(fileB.absolutePath),
                    at = 100L,
                    contentDigests = reconciler.digestReadablePaths(listOf(fileB.absolutePath)),
                )
            }
            releaseA.complete(Unit)
            importA.await()

            assertThat(media.listActiveForRecord(7L).single().sha256)
                .isEqualTo(KNOWN_BYTES_1234_SHA256)
            assertThat(media.listActiveForRecord(8L).single().sha256)
                .isEqualTo(KNOWN_BYTES_8675_SHA256)
        } finally {
            fileA.delete()
            fileB.delete()
        }
    }

    @Test
    fun recordReconcileNormalizesPathsAndCreatesOnlyRecordOwnedRows() = runTest {
        val media = FakeMediaAssetDao()
        val generatedUuids = ArrayDeque(listOf("media-a", "media-b"))
        val reconciler = PhotoAttachmentReconciler(media, MediaLocalPathGate()) {
            generatedUuids.removeFirst()
        }

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
        val reconciler = PhotoAttachmentReconciler(media, MediaLocalPathGate()) {
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
    fun recordReviveRefreshesSha256FromImportedBytes() = runTest {
        val media = FakeMediaAssetDao()
        val file = File.createTempFile("revive-sha", ".jpg")
        try {
            file.writeBytes(byteArrayOf(8, 6, 7, 5))
            media.seed(
                mediaAsset(
                    id = 2L,
                    clientUuid = "revived-uuid",
                    recordId = 7L,
                    localUri = file.absolutePath,
                    at = 80L,
                ).copy(
                    deletedAt = 90L,
                    updatedAt = 130L,
                    syncDirty = false,
                    sha256 = KNOWN_BYTES_1234_SHA256,
                ),
            )
            val reconciler = PhotoAttachmentReconciler(media, MediaLocalPathGate()) {
                error("revival must keep the existing media identity")
            }

            reconciler.withInvolvedPaths(
                owner = PhotoAttachmentOwner.Record(7L),
                additionalPaths = listOf(file.absolutePath),
            ) {
                reconciler.reconcile(
                    owner = PhotoAttachmentOwner.Record(7L),
                    photoLocalPaths = listOf(file.absolutePath),
                    at = 200L,
                    contentDigests = reconciler.digestReadablePaths(listOf(file.absolutePath)),
                )
            }

            val revived = media.listActiveForRecord(7L).single()
            assertThat(revived.clientUuid).isEqualTo("revived-uuid")
            assertThat(revived.sha256).isEqualTo(KNOWN_BYTES_8675_SHA256)
            assertThat(revived.deletedAt).isNull()
            assertThat(revived.syncDirty).isTrue()
        } finally {
            file.delete()
        }
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
        val reconciler = PhotoAttachmentReconciler(media, MediaLocalPathGate()) {
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
        val reconciler = PhotoAttachmentReconciler(media, MediaLocalPathGate()) {
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

    @Test
    fun withInvolvedPathsLocksExistingAndAdditionalPathsAndIgnoresBlanks() = runTest {
        val media = FakeMediaAssetDao()
        val pathGate = MediaLocalPathGate()
        media.seed(
            mediaAsset(
                id = 1L,
                clientUuid = "existing",
                recordId = 7L,
                localUri = "photos/existing.jpg",
                at = 10L,
            ),
        )
        val reconciler = PhotoAttachmentReconciler(media, pathGate)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()

        val holder = async {
            reconciler.withInvolvedPaths(
                owner = PhotoAttachmentOwner.Record(7L),
                additionalPaths = listOf(" photos/new.jpg ", "", "  "),
            ) {
                entered.complete(Unit)
                release.await()
                "ok"
            }
        }
        entered.await()

        val contender = async {
            // Contends on existing owner path held by withInvolvedPaths.
            pathGate.withLock("photos/existing.jpg") {
                secondStarted.complete(Unit)
            }
        }
        delay(50)
        assertThat(secondStarted.isCompleted).isFalse()

        val additionalContender = async {
            pathGate.withLock("photos/new.jpg") {
                "unlocked-after"
            }
        }
        delay(50)
        // additional path is also locked; blank paths must not be required.
        assertThat(additionalContender.isCompleted).isFalse()

        release.complete(Unit)
        assertThat(holder.await()).isEqualTo("ok")
        contender.await()
        assertThat(additionalContender.await()).isEqualTo("unlocked-after")
        assertThat(secondStarted.isCompleted).isTrue()
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

private const val KNOWN_BYTES_1234_SHA256 =
    "9f64a747e1b97f131fabb6b447296c9b6f0201e79fb3c5356e6c77e89b6a806a"
private const val KNOWN_BYTES_8675_SHA256 =
    "148ad5eadb29c70c19bf3de855a16faff7bf73d9739819d3b553060f12b0ccf1"
