package com.lezi.babylog.domain.carelog

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.common.MediaContentDigest
import com.lezi.babylog.core.model.RecordType
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Android Main calls and real SHA-256/Room, not a JVM dispatcher named Main. */
@RunWith(AndroidJUnit4::class)
class CareLogMainSafePhotoSaveDeviceTest {
    @Test(timeout = 120_000)
    fun publicRecordAndPlanSavesHashThreeRealPhotosOffAndroidMainBeforeCommit() = runBlocking {
        val events = CopyOnWriteArrayList<String>()
        val pathsHashed = CopyOnWriteArrayList<String>()
        CareLogRoomAcceptanceRig(beforePhotoDigest = { path ->
            assertThat(Looper.myLooper()).isNotEqualTo(Looper.getMainLooper())
            pathsHashed += path
            events += "hash"
        }).use { rig ->
            val baby = rig.createBaby()
            val paths = (1..3).map { rig.photo("main-safe-$it.png").path }
            val expectedDigests = paths.associateWith { requireNotNull(MediaContentDigest.ofReadableFile(it)) }
            val now = System.currentTimeMillis()

            suspend fun saveFromMain(block: suspend () -> Long): Long {
                events.clear()
                pathsHashed.clear()
                rig.boundaries.reset()
                rig.boundaries.onBoundary = { events += it }
                val result = withContext(Dispatchers.Main.immediate) {
                    assertThat(Looper.myLooper()).isEqualTo(Looper.getMainLooper())
                    block()
                }
                assertThat(pathsHashed).containsExactlyElementsIn(paths)
                assertThat(events.indexOfLast { it == "hash" })
                    .isLessThan(events.indexOfFirst { it.startsWith("transaction.before#") })
                assertThat(events.any { it.startsWith("transaction.afterCommit#") }).isTrue()
                return result
            }

            val record = saveFromMain {
                rig.care.addRecord(baby, RecordType.PEE, timestamp = now,
                    payloadJson = """{"pee_amount":2}""", photoLocalPaths = paths, nowMillis = now)
            }
            saveFromMain {
                rig.care.updateRecord(record, now, null, "根备注", """{"pee_amount":2}""",
                    photoLocalPaths = paths, nowMillis = now)
                record
            }
            val plan = saveFromMain {
                rig.care.createCarePlan(baby, RecordType.PEE, scheduledAt = now + 60_000L,
                    payloadJson = """{"pee_amount":2}""", photoLocalPaths = paths,
                    nowMillis = now, projectToSystemCalendar = false)
            }
            assertThat(rig.care.listRecordPhotoPaths(record)).containsExactlyElementsIn(paths)
            assertThat(rig.care.listCarePlanPhotoPaths(plan)).containsExactlyElementsIn(paths)
            val media = rig.db.mediaAssetDao().listAllIncludingDeleted().filter { it.deletedAt == null }
            assertThat(media).hasSize(6)
            media.forEach { assertThat(it.sha256).isEqualTo(expectedDigests.getValue(it.localUri)) }
            rig.assertProductionSchema()
        }
    }
}
