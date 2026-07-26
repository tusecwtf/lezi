package com.lezi.babylog.core.database

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecordAuthorMergeRoomTest {
    private lateinit var database: LeziDatabase
    private lateinit var dao: RecordDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            LeziDatabase::class.java,
        ).build()
        dao = database.recordDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun canonicalAuthorMergeOnlyChangesMetadataAtTheExpectedRevision() = runBlocking {
        val babyId = database.babyDao().upsert(
            BabyEntity(
                familyId = 1,
                nickname = "年年",
                birthdayEpochDay = 20_000,
                themeColorArgb = 0,
                clientUuid = "baby-author-merge",
                updatedAt = 40,
                syncDirty = false,
            ),
        )
        dao.upsert(
            RecordEntity(
                clientUuid = "record-author-merge",
                babyId = babyId,
                type = "formula",
                timestamp = 50,
                note = "本地内容",
                createdByUserId = 1,
                createdByMembershipId = "",
                payloadJson = """{"amount_ml":120}""",
                updatedAt = 50,
                syncDirty = true,
            ),
        )

        assertEquals(
            1,
            dao.mergeCanonicalAuthor(
                clientUuid = "record-author-merge",
                expectedUpdatedAt = 50,
                membershipId = "membership-a",
            ),
        )
        val merged = requireNotNull(dao.getByClientUuid("record-author-merge"))
        assertEquals("membership-a", merged.createdByMembershipId)
        assertEquals("本地内容", merged.note)
        assertEquals("""{"amount_ml":120}""", merged.payloadJson)
        assertEquals(50, merged.updatedAt)
        assertEquals(true, merged.syncDirty)

        assertEquals(
            0,
            dao.mergeCanonicalAuthor(
                clientUuid = "record-author-merge",
                expectedUpdatedAt = 49,
                membershipId = "membership-stale",
            ),
        )
        assertEquals(
            "membership-a",
            dao.getByClientUuid("record-author-merge")?.createdByMembershipId,
        )
    }
}
