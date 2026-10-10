package com.lezi.babylog.sync

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.LocalDataClearScope
import com.lezi.babylog.sync.media.CausalMediaRole
import com.lezi.babylog.sync.media.FileImmutableMediaSpool
import com.lezi.babylog.sync.media.ImmutableMediaSpoolSource
import com.lezi.babylog.sync.session.SyncSession
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Test

class RealSyncPortOfflineSpoolClearTest {
    @Test
    fun recordsOnlyClearKeepsRetainedAvatarSidecarsAndRetiresUnownedCareCopies() = runTest {
        val directory = Files.createTempDirectory("scoped-spool-clear").toFile()
        try {
            val files = TestMediaFileStore()
            val spool = FileImmutableMediaSpool(files, directory, 1024 * 1024, 1024)
            val rig = SyncRig(SyncSession(), mediaFileStore = files, immutableMediaSpoolOverride = spool)
            rig.awaitStartupRecovery()
            val avatarMutation = "11111111-1111-4111-8111-111111111111"
            rig.babies.seed(localBaby().copy(mutationId = avatarMutation))
            spool.freezeGroup(avatarMutation, listOf(ImmutableMediaSpoolSource(
                "22222222-2222-4222-8222-222222222222", CausalMediaRole.Avatar, "photos/avatar.jpg",
            )))
            spool.freezeGroup("33333333-3333-4333-8333-333333333333", listOf(ImmutableMediaSpoolSource(
                "44444444-4444-4444-8444-444444444444", CausalMediaRole.Log, "photos/care.jpg",
            )))
            assertThat(directory.walkTopDown().count { it.extension == "media" }).isEqualTo(2)

            rig.port.clearLocalData(LocalDataClearScope.RecordsOnly, realPortClearWorkflow {
                rig.records.deleteAll()
                rig.conflictDetails.deleteAllTransportJournals()
            }).getOrThrow()

            assertThat(directory.walkTopDown().count { it.extension == "media" }).isEqualTo(1)
            assertThat(spool.recoverAndSweep(setOf(avatarMutation)).keys).containsExactly(avatarMutation)
            assertThat(rig.backend.handshakeCalls).isEqualTo(0)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun allLocalClearRemovesOrphanSpoolBytesWithoutAnyNetworkOrLaterSync() = runTest {
        val directory = Files.createTempDirectory("offline-spool-clear").toFile()
        try {
            val files = TestMediaFileStore()
            val spool = FileImmutableMediaSpool(files, directory, 1024 * 1024, 1024)
            val rig = SyncRig(SyncSession(), mediaFileStore = files, immutableMediaSpoolOverride = spool)
            rig.awaitStartupRecovery()
            spool.freezeGroup(
                "11111111-1111-4111-8111-111111111111",
                listOf(ImmutableMediaSpoolSource(
                    "22222222-2222-4222-8222-222222222222", CausalMediaRole.Avatar, "photos/private.jpg",
                )),
            )
            assertThat(directory.walkTopDown().any { it.extension == "media" }).isTrue()

            rig.port.clearLocalData(LocalDataClearScope.AllLocalData, realPortClearWorkflow {
                rig.conflictDetails.deleteAllTransportJournals()
            }).getOrThrow()

            assertThat(directory.walkTopDown().any { it.extension == "media" }).isFalse()
            assertThat(rig.backend.handshakeCalls).isEqualTo(0)
            assertThat(rig.pendingReplicaCleanup.pending).isNull()
        } finally {
            directory.deleteRecursively()
        }
    }
}
