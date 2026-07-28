package com.lezi.babylog.core.database

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FreshDatabaseTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private var database: LeziDatabase? = null
    private val databaseNames = mutableSetOf<String>()

    @After
    fun tearDown() {
        database?.close()
        databaseNames.forEach(context::deleteDatabase)
    }

    @Test
    fun freshDatabaseCreatesCurrentSchema() {
        val db = openDatabase("fresh-schema")
        val sqlite = db.openHelper.writableDatabase

        assertEquals(23, sqlite.version)
        val tables = buildSet {
            sqlite.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { cursor ->
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
        assertTrue(
            tables.containsAll(
                setOf(
                    "local_users",
                    "families",
                    "memberships",
                    "babies",
                    "records",
                    "care_plans",
                    "fulfillment_candidates",
                    "media_assets",
                    "outbox",
                    "custom_items",
                    "pending_reminder_cleanup",
                    "pending_replica_cleanup",
                ),
            ),
        )
        assertFalse(tables.contains("calendar_events"))

        val recordColumns = buildSet {
            sqlite.query("PRAGMA table_info(records)").use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) add(cursor.getString(nameIndex))
            }
        }
        assertFalse(recordColumns.contains("createdByUserId"))
        assertFalse(recordColumns.contains("createdByDeviceId"))
        assertTrue(recordColumns.contains("createdByMembershipId"))
        val babyColumns = buildSet {
            sqlite.query("PRAGMA table_info(babies)").use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) add(cursor.getString(nameIndex))
            }
        }
        assertTrue(babyColumns.contains("familyAuthority"))
    }

    @Test
    fun freshDatabaseRejectsInvalidMediaOwnershipAtSqlBoundary() {
        val db = openDatabase("media-owner-check")
        val sqlite = db.openHelper.writableDatabase

        val triggers = buildSet {
            sqlite.query(
                "SELECT name FROM sqlite_master WHERE type = 'trigger' AND tbl_name = 'media_assets'",
            ).use { cursor ->
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
        assertTrue(triggers.contains("media_assets_owner_insert"))
        assertTrue(triggers.contains("media_assets_owner_update"))

        val ownerless = runCatching {
            sqlite.execSQL(
                """
                INSERT INTO media_assets (clientUuid, kind, localUri, createdAt)
                VALUES ('invalid-ownerless', 'log', 'media/a.jpg', 1)
                """.trimIndent(),
            )
        }.exceptionOrNull()
        val doubleOwned = runCatching {
            sqlite.execSQL(
                """
                INSERT INTO media_assets
                    (recordId, carePlanId, clientUuid, kind, localUri, createdAt)
                VALUES (1, 2, 'invalid-double-owner', 'log', 'media/b.jpg', 1)
                """.trimIndent(),
            )
        }.exceptionOrNull()
        sqlite.execSQL(
            """
            INSERT INTO media_assets
                (recordId, clientUuid, kind, localUri, createdAt)
            VALUES (1, 'valid-record-owner', 'log', 'media/c.jpg', 1)
            """.trimIndent(),
        )
        val invalidUpdate = runCatching {
            sqlite.execSQL(
                """
                UPDATE media_assets SET carePlanId = 2
                WHERE clientUuid = 'valid-record-owner'
                """.trimIndent(),
            )
        }.exceptionOrNull()

        assertNotNull(ownerless)
        assertNotNull(doubleOwned)
        assertNotNull(invalidUpdate)
    }

    @Test
    fun sameVersionReopenPreservesData() = runBlocking {
        val name = uniqueName("same-version-reopen")
        database = buildLeziDatabase(context, name)
        database!!.babyDao().upsert(
            BabyEntity(
                familyId = 1,
                nickname = "年年",
                birthdayEpochDay = 20_000,
                themeColorArgb = 0,
                clientUuid = "baby-fresh-reopen",
                updatedAt = 100,
            ),
        )
        database!!.close()

        database = buildLeziDatabase(context, name)

        assertEquals(
            "年年",
            database!!.babyDao().getByClientUuid("baby-fresh-reopen")?.nickname,
        )
    }

    @Test
    fun nonCurrentSchemaFailsWithoutMutation() {
        val name = uniqueName("non-current-rejected")
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { sqlite ->
            sqlite.version = 22
        }
        database = buildLeziDatabase(context, name)

        val failure = runCatching {
            database!!.openHelper.writableDatabase
        }.exceptionOrNull()

        assertNotNull(failure)
        assertTrue(failure!!.message.orEmpty().contains("migration from 22 to 23"))
        database!!.close()
        database = null
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { sqlite ->
            assertEquals(22, sqlite.version)
        }
    }

    private fun openDatabase(label: String): LeziDatabase {
        val name = uniqueName(label)
        return buildLeziDatabase(context, name).also { database = it }
    }

    private fun uniqueName(label: String): String =
        "lezi-$label-${System.nanoTime()}.db".also(databaseNames::add)
}
