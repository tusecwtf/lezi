package com.lezi.babylog.core.database

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Room regressions for synthetic elevated-root publication receipts after
 * standalone log/avatar packages (see EphemeralPublishPipeline + ADR-0008).
 */
@RunWith(AndroidJUnit4::class)
class SyntheticRootPublicationRoomTest {
    private lateinit var database: LeziDatabase
    private lateinit var records: RecordDao
    private lateinit var carePlans: CarePlanDao
    private lateinit var babies: BabyDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            LeziDatabase::class.java,
        ).build()
        records = database.recordDao()
        carePlans = database.carePlanDao()
        babies = database.babyDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun recordAdvancesRevisionAndReceiptWhenContentEpochMatches() = runBlocking {
        records.upsert(
            RecordEntity(
                clientUuid = "rec-syn",
                babyId = 1,
                type = "formula",
                timestamp = 1,
                payloadJson = """{"amount_ml":1}""",
                updatedAt = 100,
                familyPublishedUpdatedAt = 100,
                syncDirty = false,
            ),
        )

        assertTrue(
            records.acknowledgeSyntheticRootPublication(
                clientUuid = "rec-syn",
                expectedLocalUpdatedAt = 100,
                publishedUpdatedAt = 101,
            ),
        )
        val row = requireNotNull(records.getByClientUuid("rec-syn"))
        assertEquals(101L, row.updatedAt)
        assertEquals(101L, row.familyPublishedUpdatedAt)
        assertFalse(row.syncDirty)
    }

    @Test
    fun recordConcurrentEditKeepsContentAndMonotonicReceipt() = runBlocking {
        records.upsert(
            RecordEntity(
                clientUuid = "rec-conc",
                babyId = 1,
                type = "formula",
                timestamp = 1,
                payloadJson = """{"amount_ml":1}""",
                note = "live",
                updatedAt = 200,
                familyPublishedUpdatedAt = 100,
                syncDirty = true,
            ),
        )

        assertFalse(
            records.acknowledgeSyntheticRootPublication(
                clientUuid = "rec-conc",
                expectedLocalUpdatedAt = 100,
                publishedUpdatedAt = 101,
            ),
        )
        val row = requireNotNull(records.getByClientUuid("rec-conc"))
        assertEquals(200L, row.updatedAt)
        assertEquals("live", row.note)
        assertTrue(row.syncDirty)
        assertEquals(101L, row.familyPublishedUpdatedAt)
    }

    @Test
    fun carePlanSyntheticAckMatchesRecordReceiptSemantics() = runBlocking {
        carePlans.upsert(
            CarePlanEntity(
                clientUuid = "plan-syn",
                babyId = 1,
                type = "formula",
                scheduledAt = 9_000_000_000_000L,
                scheduledZoneId = "Asia/Shanghai",
                payloadJson = """{"amount_ml":1}""",
                updatedAt = 50,
                familyPublishedUpdatedAt = 50,
                syncDirty = true,
            ),
        )

        assertTrue(
            carePlans.acknowledgeSyntheticRootPublication(
                clientUuid = "plan-syn",
                expectedLocalUpdatedAt = 50,
                publishedUpdatedAt = 55,
            ),
        )
        val row = requireNotNull(carePlans.getByClientUuid("plan-syn"))
        assertEquals(55L, row.updatedAt)
        assertEquals(55L, row.familyPublishedUpdatedAt)
        assertFalse(row.syncDirty)
    }

    @Test
    fun recordConcurrentEditToExactlyPublishedKeepsDirtyAndBody() = runBlocking {
        records.upsert(
            RecordEntity(
                clientUuid = "rec-pub-conc",
                babyId = 1,
                type = "formula",
                timestamp = 1,
                payloadJson = """{"amount_ml":2}""",
                note = "edited-to-published-clock",
                updatedAt = 401,
                familyPublishedUpdatedAt = 400,
                syncDirty = true,
            ),
        )

        assertFalse(
            records.acknowledgeSyntheticRootPublication(
                clientUuid = "rec-pub-conc",
                expectedLocalUpdatedAt = 400,
                publishedUpdatedAt = 401,
            ),
        )
        val row = requireNotNull(records.getByClientUuid("rec-pub-conc"))
        assertEquals(401L, row.updatedAt)
        assertEquals("edited-to-published-clock", row.note)
        assertTrue(row.syncDirty)
        assertEquals(401L, row.familyPublishedUpdatedAt)
    }

    @Test
    fun carePlanConcurrentEditToExactlyPublishedKeepsDirty() = runBlocking {
        carePlans.upsert(
            CarePlanEntity(
                clientUuid = "plan-pub-conc",
                babyId = 1,
                type = "formula",
                scheduledAt = 9_000_000_000_000L,
                scheduledZoneId = "Asia/Shanghai",
                payloadJson = """{"amount_ml":3}""",
                updatedAt = 801,
                familyPublishedUpdatedAt = 800,
                syncDirty = true,
            ),
        )

        assertFalse(
            carePlans.acknowledgeSyntheticRootPublication(
                clientUuid = "plan-pub-conc",
                expectedLocalUpdatedAt = 800,
                publishedUpdatedAt = 801,
            ),
        )
        val row = requireNotNull(carePlans.getByClientUuid("plan-pub-conc"))
        assertEquals(801L, row.updatedAt)
        assertTrue(row.syncDirty)
        assertEquals(801L, row.familyPublishedUpdatedAt)
    }

    @Test
    fun babySyntheticAckAdvancesWatermarkAndKeepsConcurrentDirty() = runBlocking {
        babies.upsert(
            BabyEntity(
                familyId = 1,
                nickname = "宝宝",
                birthdayEpochDay = 20_000,
                themeColorArgb = 0,
                clientUuid = "baby-syn",
                updatedAt = 70,
                syncDirty = false,
            ),
        )

        assertTrue(
            babies.acknowledgeSyntheticRootPublication(
                clientUuid = "baby-syn",
                expectedLocalUpdatedAt = 70,
                publishedUpdatedAt = 71,
            ),
        )
        val row = requireNotNull(babies.getByClientUuid("baby-syn"))
        assertEquals(71L, row.updatedAt)
        assertFalse(row.syncDirty)

        // Concurrent profile edit: do not clobber, leave dirty for outbox rebuild.
        babies.update(row.copy(nickname = "新昵称", updatedAt = 90, syncDirty = true))
        assertFalse(
            babies.acknowledgeSyntheticRootPublication(
                clientUuid = "baby-syn",
                expectedLocalUpdatedAt = 70,
                publishedUpdatedAt = 71,
            ),
        )
        val concurrent = requireNotNull(babies.getByClientUuid("baby-syn"))
        assertEquals("新昵称", concurrent.nickname)
        assertEquals(90L, concurrent.updatedAt)
        assertTrue(concurrent.syncDirty)

        // Concurrent edit that lands exactly on published clock keeps dirty.
        babies.update(concurrent.copy(nickname = "同刻", updatedAt = 71, syncDirty = true))
        assertFalse(
            babies.acknowledgeSyntheticRootPublication(
                clientUuid = "baby-syn",
                expectedLocalUpdatedAt = 70,
                publishedUpdatedAt = 71,
            ),
        )
        val atPublished = requireNotNull(babies.getByClientUuid("baby-syn"))
        assertEquals("同刻", atPublished.nickname)
        assertEquals(71L, atPublished.updatedAt)
        assertTrue(atPublished.syncDirty)
    }
}
