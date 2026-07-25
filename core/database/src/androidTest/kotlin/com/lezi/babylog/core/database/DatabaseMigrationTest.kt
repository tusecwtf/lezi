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
    fun migrate1To6_preservesV1DataAndCreatesLaterTables() {
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
            6,
            true,
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
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
            listOf(
                "index_media_assets_recordId",
                "index_media_assets_babyId_kind_deletedAt",
            ).forEach { index ->
                query(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = ?",
                    arrayOf(index),
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(1L, cursor.getLong(0))
                }
            }
            close()
        }
    }

    @Test
    fun migrate6To7_normalizesExclusiveMediaAssociations() {
        helper.createDatabase(MEDIA_DATABASE, 6).apply {
            execSQL(
                """
                INSERT INTO media_assets (
                    id, recordId, clientUuid, kind, babyId, localUri,
                    byteSize, createdAt, updatedAt, syncDirty
                ) VALUES (
                    1, 11, 'legacy-log', 'log', 22, 'photos/log.jpg',
                    12, 100, 100, 0
                )
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO media_assets (
                    id, recordId, clientUuid, kind, babyId, localUri,
                    byteSize, createdAt, updatedAt, syncDirty
                ) VALUES (
                    2, 33, 'legacy-avatar', 'avatar', 44, 'avatars/baby.jpg',
                    12, 100, 100, 0
                )
                """.trimIndent(),
            )
            close()
        }

        helper.runMigrationsAndValidate(
            MEDIA_DATABASE,
            7,
            true,
            MIGRATION_6_7,
        ).apply {
            query(
                "SELECT recordId, babyId FROM media_assets ORDER BY id",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(11L, cursor.getLong(0))
                assertTrue(cursor.isNull(1))
                assertTrue(cursor.moveToNext())
                assertTrue(cursor.isNull(0))
                assertEquals(44L, cursor.getLong(1))
            }
            close()
        }
    }

    private companion object {
        const val TEST_DATABASE = "lezi-migration-test"
        const val MEDIA_DATABASE = "lezi-media-migration-test"
    }
}
