package com.lezi.babylog.core.database

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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

        assertEquals(21, sqlite.version)
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
                    "calendar_events",
                    "pending_reminder_cleanup",
                    "pending_replica_cleanup",
                ),
            ),
        )
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
    fun legacySchemaFailsWithoutMigrationOrDestructiveFallback() {
        val name = uniqueName("legacy-rejected")
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { sqlite ->
            sqlite.version = 20
        }
        database = buildLeziDatabase(context, name)

        val failure = runCatching {
            database!!.openHelper.writableDatabase
        }.exceptionOrNull()

        assertNotNull(failure)
        assertTrue(failure!!.message.orEmpty().contains("migration from 20 to 21"))
        database!!.close()
        database = null
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { sqlite ->
            assertEquals(20, sqlite.version)
        }
    }

    private fun openDatabase(label: String): LeziDatabase {
        val name = uniqueName(label)
        return buildLeziDatabase(context, name).also { database = it }
    }

    private fun uniqueName(label: String): String =
        "lezi-$label-${System.nanoTime()}.db".also(databaseNames::add)
}
