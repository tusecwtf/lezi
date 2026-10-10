package com.lezi.babylog.sync.clear

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.LocalDataClearScope
import com.lezi.babylog.core.database.causal.PrivateSpoolPathPolicy
import com.lezi.babylog.sync.MemoryConflictSummaryDao
import com.lezi.babylog.sync.TestImmutableMediaSpool
import com.lezi.babylog.sync.TestMediaFileStore
import com.lezi.babylog.sync.media.ScopedMediaSpoolClear
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Test

class LocalReplicaClearPrivateSpoolTest {
    @Test
    fun recordsOnlyDoesNotUnlinkHistoricalSpoolPathOrItsSurvivingAvatarAlias() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("clear-private-spool").toFile()
        try {
            val bytes = byteArrayOf(1, 2, 3)
            val owned = File(directory, "causal-media-spool/group/photo").apply {
                requireNotNull(parentFile).mkdirs(); writeBytes(bytes)
            }
            val deleted = mutableListOf<String>()
            val files = object : TestMediaFileStore() {
                override suspend fun delete(localUri: String) {
                    deleted += localUri
                    if (localUri == owned.path) owned.delete()
                }
            }
            val rig = ClearRig(mediaFiles = files)
            // Persisted historical aliases predate the new no-publication guard.
            rig.media.seed(media("log", "log", owned.path))
            val avatar = media("avatar", "avatar", "causal-media-spool/group/photo")
            rig.media.seed(avatar)
            val before = rig.media.getByClientUuid("avatar")
            val guarded = PrivateSpoolPathPolicy(directory).guard(rig.media, rig.transactions)
            val coordinator = LocalReplicaClearCoordinator(
                rig.barrier, rig.preferences, rig.babies, guarded, files,
                ScopedMediaSpoolClear(rig.babies, rig.cache, MemoryConflictSummaryDao(), TestImmutableMediaSpool(files)),
                rig.transactions, rig.pending,
            )
            coordinator.clear(LocalDataClearScope.RecordsOnly) {}.getOrThrow()
            assertThat(deleted).doesNotContain(owned.path)
            assertThat(owned.readBytes()).isEqualTo(bytes)
            assertThat(rig.media.getByClientUuid("avatar")).isEqualTo(before)
            assertThat(rig.media.getByClientUuid("log")).isNull()
        } finally { directory.deleteRecursively() }
    }
}
