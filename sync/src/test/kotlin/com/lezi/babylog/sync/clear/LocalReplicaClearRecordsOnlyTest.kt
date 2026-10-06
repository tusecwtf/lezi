package com.lezi.babylog.sync.clear
import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.BabyEntity
import com.lezi.babylog.core.database.CarePlanEntity
import com.lezi.babylog.core.database.DatabaseTransactionRunner
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.core.database.PendingReplicaCleanup
import com.lezi.babylog.core.database.LocalDataClearScope
import com.lezi.babylog.core.database.PendingReplicaCleanupStore
import com.lezi.babylog.core.database.RecordEntity
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import com.lezi.babylog.sync.LocalClearWorkflow
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.sync.session.SyncSession
import com.lezi.babylog.sync.MemoryBabyDao
import com.lezi.babylog.sync.MemoryCarePlanDao
import com.lezi.babylog.sync.MemoryMediaDao
import com.lezi.babylog.sync.MemoryRecordDao
import com.lezi.babylog.sync.MemorySyncPreferences
import com.lezi.babylog.sync.TestMediaFileStore

@OptIn(ExperimentalCoroutinesApi::class)
// Contract-cluster split (ticket 08).
class LocalReplicaClearRecordsOnlyTest {
    @Test
    fun recordsOnlyRecoveryDoesNotShortCircuitARequestedAllLocalClear() = runTest {
        val rig = ClearRig()
        var roomCalls = 0

        val result = rig.coordinator.clear(
            scope = LocalDataClearScope.AllLocalData,
            workflow = testLocalClearWorkflow { roomCalls += 1 },
            recoverDomain = { LocalDataClearScope.RecordsOnly },
        )

        assertThat(result.isSuccess).isTrue()
        assertThat(roomCalls).isEqualTo(1)
        assertThat(rig.transactions.runCount).isEqualTo(2)
    }
    @Test
    fun recordsOnlyClearsRecordAndPlanReplicaButPreservesAvatarAndGeneration() = runTest {
        val originalSession = joinedClearSession().copy(
            pullCursor = 9,
            pullGeneration = "known-generation",
            membershipId = "membership-a",
        )
        val rig = ClearRig(session = originalSession)
        rig.records.seed(record(payloadJson = """{"photos":["photos/record-inline.jpg"]}"""))
        rig.carePlans.seed(carePlan("""{"photos":["photos/plan-inline.jpg"]}"""))
        rig.media.seed(media("record-media", "log", "photos/record.jpg"))
        rig.media.seed(media("plan-media", "log", "photos/plan.jpg", carePlanId = 1))
        rig.media.seed(media("avatar-media", "avatar", "avatars/baby.jpg"))
        val result = rig.coordinator.clear(LocalDataClearScope.RecordsOnly) {
            rig.records.deleteAll()
            rig.carePlans.deleteAll()
        }

        assertThat(result.isSuccess).isTrue()
        assertThat(rig.preferences.current()).isEqualTo(originalSession.copy(pullCursor = 0))
        assertThat(rig.media.getByClientUuid("record-media")).isNull()
        assertThat(rig.media.getByClientUuid("plan-media")).isNull()
        assertThat(rig.media.getByClientUuid("avatar-media")).isNotNull()
        assertThat(rig.mediaFiles.deleted).containsExactly(
            "photos/record.jpg",
            "photos/plan.jpg",
        )
    }
    @Test
    fun recordsOnlyNeverDeletesAPathStillOwnedByAnAvatar() = runTest {
        val rig = ClearRig()
        val sharedPath = "avatars/shared.jpg"
        rig.records.seed(record(payloadJson = """{"photos":["$sharedPath"]}"""))
        rig.media.seed(media("record-shared", "log", sharedPath))
        rig.media.seed(media("avatar-shared", "avatar", sharedPath))

        val result = rig.coordinator.clear(LocalDataClearScope.RecordsOnly) {
            rig.records.deleteAll()
        }

        assertThat(result.isSuccess).isTrue()
        assertThat(rig.media.getByClientUuid("record-shared")).isNull()
        assertThat(rig.media.getByClientUuid("avatar-shared")).isNotNull()
        assertThat(rig.mediaFiles.deleted).doesNotContain(sharedPath)
    }
    @Test
    fun recordsOnlySweepsOrphanDraftImportsButPreservesAvatarRoot() = runTest {
        val rig = ClearRig()
        val orphanDraft = "record-media/orphan-draft.jpg"
        val avatarOwnedRecordPath = "record-media/avatar-owned.jpg"
        val orphanAvatar = "baby_avatars/orphan-avatar.jpg"
        rig.mediaFiles.existing += orphanDraft
        rig.mediaFiles.existing += avatarOwnedRecordPath
        rig.mediaFiles.existing += orphanAvatar
        rig.media.seed(media("avatar-in-record-root", "avatar", avatarOwnedRecordPath))

        val result = rig.coordinator.clear(LocalDataClearScope.RecordsOnly) {}

        assertThat(result.isSuccess).isTrue()
        assertThat(rig.mediaFiles.existing).doesNotContain(orphanDraft)
        assertThat(rig.mediaFiles.existing).contains(avatarOwnedRecordPath)
        assertThat(rig.mediaFiles.existing).contains(orphanAvatar)
    }
    @Test
    fun recordsOnlyChunksLargeCapturedMediaDeletes() = runTest {
        val rig = ClearRig()
        repeat(1_005) { index ->
            val uuid = "log-media-$index"
            rig.media.seed(media(uuid, "log", "photos/$index.jpg"))
        }

        val result = rig.coordinator.clear(LocalDataClearScope.RecordsOnly) {}

        assertThat(result.isSuccess).isTrue()
        assertThat(rig.media.listAllIncludingDeleted()).isEmpty()
    }
}
