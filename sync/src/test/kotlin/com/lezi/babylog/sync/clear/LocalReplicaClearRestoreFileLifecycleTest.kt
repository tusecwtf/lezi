package com.lezi.babylog.sync.clear

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.LocalDataClearScope
import com.lezi.babylog.core.database.MediaLocalPathGate
import com.lezi.babylog.core.database.causal.MediaReferenceEntity
import com.lezi.babylog.core.database.causal.MediaReferenceHolderKind
import com.lezi.babylog.sync.MemoryMediaReferenceDao
import com.lezi.babylog.sync.TestMediaFileStore
import com.lezi.babylog.sync.disasterrecovery.CapturedRestoreRows
import com.lezi.babylog.sync.disasterrecovery.RestoreFileLifecycleOwner
import com.lezi.babylog.sync.disasterrecovery.RestoreFileSnapshotSource
import com.lezi.babylog.sync.disasterrecovery.RestoreFileSnapshotStore
import com.lezi.babylog.sync.media.ReferenceAwareMediaFileCleanup
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Test

class LocalReplicaClearRestoreFileLifecycleTest {
    @Test
    fun recordsOnlyKeepsAdoptedAvatarAndItsOwnerUntilAllLocalClearRemovesItsHolders() = runTest {
        val directory = Files.createTempDirectory("clear-retained-restore-avatar").toFile()
        try {
            val bytes = byteArrayOf(11, 22, 33)
            val original = File(directory, "avatar.jpg").apply { writeBytes(bytes) }
            val files = object : TestMediaFileStore() {
                override suspend fun delete(localUri: String) {
                    super.delete(localUri)
                    File(localUri).takeIf { it.isFile }?.let { check(it.delete()) }
                }
            }
            val references = MemoryMediaReferenceDao()
            val snapshots = RestoreFileSnapshotStore(File(directory, "restore-snapshots"))
            lateinit var owned: File
            lateinit var lifecycle: RestoreFileLifecycleOwner
            lateinit var rig: ClearRig
            rig = ClearRig(
                mediaFiles = files,
                restoreOwnedPaths = {
                    assertThat(rig.transactions.depth).isEqualTo(0)
                    setOf(owned.path)
                },
                reclaimRestoreFiles = {
                    assertThat(rig.transactions.depth).isEqualTo(0)
                    assertThat(rig.pending.pending).isNotNull()
                    // Generic file deletion must leave this owner's bytes intact.
                    assertThat(owned.isFile).isTrue()
                    if (rig.pending.pending!!.scope == LocalDataClearScope.AllLocalData) {
                        assertThat(rig.media.getByClientUuid(AVATAR)).isNull()
                        assertThat(references.listForMedia(AVATAR)).isEmpty()
                    }
                    lifecycle.reclaimRetired(force = true)
                },
            )
            val cleanup = ReferenceAwareMediaFileCleanup(
                rig.media, references, files, rig.transactions, MediaLocalPathGate(),
            )
            lifecycle = RestoreFileLifecycleOwner(
                rig.cache, snapshots, rig.transactions, rig.media, files, cleanup, rig.babies,
                currentFamilyId = { rig.preferences.current().familyId },
            )
            val babyId = rig.babies.seed(baby(original.path).copy(avatarMediaUuid = AVATAR))
            rig.media.seed(media(AVATAR, "avatar", original.path).copy(
                babyId = babyId, byteSize = bytes.size.toLong(), mime = "image/jpeg",
            ))
            val captured = requireNotNull(rig.media.getByClientUuid(AVATAR))
            val evidence = CapturedRestoreRows(
                emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), listOf(captured),
            ).exactEvidence("media", AVATAR)
            val pointer = snapshots.capture(
                REQUEST,
                listOf(RestoreFileSnapshotSource(AVATAR, original, "image/jpeg", null, null)),
                manifestEstimateBytes = 4096,
            ) {
                """{"source_family":"family-a","evidence_version":2,"versions":[{"type":"media","uuid":"$AVATAR","evidence":"$evidence"}]}"""
            }
            val snapshot = snapshots.read(pointer)
            owned = snapshot.ownedPath(snapshot.media.single())
            assertThat(original.delete()).isTrue()
            lifecycle.retireUndispatched(REQUEST)
            lifecycle.reclaimRetired(force = true)
            val avatarBefore = requireNotNull(rig.media.getByClientUuid(AVATAR))
            val babyBefore = requireNotNull(rig.babies.get(babyId))
            val ownerBefore = requireNotNull(rig.cache.getTransportJournal(RestoreFileLifecycleOwner.ownerKey(REQUEST)))
            assertThat(avatarBefore.localUri).isEqualTo(owned.path)
            assertThat(babyBefore.avatarPath).isEqualTo(owned.path)
            val holder = MediaReferenceEntity(
                mediaUuid = AVATAR,
                holderKind = MediaReferenceHolderKind.STABLE_ROOT,
                holderId = babyBefore.clientUuid,
                localUri = owned.path,
                createdAt = 1,
            )
            references.upsert(holder)

            rig.coordinator.clear(LocalDataClearScope.RecordsOnly) {
                rig.cache.deleteAllTransportJournals()
            }.getOrThrow()

            assertThat(rig.media.getByClientUuid(AVATAR)).isEqualTo(avatarBefore)
            assertThat(rig.babies.get(babyId)).isEqualTo(babyBefore)
            assertThat(references.listForMedia(AVATAR)).containsExactly(holder)
            assertThat(rig.cache.getTransportJournal(ownerBefore.journalKey)).isEqualTo(ownerBefore)
            assertThat(owned.readBytes()).isEqualTo(bytes)
            assertThat(rig.pending.pending).isNull()

            rig.newCoordinator().clear(LocalDataClearScope.AllLocalData) {
                rig.cache.deleteAllTransportJournals()
                rig.babies.deleteAll()
                references.deleteAll()
            }.getOrThrow()

            assertThat(rig.media.getByClientUuid(AVATAR)).isNull()
            assertThat(owned.exists()).isFalse()
            assertThat(rig.cache.listRestoreFileOwners()).isEmpty()
            assertThat(files.deleted).doesNotContain(owned.path)
            assertThat(rig.pending.pending).isNull()
        } finally {
            directory.deleteRecursively()
        }
    }

    private companion object {
        const val REQUEST = "11111111-1111-4111-8111-111111111111"
        const val AVATAR = "22222222-2222-4222-8222-222222222222"
    }
}
