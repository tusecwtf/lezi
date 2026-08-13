package com.lezi.babylog.core.database.causal

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lezi.babylog.core.database.BabyEntity
import com.lezi.babylog.core.database.CarePlanEntity
import com.lezi.babylog.core.database.CustomItemEntity
import com.lezi.babylog.core.database.LeziDatabase
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.core.database.RecordEntity
import java.util.Collections
import java.util.concurrent.Executor
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConflictInboxRoomProjectionDeviceTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun oneBatchedQueryProjectsFiveRootsAndInvalidatesForEveryOwnedTable() = runBlocking {
        val statements = Collections.synchronizedList(mutableListOf<String>())
        val database = Room.inMemoryDatabaseBuilder(context, LeziDatabase::class.java)
            .setQueryCallback({ sql, _ -> statements += sql }, Executor { it.run() })
            .build()
        try {
            val roots = seedFiveRootKinds(database)
            seedScaleRows(database)

            statements.clear()
            val emissions = Channel<List<ConflictInboxProjectionRow>>(Channel.UNLIMITED)
            val observer = launch(start = CoroutineStart.UNDISPATCHED) {
                database.conflictSummaryDao().observeInboxProjection().collect { emissions.send(it) }
            }
            val initial = withTimeout(2_000) { emissions.receive() }
            assertEquals(105, initial.size)
            assertEquals(
                listOf(CONFLICT_BABY, CONFLICT_RECORD, CONFLICT_PLAN, CONFLICT_CUSTOM, CONFLICT_WAKE),
                initial.take(5).map { it.conflictId },
            )
            assertEquals(listOf("scale-000", "scale-001"), initial.drop(5).take(2).map { it.conflictId })
            assertTrue(initial.none { it.conflictId == CONFLICT_STALE })
            assertFiveRootProjection(initial.take(5))
            assertQueryCount(statements, 1)

            database.babyDao().upsert(roots.baby.copy(nickname = "年年"))
            assertEquals("年年", withTimeout(2_000) { emissions.receive() }.first().localTitle)
            assertQueryCount(statements, 2)

            database.mediaAssetDao().upsert(
                MediaAssetEntity(
                    babyId = roots.baby.id,
                    clientUuid = "avatar-2",
                    kind = "avatar",
                    localUri = "avatar-2.jpg",
                    createdAt = 600,
                ),
            )
            assertEquals(2, withTimeout(2_000) { emissions.receive() }.first().localMediaCount)
            assertQueryCount(statements, 3)

            database.conflictSnapshotCacheDao().upsert(
                ConflictSnapshotCacheEntity(
                    conflictId = CONFLICT_BABY,
                    snapshotJson = "cached-snapshot",
                    cachedAt = 700,
                ),
            )
            assertEquals("cached-snapshot", withTimeout(2_000) { emissions.receive() }.first().snapshotJson)
            assertQueryCount(statements, 4)

            observer.cancelAndJoin()
        } finally {
            database.close()
        }
    }

    private suspend fun seedFiveRootKinds(database: LeziDatabase): SeededRoots {
        val babyId = database.babyDao().upsert(
            BabyEntity(
                familyId = 1,
                nickname = "豆豆",
                birthdayEpochDay = 20_000,
                themeColorArgb = 0xff6688aa.toInt(),
                clientUuid = "baby-1",
                updatedAt = 500,
                deletedAt = 501,
            ),
        )
        val baby = database.babyDao().getByClientUuid("baby-1")!!
        val recordId = database.recordDao().upsert(
            RecordEntity(
                clientUuid = "record-1",
                babyId = babyId,
                type = "nursing",
                timestamp = 400,
                updatedAt = 400,
                createdByMembershipId = "member-record",
            ),
        )
        val planId = database.carePlanDao().upsert(
            CarePlanEntity(
                clientUuid = "plan-1",
                babyId = babyId,
                type = "formula",
                scheduledAt = 300,
                scheduledZoneId = "Asia/Shanghai",
                createdByMembershipId = "member-plan",
                updatedAt = 300,
                deletedAt = 301,
            ),
        )
        database.customItemDao().upsert(
            CustomItemEntity(
                clientUuid = "custom-1",
                familyId = 1,
                name = "维生素",
                iconSlot = 1,
                createdByMembershipId = "member-custom",
                updatedAt = 200,
            ),
        )
        val wakeId = database.wakeObservationDao().upsert(
            WakeObservationEntity(
                clientUuid = "wake-1",
                sleepRecordClientUuid = "record-1",
                wakeTimestamp = 100,
                observerMembershipId = "member-wake",
                updatedAt = 100,
                deletedAt = 101,
            ),
        )
        listOf(
            summary(CONFLICT_BABY, "baby", "baby-1", 500),
            summary(CONFLICT_STALE, "baby", "baby-1", 499),
            summary(CONFLICT_RECORD, "record", "record-1", 400),
            summary(CONFLICT_PLAN, "care_plan", "plan-1", 300),
            summary(CONFLICT_CUSTOM, "custom_item", "custom-1", 200),
            summary(CONFLICT_WAKE, "wake_observation", "wake-1", 100),
        ).forEach { database.conflictSummaryDao().upsert(it) }
        listOf(
            MediaAssetEntity(
                babyId = babyId,
                clientUuid = "avatar-1",
                kind = "avatar",
                localUri = "avatar-1.jpg",
                createdAt = 500,
            ),
            MediaAssetEntity(
                recordId = recordId,
                clientUuid = "record-photo",
                localUri = "record.jpg",
                createdAt = 400,
            ),
            MediaAssetEntity(
                carePlanId = planId,
                clientUuid = "plan-photo",
                localUri = "plan.jpg",
                createdAt = 300,
            ),
            MediaAssetEntity(
                wakeObservationId = wakeId,
                clientUuid = "wake-photo",
                kind = "wake",
                localUri = "wake.jpg",
                createdAt = 100,
            ),
        ).forEach { database.mediaAssetDao().upsert(it) }
        return SeededRoots(baby)
    }

    private suspend fun seedScaleRows(database: LeziDatabase) {
        repeat(100) { index ->
            val suffix = index.toString().padStart(3, '0')
            database.customItemDao().upsert(
                CustomItemEntity(
                    clientUuid = "scale-root-$suffix",
                    familyId = 1,
                    name = "scale $suffix",
                    iconSlot = 0,
                    updatedAt = 50,
                ),
            )
            database.conflictSummaryDao().upsert(
                summary("scale-$suffix", "custom_item", "scale-root-$suffix", 50),
            )
        }
    }

    private fun assertFiveRootProjection(rows: List<ConflictInboxProjectionRow>) {
        val byConflict = rows.associateBy { it.conflictId }
        assertRow(byConflict.getValue(CONFLICT_BABY), "baby", "豆豆", "豆豆", null, true, 1)
        assertRow(
            byConflict.getValue(CONFLICT_RECORD),
            "record",
            "nursing",
            "豆豆",
            "member-record",
            false,
            1,
        )
        assertRow(
            byConflict.getValue(CONFLICT_PLAN),
            "care_plan",
            "formula",
            "豆豆",
            "member-plan",
            true,
            1,
        )
        assertRow(
            byConflict.getValue(CONFLICT_CUSTOM),
            "custom_item",
            "维生素",
            null,
            "member-custom",
            false,
            0,
        )
        assertRow(
            byConflict.getValue(CONFLICT_WAKE),
            "wake_observation",
            null,
            "豆豆",
            "member-wake",
            true,
            1,
        )
    }

    private fun assertRow(
        row: ConflictInboxProjectionRow,
        entityType: String,
        localTitle: String?,
        babyLabel: String?,
        actorId: String?,
        tombstone: Boolean,
        mediaCount: Int,
    ) {
        assertEquals(entityType, row.entityType)
        assertEquals(localTitle, row.localTitle)
        assertEquals(babyLabel, row.babyLabel)
        assertEquals(actorId, row.localActorId)
        assertEquals(tombstone, row.localTombstone)
        assertEquals(mediaCount, row.localMediaCount)
        assertNull(row.snapshotJson)
    }

    private fun assertQueryCount(statements: List<String>, expected: Int) {
        assertEquals(
            expected,
            statements.count { it.contains("WITH canonical AS", ignoreCase = true) },
        )
    }

    private fun summary(
        conflictId: String,
        entityType: String,
        clientUuid: String,
        updatedAt: Long,
    ) = ConflictSummaryEntity(
        conflictId = conflictId,
        entityType = entityType,
        clientUuid = clientUuid,
        stableVersionId = "stable-$clientUuid",
        status = "open",
        kind = "concurrent",
        updatedAt = updatedAt,
    )

    private data class SeededRoots(val baby: BabyEntity)
}

private const val CONFLICT_BABY = "conflict-baby"
private const val CONFLICT_STALE = "conflict-stale"
private const val CONFLICT_RECORD = "conflict-record"
private const val CONFLICT_PLAN = "conflict-plan"
private const val CONFLICT_CUSTOM = "conflict-custom"
private const val CONFLICT_WAKE = "conflict-wake"
