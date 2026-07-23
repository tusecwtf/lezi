package com.lezi.babylog.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        LeziDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate1To4_preservesV1DataAndCreatesLaterTables() {
        helper.createDatabase(TEST_DATABASE, 1).apply {
            execSQL(
                """
                INSERT INTO local_users (id, displayName, deviceId, createdAt)
                VALUES (7, '迁移用户', 'device-v1', 1234)
                """.trimIndent(),
            )
            close()
        }

        helper.runMigrationsAndValidate(
            TEST_DATABASE,
            4,
            true,
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
        ).apply {
            query("SELECT displayName, deviceId, createdAt FROM local_users WHERE id = 7").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("迁移用户", cursor.getString(0))
                assertEquals("device-v1", cursor.getString(1))
                assertEquals(1234L, cursor.getLong(2))
            }

            listOf("outbox", "custom_items", "calendar_events").forEach { table ->
                query(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?",
                    arrayOf(table),
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(1L, cursor.getLong(0))
                }
            }
            close()
        }
    }

    private companion object {
        const val TEST_DATABASE = "lezi-migration-test"
    }
}
