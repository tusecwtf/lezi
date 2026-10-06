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
class LocalReplicaClearAllLocalTest {
    @Test
    fun allLocalClearsEveryCapturedReplicaAndDropsGeneration() = runTest {
        val rig = ClearRig(
            session = joinedClearSession().copy(
                pullCursor = 11,
                pullGeneration = "known-generation",
            ),
        )
        val babyId = rig.babies.seed(baby(avatarPath = "avatars/baby.jpg"))
        rig.records.seed(
            record(
                babyId = babyId,
                payloadJson = """{"photos":["photos/inline.jpg"]}""",
            ),
        )
        rig.media.seed(media("log-media", "log", "photos/log.jpg"))
        rig.media.seed(media("avatar-media", "avatar", "avatars/baby.jpg"))

        val result = rig.coordinator.clear(LocalDataClearScope.AllLocalData) {
            rig.records.deleteAll()
            rig.babies.deleteAll()
        }

        assertThat(result.isSuccess).isTrue()
        assertThat(rig.preferences.current().pullCursor).isEqualTo(0)
        assertThat(rig.preferences.current().pullGeneration).isEmpty()
        assertThat(rig.media.listAllIncludingDeleted()).isEmpty()
        assertThat(rig.mediaFiles.deleted).containsExactly(
            "photos/log.jpg",
            "avatars/baby.jpg",
        )
        assertThat(rig.mediaFiles.deleted).doesNotContain("photos/inline.jpg")
        assertThat(rig.pending.pending).isNull()
    }
}
