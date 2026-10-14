package com.lezi.babylog.domain

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lezi.babylog.core.database.FulfillmentCandidateEntity
import com.lezi.babylog.core.database.LeziDatabase
import com.lezi.babylog.core.database.MAX_EXPLICIT_PROJECTION_ROOTS
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.core.database.RecordDao
import com.lezi.babylog.core.database.RecordEntity
import com.lezi.babylog.core.database.SearchSleepRecordProjection
import com.lezi.babylog.core.database.causal.WakeObservationEntity
import com.lezi.babylog.domain.carelog.CareLogQueries
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import java.util.concurrent.Executor

/** Real generated Room DAO + production search/projection; requires an isolated API26+ device. */
@RunWith(AndroidJUnit4::class)
class CareLogSearchRoomDeviceTest {
    private val sqlQueries = Collections.synchronizedList(mutableListOf<String>())
    private val database = Room.inMemoryDatabaseBuilder(
        InstrumentationRegistry.getInstrumentation().targetContext,
        LeziDatabase::class.java,
    ).setQueryCallback({ sql, _ -> sqlQueries.add(sql) }, Executor { it.run() }).build()

    @After
    fun tearDown() = database.close()

    @Test
    fun realRoomSearchMatchesWakeDerivedTextAndWithdrawal() = runBlocking {
        val id = database.recordDao().upsert(sleep("sleep"))
        val queries = queries()
        assertEquals(listOf(id), queries.search(1, "进行中").map { it.id })
        val wakeId = database.wakeObservationDao().upsert(
            WakeObservationEntity(
                clientUuid = "wake",
                sleepRecordClientUuid = "sleep",
                wakeTimestamp = START + 30 * MINUTE,
                updatedAt = START + 30 * MINUTE,
            ),
        )

        assertTrue(queries.search(1, "进行中").isEmpty())
        assertEquals(listOf(id), queries.search(1, "30m").map { it.id })
        assertEquals(listOf(id), queries.search(1, "30").map { it.id })
        val wake = database.wakeObservationDao().getByClientUuid("wake")!!
        assertEquals(wakeId, wake.id)
        database.wakeObservationDao().update(wake.copy(withdrawn = true))

        assertTrue(queries.search(1, "30m").isEmpty())
        assertEquals(listOf(id), queries.search(1, "进行中").map { it.id })
    }

    @Test
    fun realRoomCandidatesExcludeOtherBabiesTombstonesAndUnadoptedRecords() = runBlocking {
        val end = START + 30 * MINUTE
        val visibleId = database.recordDao().upsert(sleep("visible").copy(endTimestamp = end))
        database.recordDao().upsert(sleep("hidden").copy(endTimestamp = end))
        database.recordDao().upsert(sleep("deleted").copy(endTimestamp = end, deletedAt = end))
        database.recordDao().upsert(sleep("other-baby").copy(babyId = 2, endTimestamp = end))
        database.recordDao().upsert(sleep("unadopted").copy(endTimestamp = end))
        database.fulfillmentCandidateDao().upsert(
            FulfillmentCandidateEntity(
                clientUuid = "candidate",
                carePlanClientUuid = "plan",
                recordClientUuid = "unadopted",
                confirmedAt = end,
                adoptionStatus = "conflict_not_adopted",
                updatedAt = end,
            ),
        )

        assertEquals(
            listOf(visibleId),
            queries().search(1, "30m", hiddenSourceClientUuids = setOf("hidden")).map { it.id },
        )
    }

    @Test
    fun realRoomTombstoneBetweenCandidateAndProjectionIsNotResurrected() = runBlocking {
        val id = database.recordDao().upsert(sleep("sleep"))
        val records = object : RecordDao by database.recordDao() {
            override suspend fun searchCandidates(
                babyId: Long,
                escapedPattern: String,
                matchingTypeKeys: List<String>,
            ): List<RecordEntity> {
                val selected = database.recordDao().searchCandidates(babyId, escapedPattern, matchingTypeKeys)
                val row = database.recordDao().get(id)!!
                database.recordDao().update(row.copy(deletedAt = START + MINUTE))
                return selected
            }
        }

        assertTrue(queries(records).search(1, "睡眠").isEmpty())
    }

    @Test
    fun realRoomModernHistoryUsesOnePeerContextAndNoMediaQueries() = runBlocking {
        val count = MAX_EXPLICIT_PROJECTION_ROOTS * 2 + 1
        val requested = mutableListOf<String>()
        repeat(count) { index ->
            val uuid = "sleep-$index"
            val start = START + index * 60 * MINUTE
            database.recordDao().upsert(sleep(uuid).copy(timestamp = start))
            val wakeId = database.wakeObservationDao().upsert(WakeObservationEntity(
                clientUuid = "wake-$index", sleepRecordClientUuid = uuid,
                wakeTimestamp = start + 30 * MINUTE, updatedAt = start,
            ))
            database.mediaAssetDao().upsert(MediaAssetEntity(
                clientUuid = "media-$index", kind = "wake", wakeObservationId = wakeId,
                localUri = "test-$index.jpg", createdAt = start,
            ))
            requested += uuid
        }
        database.recordDao().upsert(sleep("legacy").copy(endTimestamp = START + 30 * MINUTE))
        database.recordDao().upsert(sleep("older-open").copy(timestamp = START + count * 60 * MINUTE))
        database.recordDao().upsert(sleep("hidden-peer").copy(timestamp = START + (count + 1) * 60 * MINUTE))
        requested += "legacy"
        requested += "older-open"
        // The hidden source-role peer is not requested but must still arbitrate overlap.
        val canonical = requested.chunked(MAX_EXPLICIT_PROJECTION_ROOTS).flatMap { chunk ->
            database.timelineWindowDao().loadRecordProjectionForRoots(chunk)
        }.map { SearchSleepRecordProjection(it.root, requireNotNull(it.sleepInterval)) }
        sqlQueries.clear()

        val projected = database.timelineWindowDao().loadSearchSleepProjection(1, requested)

        assertEquals(canonical, projected)
        assertTrue(projected.single { it.root.clientUuid == "older-open" }.sleepInterval.isOverlapPending)
        val trace = synchronized(sqlQueries) { sqlQueries.toList() }
        // Room may finish asynchronous invalidation bookkeeping after fixture writes.
        // Count only application reads, matching the existing projection device tests.
        assertEquals(5, trace.count {
            it.trimStart().startsWith("SELECT", ignoreCase = true) &&
                !it.contains("room_table_modification_log", ignoreCase = true)
        })
        assertEquals(2, trace.count { it.contains("FROM records", ignoreCase = true) })
        assertEquals(3, trace.count { it.contains("FROM wake_observations", ignoreCase = true) })
        assertTrue(trace.none { it.contains("media_assets", ignoreCase = true) })
    }

    private fun queries(records: RecordDao = database.recordDao()) = CareLogQueries(
        babyDao = database.babyDao(),
        recordDao = records,
        carePlanDao = database.carePlanDao(),
        fulfillmentCandidateDao = database.fulfillmentCandidateDao(),
        recordWakeProjectionDao = database.timelineWindowDao(),
    )

    private fun sleep(uuid: String) = RecordEntity(
        clientUuid = uuid,
        babyId = 1,
        type = "sleep",
        timestamp = START,
        payloadJson = """{"is_nap":false,"anomaly_flag":false}""",
        updatedAt = START,
    )

    private companion object {
        const val START = 1_700_000_000_000L
        const val MINUTE = 60_000L
    }
}
