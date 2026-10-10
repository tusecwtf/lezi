package com.lezi.babylog.sync.clear

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.LocalDataClearScope
import com.lezi.babylog.sync.sourcerelation.SOURCE_RELATION_COMMAND_JOURNAL_KEY
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test

class LocalReplicaClearRestoreFileTest {
    @Test
    fun bothClearScopesKeepUnresolvedSourceRelationCommandUntilExplicitAbandonment() = runTest {
        for (scope in LocalDataClearScope.entries) {
            val rig = ClearRig()
            val originalPayload = """{"operation":"pending-choice","authority":"original-family","outcome":"unknown"}"""
            rig.cache.putTransportJournal(SOURCE_RELATION_COMMAND_JOURNAL_KEY, originalPayload, 29)
            val original = rig.cache.getTransportJournal(SOURCE_RELATION_COMMAND_JOURNAL_KEY)

            rig.coordinator.clear(scope) {
                rig.cache.deleteAllTransportJournals()
            }.getOrThrow()

            assertThat(rig.cache.getTransportJournal(SOURCE_RELATION_COMMAND_JOURNAL_KEY))
                .isEqualTo(original)
        }
    }

    @Test
    fun bothClearScopesPreserveEveryPriorRestoreFileOwnerAcrossBulkJournalDeletion() = runTest {
        for (scope in LocalDataClearScope.entries) {
            val rig = ClearRig()
            val ownerKey = "restore-file-owner-v1:11111111-1111-4111-8111-111111111111"
            val otherOwnerKey = "restore-file-owner-v1:22222222-2222-4222-8222-222222222222"
            val payload = """{"format":1,"phase":"retained","request":"11111111-1111-4111-8111-111111111111","ownership_sha256":"prior-owned-digest","ownership_bytes":123,"reason":"published"}"""
            rig.cache.putTransportJournal(ownerKey, payload, 17)
            rig.cache.putTransportJournal(otherOwnerKey, "opaque owner awaiting repair", 19)
            rig.cache.putTransportJournal("ordinary-transport", "discard me", 0)
            val owners = rig.cache.listRestoreFileOwners()

            rig.coordinator.clear(scope) {
                assertThat(rig.transactions.depth).isEqualTo(1)
                rig.cache.deleteAllTransportJournals()
            }.getOrThrow()

            assertThat(rig.cache.listRestoreFileOwners()).containsExactlyElementsIn(owners)
            assertThat(rig.cache.getTransportJournal("ordinary-transport")).isNull()
            assertThat(rig.pending.pending).isNull()
        }
    }

    @Test
    fun failedRestoreCleanupKeepsMarkerAndRetriesOutsideRoomAfterMediaDeletion() = runTest {
        val ownedPath = "record-media/restore-owned.jpg"
        var failReclaim = true
        val callbackDepths = mutableListOf<Int>()
        val snapshotDepths = mutableListOf<Int>()
        lateinit var rig: ClearRig
        rig = ClearRig(
            restoreOwnedPaths = {
                snapshotDepths += rig.transactions.depth
                setOf(ownedPath)
            },
            reclaimRestoreFiles = {
                callbackDepths += rig.transactions.depth
                assertThat(rig.media.getByClientUuid("old-media")).isNull()
                assertThat(rig.pending.pending).isNotNull()
                assertThat(rig.mediaFiles.existing).contains(ownedPath)
                if (failReclaim) error("restore cleanup interrupted")
                rig.mediaFiles.existing -= ownedPath
            },
        )
        rig.media.seed(media("old-media", "log", ownedPath))
        rig.mediaFiles.existing += ownedPath
        var roomClears = 0

        val failed = rig.coordinator.clear(LocalDataClearScope.AllLocalData) {
            roomClears += 1
        }.exceptionOrNull()

        assertThat(failed).isInstanceOf(LocalClearCommittedException::class.java)
        assertThat(failed!!.cause).hasMessageThat().isEqualTo("restore cleanup interrupted")
        assertThat(rig.pending.pending).isNotNull()
        assertThat(rig.media.getByClientUuid("old-media")).isNull()
        assertThat(rig.mediaFiles.existing).contains(ownedPath)

        rig.media.seed(media("new-media", "log", "record-media/new.jpg"))
        rig.mediaFiles.existing += "record-media/new.jpg"
        failReclaim = false
        rig.newCoordinator().recoverPending().getOrThrow()

        assertThat(callbackDepths).containsExactly(0, 0)
        assertThat(snapshotDepths).containsExactly(0, 0)
        assertThat(roomClears).isEqualTo(1)
        assertThat(rig.pending.pending).isNull()
        assertThat(rig.media.getByClientUuid("old-media")).isNull()
        assertThat(rig.media.getByClientUuid("new-media")).isNotNull()
        assertThat(rig.mediaFiles.existing).containsExactly("record-media/new.jpg")
        assertThat(rig.mediaFiles.deleted).doesNotContain(ownedPath)
    }

    @Test
    fun markerRetirementFailureRetriesOriginalCleanupAndStillPerformsTheNewExplicitClear() = runTest {
        val reclaimedSnapshots = mutableListOf<Set<String>>()
        lateinit var rig: ClearRig
        rig = ClearRig(reclaimRestoreFiles = {
            reclaimedSnapshots += requireNotNull(rig.pending.pending).mediaClientUuids
        })
        rig.media.seed(media("old-media", "log", "photos/old.jpg"))
        rig.transactions.failRunNumbers += 3
        var roomClears = 0

        val failed = rig.coordinator.clear(LocalDataClearScope.RecordsOnly) {
            roomClears += 1
        }.exceptionOrNull()

        assertThat(failed).isInstanceOf(LocalClearCommittedException::class.java)
        assertThat(rig.pending.pending).isNotNull()
        assertThat(rig.media.getByClientUuid("old-media")).isNull()
        rig.media.seed(media("new-media", "log", "photos/new.jpg"))

        rig.newCoordinator().clear(LocalDataClearScope.RecordsOnly) {
            roomClears += 1
        }.getOrThrow()

        assertThat(reclaimedSnapshots).containsExactly(
            setOf("old-media"), setOf("old-media"), setOf("new-media"),
        ).inOrder()
        assertThat(roomClears).isEqualTo(2)
        assertThat(rig.media.listAllIncludingDeleted()).isEmpty()
        assertThat(rig.pending.pending).isNull()
    }

    @Test
    fun callerCancellationWaitsForRestoreReclamationAndMarkerRetirement() = runTest {
        val reclaimStarted = CompletableDeferred<Unit>()
        val allowReclaim = CompletableDeferred<Unit>()
        lateinit var rig: ClearRig
        rig = ClearRig(reclaimRestoreFiles = {
            assertThat(rig.transactions.depth).isEqualTo(0)
            reclaimStarted.complete(Unit)
            allowReclaim.await()
        })
        rig.media.seed(media("old-media", "log", "photos/old.jpg"))
        var result: Result<Unit>? = null
        val clearing = launch {
            result = rig.coordinator.clear(LocalDataClearScope.RecordsOnly) {}
        }
        reclaimStarted.await()
        clearing.cancel()
        allowReclaim.complete(Unit)
        clearing.join()

        assertThat(result!!.exceptionOrNull()).isInstanceOf(CancellationException::class.java)
        assertThat(rig.media.getByClientUuid("old-media")).isNull()
        assertThat(rig.pending.pending).isNull()
    }
}
