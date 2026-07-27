package com.lezi.babylog.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun migrate8To9_addsCustomItemCreatorMembershipWithoutDroppingRows() {
        helper.createDatabase(CUSTOM_OWNERSHIP_DATABASE, 8).apply {
            execSQL(
                """
                INSERT INTO custom_items (
                    id, clientUuid, familyId, name, iconSlot, sortOrder, updatedAt, deletedAt
                ) VALUES (
                    3, 'legacy-custom-uuid', 1, '抚触', 2, 0, 1000, NULL
                )
                """.trimIndent(),
            )
            close()
        }

        helper.runMigrationsAndValidate(
            CUSTOM_OWNERSHIP_DATABASE,
            9,
            true,
            MIGRATION_8_9,
        ).apply {
            query(
                """
                SELECT id, clientUuid, name, iconSlot, createdByMembershipId
                FROM custom_items WHERE id = 3
                """.trimIndent(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(3L, cursor.getLong(0))
                assertEquals("legacy-custom-uuid", cursor.getString(1))
                assertEquals("抚触", cursor.getString(2))
                assertEquals(2, cursor.getInt(3))
                assertEquals("", cursor.getString(4))
            }
            close()
        }
    }

    @Test
    fun migrate9To10_createsCarePlansTable() {
        helper.createDatabase(CARE_PLAN_DATABASE, 9).apply {
            close()
        }
        helper.runMigrationsAndValidate(
            CARE_PLAN_DATABASE,
            10,
            true,
            MIGRATION_9_10,
        ).apply {
            query(
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'care_plans'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1L, cursor.getLong(0))
            }
            close()
        }
    }

    @Test
    fun migrate10To11_addsCarePlanIdOnMediaAssets() {
        helper.createDatabase(PLAN_PHOTO_DATABASE, 10).apply {
            close()
        }
        helper.runMigrationsAndValidate(
            PLAN_PHOTO_DATABASE,
            11,
            true,
            MIGRATION_10_11,
        ).apply {
            query("PRAGMA table_info(media_assets)").use { cursor ->
                val names = mutableListOf<String>()
                val nameIdx = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) {
                    names += cursor.getString(nameIdx)
                }
                assertTrue(names.contains("carePlanId"))
            }
            close()
        }
    }

    @Test
    fun migrate11To12_addsCustomItemSyncDirtyWithoutDroppingRows() {
        helper.createDatabase(CUSTOM_SYNC_DATABASE, 11).apply {
            execSQL(
                """
                INSERT INTO custom_items (
                    id, clientUuid, familyId, name, iconSlot, sortOrder,
                    updatedAt, deletedAt, createdByMembershipId
                ) VALUES (
                    5, 'sync-custom-uuid', 1, '抚触', 2, 0, 1000, NULL, 'm-creator'
                )
                """.trimIndent(),
            )
            close()
        }
        helper.runMigrationsAndValidate(
            CUSTOM_SYNC_DATABASE,
            12,
            true,
            MIGRATION_11_12,
        ).apply {
            query(
                """
                SELECT id, clientUuid, name, createdByMembershipId, syncDirty
                FROM custom_items WHERE id = 5
                """.trimIndent(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(5L, cursor.getLong(0))
                assertEquals("sync-custom-uuid", cursor.getString(1))
                assertEquals("抚触", cursor.getString(2))
                assertEquals("m-creator", cursor.getString(3))
                // Existing shared defs need a push after upgrade.
                assertEquals(1, cursor.getInt(4))
            }
            close()
        }
    }

    @Test
    fun migrate12To13_addsSourceRecordClientUuidWithoutDroppingPlans() {
        helper.createDatabase(SOURCE_RECORD_DATABASE, 12).apply {
            execSQL(
                """
                INSERT INTO care_plans (
                    id, clientUuid, babyId, type, customItemId, scheduledAt, scheduledZoneId,
                    note, payloadJson, schemaVersion, status, createdByMembershipId,
                    fulfilledRecordClientUuid, fulfilledAt, updatedAt, deletedAt
                ) VALUES (
                    3, 'plan-src-uuid', 1, 'pee', NULL, 2000, 'Asia/Shanghai',
                    '迁移', '{}', 1, 'pending', 'm-1',
                    NULL, NULL, 1500, NULL
                )
                """.trimIndent(),
            )
            close()
        }
        helper.runMigrationsAndValidate(
            SOURCE_RECORD_DATABASE,
            13,
            true,
            MIGRATION_12_13,
        ).apply {
            query(
                """
                SELECT id, clientUuid, sourceRecordClientUuid, status
                FROM care_plans WHERE id = 3
                """.trimIndent(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(3L, cursor.getLong(0))
                assertEquals("plan-src-uuid", cursor.getString(1))
                // Additive column defaults to null for pre-conversion plans.
                assertTrue(cursor.isNull(2))
                assertEquals("pending", cursor.getString(3))
            }
            close()
        }
    }

    @Test
    fun migrate13To14_addsCarePlanSyncDirtyAndMarksExistingDirty() {
        helper.createDatabase(CARE_PLAN_SYNC_DATABASE, 13).apply {
            execSQL(
                """
                INSERT INTO care_plans (
                    id, clientUuid, babyId, type, customItemId, scheduledAt, scheduledZoneId,
                    note, payloadJson, schemaVersion, status, createdByMembershipId,
                    fulfilledRecordClientUuid, fulfilledAt, sourceRecordClientUuid,
                    updatedAt, deletedAt
                ) VALUES (
                    4, 'plan-sync-uuid', 1, 'formula', NULL, 3000, 'Asia/Shanghai',
                    '同步迁移', '{}', 1, 'pending', 'm-2',
                    NULL, NULL, NULL,
                    1600, NULL
                )
                """.trimIndent(),
            )
            close()
        }
        helper.runMigrationsAndValidate(
            CARE_PLAN_SYNC_DATABASE,
            14,
            true,
            MIGRATION_13_14,
        ).apply {
            query(
                """
                SELECT id, clientUuid, syncDirty, status
                FROM care_plans WHERE id = 4
                """.trimIndent(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(4L, cursor.getLong(0))
                assertEquals("plan-sync-uuid", cursor.getString(1))
                // Existing plans need a first family publish after upgrade.
                assertEquals(1, cursor.getInt(2))
                assertEquals("pending", cursor.getString(3))
            }
            query(
                """
                SELECT COUNT(*) FROM sqlite_master
                WHERE type = 'index' AND name = 'index_care_plans_syncDirty'
                """.trimIndent(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1L, cursor.getLong(0))
            }
            close()
        }
    }

    @Test
    fun migrate14To15_createsFulfillmentCandidatesTable() {
        helper.createDatabase(FULFILLMENT_DATABASE, 14).apply {
            // v14 has care_plans with syncDirty; no fulfillment_candidates yet.
            close()
        }
        helper.runMigrationsAndValidate(
            FULFILLMENT_DATABASE,
            15,
            true,
            MIGRATION_14_15,
        ).apply {
            query(
                """
                SELECT COUNT(*) FROM sqlite_master
                WHERE type = 'table' AND name = 'fulfillment_candidates'
                """.trimIndent(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1L, cursor.getLong(0))
            }
            query(
                """
                SELECT COUNT(*) FROM sqlite_master
                WHERE type = 'index' AND name = 'index_fulfillment_candidates_clientUuid'
                """.trimIndent(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1L, cursor.getLong(0))
            }
            // Empty after upgrade; new fulfills write rows going forward.
            query("SELECT COUNT(*) FROM fulfillment_candidates").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0L, cursor.getLong(0))
            }
            close()
        }
    }

    @Test
    fun migrate15To16_addsAdoptionStatusForConflictResolution() {
        helper.createDatabase(ADOPTION_DATABASE, 15).apply {
            execSQL(
                """
                INSERT INTO fulfillment_candidates (
                    clientUuid, carePlanClientUuid, recordClientUuid,
                    actualTimestamp, confirmedAt, submitterMembershipId, submitterRole,
                    updatedAt, deletedAt, syncDirty
                ) VALUES (
                    'cand-1', 'plan-1', 'rec-1',
                    100, 200, 'm1', 'member',
                    200, NULL, 0
                )
                """.trimIndent(),
            )
            close()
        }
        helper.runMigrationsAndValidate(
            ADOPTION_DATABASE,
            16,
            true,
            MIGRATION_15_16,
        ).apply {
            query(
                """
                SELECT adoptionStatus FROM fulfillment_candidates WHERE clientUuid = 'cand-1'
                """.trimIndent(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("", cursor.getString(0))
            }
            query(
                """
                SELECT COUNT(*) FROM sqlite_master
                WHERE type = 'index'
                  AND name = 'index_fulfillment_candidates_adoptionStatus'
                """.trimIndent(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1L, cursor.getLong(0))
            }
            close()
        }
    }

    @Test
    fun migrate16To17_addsConvertedRecordClientUuidForConflictAudit() {
        helper.createDatabase(CONVERT_POINTER_DATABASE, 16).apply {
            execSQL(
                """
                INSERT INTO fulfillment_candidates (
                    clientUuid, carePlanClientUuid, recordClientUuid,
                    actualTimestamp, confirmedAt, submitterMembershipId, submitterRole,
                    adoptionStatus, updatedAt, deletedAt, syncDirty
                ) VALUES (
                    'cand-1', 'plan-1', 'rec-1',
                    100, 200, 'm1', 'member',
                    'conflict_not_adopted', 200, NULL, 0
                )
                """.trimIndent(),
            )
            close()
        }
        helper.runMigrationsAndValidate(
            CONVERT_POINTER_DATABASE,
            17,
            true,
            MIGRATION_16_17,
        ).apply {
            query(
                """
                SELECT convertedRecordClientUuid, adoptionStatus
                FROM fulfillment_candidates WHERE clientUuid = 'cand-1'
                """.trimIndent(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("", cursor.getString(0))
                assertEquals("conflict_not_adopted", cursor.getString(1))
            }
            close()
        }
    }

    @Test
    fun migrate17To18_addsRecordMembershipAuthorWithoutChangingLegacyRows() {
        helper.createDatabase(RECORD_MEMBERSHIP_AUTHOR_DATABASE, 17).apply {
            execSQL(
                """
                INSERT INTO records (
                    id, clientUuid, babyId, type, timestamp, endTimestamp, note,
                    createdByUserId, createdByDeviceId, payloadJson, schemaVersion,
                    updatedAt, deletedAt, syncDirty
                ) VALUES (
                    71, 'record-legacy-author', 9, 'formula', 1000, NULL, '保留旧记录',
                    3, 'legacy-device', '{"amount_ml":120}', 1,
                    1100, NULL, 0
                )
                """.trimIndent(),
            )
            close()
        }

        helper.runMigrationsAndValidate(
            RECORD_MEMBERSHIP_AUTHOR_DATABASE,
            18,
            true,
            MIGRATION_17_18,
        ).apply {
            query(
                """
                SELECT id, clientUuid, babyId, type, timestamp, note,
                       createdByUserId, createdByMembershipId, createdByDeviceId,
                       payloadJson, schemaVersion, updatedAt, deletedAt, syncDirty
                FROM records WHERE id = 71
                """.trimIndent(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(71L, cursor.getLong(0))
                assertEquals("record-legacy-author", cursor.getString(1))
                assertEquals(9L, cursor.getLong(2))
                assertEquals("formula", cursor.getString(3))
                assertEquals(1000L, cursor.getLong(4))
                assertEquals("保留旧记录", cursor.getString(5))
                assertEquals(3L, cursor.getLong(6))
                assertEquals("", cursor.getString(7))
                assertEquals("legacy-device", cursor.getString(8))
                assertEquals("""{"amount_ml":120}""", cursor.getString(9))
                assertEquals(1, cursor.getInt(10))
                assertEquals(1100L, cursor.getLong(11))
                assertTrue(cursor.isNull(12))
                assertEquals(0, cursor.getInt(13))
                assertTrue(!cursor.moveToNext())
            }
            close()
        }
    }

    @Test
    fun migrate18To19_addsCarePlanIdsWithoutLosingPendingReminderCleanup() {
        helper.createDatabase(PENDING_REMINDER_V19_DATABASE, 18).apply {
            execSQL(
                """
                INSERT INTO pending_reminder_cleanup (
                    operation, calendarEventIds, familyServerRetained
                ) VALUES ('records_clear', '9,3,9', 1)
                """.trimIndent(),
            )
            close()
        }

        helper.runMigrationsAndValidate(
            PENDING_REMINDER_V19_DATABASE,
            19,
            true,
            MIGRATION_18_19,
        ).apply {
            query(
                """
                SELECT calendarEventIds, carePlanIds, familyServerRetained
                FROM pending_reminder_cleanup
                WHERE operation = 'records_clear'
                """.trimIndent(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("9,3,9", cursor.getString(0))
                assertEquals("", cursor.getString(1))
                assertEquals(1, cursor.getInt(2))
            }
            close()
        }
    }

    @Test
    fun migrate19To20_createsPendingReplicaCleanupWithoutChangingRoom19Data() {
        helper.createDatabase(PENDING_REPLICA_V20_DATABASE, 19).apply {
            execSQL(
                """
                INSERT INTO pending_reminder_cleanup (
                    operation, calendarEventIds, carePlanIds, familyServerRetained
                ) VALUES ('records_clear', '9,3', '8,4', 1)
                """.trimIndent(),
            )
            close()
        }

        helper.runMigrationsAndValidate(
            PENDING_REPLICA_V20_DATABASE,
            20,
            true,
            MIGRATION_19_20,
        ).apply {
            query(
                """
                SELECT calendarEventIds, carePlanIds, familyServerRetained
                FROM pending_reminder_cleanup
                WHERE operation = 'records_clear'
                """.trimIndent(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("9,3", cursor.getString(0))
                assertEquals("8,4", cursor.getString(1))
                assertEquals(1, cursor.getInt(2))
            }
            query(
                """
                SELECT COUNT(*) FROM sqlite_master
                WHERE type = 'table' AND name = 'pending_replica_cleanup'
                """.trimIndent(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1L, cursor.getLong(0))
            }
            close()
        }
    }

    @Test
    fun migrate20To21_addsSystemCalendarIdsWithoutLosingPendingCleanup() {
        helper.createDatabase(PENDING_CALENDAR_V21_DATABASE, 20).apply {
            execSQL(
                """
                INSERT INTO pending_reminder_cleanup (
                    operation, calendarEventIds, carePlanIds, familyServerRetained
                ) VALUES ('records_clear', '9,3', '8,4', 1)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO care_plans (
                    id, clientUuid, babyId, type, customItemId, scheduledAt,
                    scheduledZoneId, note, payloadJson, schemaVersion, status,
                    createdByMembershipId, fulfilledRecordClientUuid, fulfilledAt,
                    sourceRecordClientUuid, updatedAt, deletedAt, syncDirty
                ) VALUES (
                    23, 'plan-v20', 1, 'bath', NULL, 2000,
                    'Asia/Shanghai', NULL, '{}', 1, 'pending',
                    'member-1', NULL, NULL,
                    NULL, 1500, NULL, 0
                )
                """.trimIndent(),
            )
            close()
        }

        helper.runMigrationsAndValidate(
            PENDING_CALENDAR_V21_DATABASE,
            21,
            true,
            MIGRATION_20_21,
        ).apply {
            query(
                """
                SELECT calendarEventIds, carePlanIds, systemCalendarProjectionsJson,
                       settingsSnapshotCaptured, currentBabyId, nextFeedAt, nextFeedEpoch,
                       familyServerRetained
                FROM pending_reminder_cleanup
                WHERE operation = 'records_clear'
                """.trimIndent(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("9,3", cursor.getString(0))
                assertEquals("8,4", cursor.getString(1))
                assertEquals("{}", cursor.getString(2))
                assertEquals(0, cursor.getInt(3))
                assertTrue(cursor.isNull(4))
                assertTrue(cursor.isNull(5))
                assertTrue(cursor.isNull(6))
                assertEquals(1, cursor.getInt(7))
            }
            query(
                """
                SELECT systemCalendarProjectionEnabled, systemCalendarEventId,
                       systemCalendarReminderReady, systemCalendarProjectionPending,
                       legacyCarePlanReminderPending
                FROM care_plans WHERE id = 23
                """.trimIndent(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
                assertTrue(cursor.isNull(1))
                assertEquals(0, cursor.getInt(2))
                assertEquals(0, cursor.getInt(3))
                assertEquals(1, cursor.getInt(4))
            }
            close()
        }
    }

    @Test
    fun migrate8To21_preservesLegacyPendingReminderCleanupRow() {
        helper.createDatabase(PENDING_REMINDER_DATABASE, 8).apply {
            execSQL(
                """
                INSERT INTO pending_reminder_cleanup (
                    operation, calendarEventIds, familyServerRetained
                ) VALUES ('records_clear', '9,3,9', 1)
                """.trimIndent(),
            )
            close()
        }

        helper.runMigrationsAndValidate(
            PENDING_REMINDER_DATABASE,
            21,
            true,
            MIGRATION_8_9,
            MIGRATION_9_10,
            MIGRATION_10_11,
            MIGRATION_11_12,
            MIGRATION_12_13,
            MIGRATION_13_14,
            MIGRATION_14_15,
            MIGRATION_15_16,
            MIGRATION_16_17,
            MIGRATION_17_18,
            MIGRATION_18_19,
            MIGRATION_19_20,
            MIGRATION_20_21,
        ).apply {
            query(
                """
                SELECT calendarEventIds, carePlanIds, familyServerRetained
                FROM pending_reminder_cleanup
                WHERE operation = 'records_clear'
                """.trimIndent(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("9,3,9", cursor.getString(0))
                assertEquals("", cursor.getString(1))
                assertEquals(1, cursor.getInt(2))
            }
            close()
        }
    }

    /**
     * Full upgrade chain from the shipped 0.2.4 Room schema (v7) through every
     * published migration to the current feature head (v21).
     *
     * Seeds a realistic pre-feature DB: facts with 1–3 log photos, free-title
     * calendar events, and a custom item. Asserts row preservation and that
     * feature tables/columns exist — does not invent missing migrations.
     */
    @Test
    fun migrate7To21_preservesShipped024BaselineThroughCurrentHead() {
        helper.createDatabase(SHIPPED_024_DATABASE, 7).apply {
            execSQL(
                """
                INSERT INTO local_users (id, displayName, deviceId, createdAt)
                VALUES (1, '升级用户', 'device-0.2.4', 1000)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO families (id, ownerUserId, createdAt)
                VALUES (1, 1, 1000)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO memberships (familyId, userId, role, status, joinedAt)
                VALUES (1, 1, 'owner', 'active', 1000)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO babies (
                    id, familyId, nickname, sex, birthdayEpochDay, birthWeightGrams,
                    dueDateEpochDay, themeColorArgb, sortOrder, clientUuid, updatedAt,
                    deletedAt, syncDirty, avatarMediaUuid, avatarPath
                ) VALUES (
                    10, 1, '乐乐', 'unknown', 19000, 3200,
                    NULL, -14575885, 0, 'baby-uuid-024', 2000,
                    NULL, 0, NULL, NULL
                )
                """.trimIndent(),
            )
            // Fact record with one log photo (typical pee).
            execSQL(
                """
                INSERT INTO records (
                    id, clientUuid, babyId, type, timestamp, endTimestamp, note,
                    createdByUserId, createdByDeviceId, payloadJson, schemaVersion,
                    updatedAt, deletedAt, syncDirty
                ) VALUES (
                    100, 'rec-pee-1', 10, 'pee', 3000, NULL, '升级保留尿尿',
                    1, 'device-0.2.4', '{}', 1,
                    3000, NULL, 0
                )
                """.trimIndent(),
            )
            // Memo-style fact with three log photos (upper bound pre-feature).
            execSQL(
                """
                INSERT INTO records (
                    id, clientUuid, babyId, type, timestamp, endTimestamp, note,
                    createdByUserId, createdByDeviceId, payloadJson, schemaVersion,
                    updatedAt, deletedAt, syncDirty
                ) VALUES (
                    101, 'rec-memo-3photos', 10, 'memo', 3100, NULL, '三张照片备注',
                    1, 'device-0.2.4', '{"text":"旧备注"}', 1,
                    3100, NULL, 0
                )
                """.trimIndent(),
            )
            // Soft-deleted fact must survive the chain (tombstone).
            execSQL(
                """
                INSERT INTO records (
                    id, clientUuid, babyId, type, timestamp, endTimestamp, note,
                    createdByUserId, createdByDeviceId, payloadJson, schemaVersion,
                    updatedAt, deletedAt, syncDirty
                ) VALUES (
                    102, 'rec-deleted', 10, 'formula', 2900, NULL, '已删配方',
                    1, 'device-0.2.4', '{"ml":120}', 1,
                    2950, 2950, 0
                )
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO media_assets (
                    id, recordId, clientUuid, kind, babyId, localUri, remoteUri,
                    mime, width, height, byteSize, createdAt, updatedAt, deletedAt, syncDirty
                ) VALUES
                (1, 100, 'media-pee-1', 'log', NULL, 'photos/pee.jpg', NULL,
                 'image/jpeg', 100, 100, 12, 3000, 3000, NULL, 0),
                (2, 101, 'media-memo-1', 'log', NULL, 'photos/memo1.jpg', NULL,
                 'image/jpeg', 200, 200, 20, 3100, 3100, NULL, 0),
                (3, 101, 'media-memo-2', 'log', NULL, 'photos/memo2.jpg', NULL,
                 'image/jpeg', 200, 200, 21, 3100, 3100, NULL, 0),
                (4, 101, 'media-memo-3', 'log', NULL, 'photos/memo3.jpg', NULL,
                 'image/jpeg', 200, 200, 22, 3100, 3100, NULL, 0)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO custom_items (
                    id, clientUuid, familyId, name, iconSlot, sortOrder, updatedAt, deletedAt
                ) VALUES (
                    5, 'custom-tui-na', 1, '抚触', 2, 0, 2500, NULL
                )
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO calendar_events (
                    id, clientUuid, babyId, title, note, eventAt, remindAt, updatedAt, deletedAt
                ) VALUES (
                    7, 'cal-free-title', 10, '医院复查', '带病历本', 50000, 49000, 4000, NULL
                )
                """.trimIndent(),
            )
            close()
        }

        helper.runMigrationsAndValidate(
            SHIPPED_024_DATABASE,
            21,
            true,
            MIGRATION_7_8,
            MIGRATION_8_9,
            MIGRATION_9_10,
            MIGRATION_10_11,
            MIGRATION_11_12,
            MIGRATION_12_13,
            MIGRATION_13_14,
            MIGRATION_14_15,
            MIGRATION_15_16,
            MIGRATION_16_17,
            MIGRATION_17_18,
            MIGRATION_18_19,
            MIGRATION_19_20,
            MIGRATION_20_21,
        ).apply {
            // Local identity preserved.
            query(
                "SELECT displayName, deviceId FROM local_users WHERE id = 1",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("升级用户", cursor.getString(0))
                assertEquals("device-0.2.4", cursor.getString(1))
            }
            query(
                "SELECT nickname, clientUuid, syncDirty FROM babies WHERE id = 10",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("乐乐", cursor.getString(0))
                assertEquals("baby-uuid-024", cursor.getString(1))
                assertEquals(0, cursor.getInt(2))
            }

            // All three records (including soft-deleted) survive.
            query(
                """
                SELECT clientUuid, type, note, deletedAt FROM records
                ORDER BY id
                """.trimIndent(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("rec-pee-1", cursor.getString(0))
                assertEquals("pee", cursor.getString(1))
                assertEquals("升级保留尿尿", cursor.getString(2))
                assertTrue(cursor.isNull(3))

                assertTrue(cursor.moveToNext())
                assertEquals("rec-memo-3photos", cursor.getString(0))
                assertEquals("memo", cursor.getString(1))
                assertEquals("三张照片备注", cursor.getString(2))

                assertTrue(cursor.moveToNext())
                assertEquals("rec-deleted", cursor.getString(0))
                assertEquals("formula", cursor.getString(1))
                assertEquals(2950L, cursor.getLong(3))
                assertTrue(!cursor.moveToNext())
            }

            // 1 + 3 log photos preserved; carePlanId additive null on legacy log rows.
            query(
                """
                SELECT clientUuid, recordId, carePlanId, localUri, kind
                FROM media_assets ORDER BY id
                """.trimIndent(),
            ).use { cursor ->
                val rows = mutableListOf<List<Any?>>()
                while (cursor.moveToNext()) {
                    rows += listOf(
                        cursor.getString(0),
                        cursor.getLong(1),
                        if (cursor.isNull(2)) null else cursor.getLong(2),
                        cursor.getString(3),
                        cursor.getString(4),
                    )
                }
                assertEquals(4, rows.size)
                assertEquals(listOf("media-pee-1", 100L, null, "photos/pee.jpg", "log"), rows[0])
                assertEquals(listOf("media-memo-1", 101L, null, "photos/memo1.jpg", "log"), rows[1])
                assertEquals(listOf("media-memo-2", 101L, null, "photos/memo2.jpg", "log"), rows[2])
                assertEquals(listOf("media-memo-3", 101L, null, "photos/memo3.jpg", "log"), rows[3])
            }
            query(
                "SELECT COUNT(*) FROM media_assets WHERE recordId = 101 AND deletedAt IS NULL",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(3L, cursor.getLong(0))
            }

            // Custom item identity/name preserved; ownership + sync dirty filled by later migs.
            query(
                """
                SELECT clientUuid, name, iconSlot, createdByMembershipId, syncDirty
                FROM custom_items WHERE id = 5
                """.trimIndent(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("custom-tui-na", cursor.getString(0))
                assertEquals("抚触", cursor.getString(1))
                assertEquals(2, cursor.getInt(2))
                assertEquals("", cursor.getString(3))
                // Pre-family rows marked dirty for first family publish after upgrade.
                assertEquals(1, cursor.getInt(4))
            }

            // Free-title calendar event preserved for explicit later conversion.
            query(
                """
                SELECT clientUuid, title, note, eventAt, remindAt
                FROM calendar_events WHERE id = 7
                """.trimIndent(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("cal-free-title", cursor.getString(0))
                assertEquals("医院复查", cursor.getString(1))
                assertEquals("带病历本", cursor.getString(2))
                assertEquals(50000L, cursor.getLong(3))
                assertEquals(49000L, cursor.getLong(4))
            }

            // Feature tables introduced by the chain are present and empty on upgrade.
            listOf(
                "pending_reminder_cleanup",
                "pending_replica_cleanup",
                "care_plans",
                "fulfillment_candidates",
            ).forEach { table ->
                query(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?",
                    arrayOf(table),
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(1L, cursor.getLong(0))
                }
            }
            query("SELECT COUNT(*) FROM care_plans").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0L, cursor.getLong(0))
            }
            query("SELECT COUNT(*) FROM fulfillment_candidates").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0L, cursor.getLong(0))
            }

            // Spot-check columns added along the chain exist on empty feature tables.
            query("PRAGMA table_info(care_plans)").use { cursor ->
                val names = mutableListOf<String>()
                val nameIdx = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) {
                    names += cursor.getString(nameIdx)
                }
                assertTrue(names.contains("sourceRecordClientUuid"))
                assertTrue(names.contains("syncDirty"))
                assertTrue(names.contains("createdByMembershipId"))
            }
            query("PRAGMA table_info(fulfillment_candidates)").use { cursor ->
                val names = mutableListOf<String>()
                val nameIdx = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) {
                    names += cursor.getString(nameIdx)
                }
                assertTrue(names.contains("adoptionStatus"))
                assertTrue(names.contains("convertedRecordClientUuid"))
            }
            close()
        }
    }

    private companion object {
        const val TEST_DATABASE = "lezi-migration-test"
        const val MEDIA_DATABASE = "lezi-media-migration-test"
        const val CUSTOM_OWNERSHIP_DATABASE = "lezi-custom-ownership-migration-test"
        const val CARE_PLAN_DATABASE = "lezi-care-plan-migration-test"
        const val PLAN_PHOTO_DATABASE = "lezi-plan-photo-migration-test"
        const val CUSTOM_SYNC_DATABASE = "lezi-custom-sync-migration-test"
        const val SOURCE_RECORD_DATABASE = "lezi-source-record-migration-test"
        const val CARE_PLAN_SYNC_DATABASE = "lezi-care-plan-sync-migration-test"
        const val FULFILLMENT_DATABASE = "lezi-fulfillment-candidates-migration-test"
        const val ADOPTION_DATABASE = "lezi-fulfillment-adoption-migration-test"
        const val CONVERT_POINTER_DATABASE = "lezi-fulfillment-convert-pointer-migration-test"
        const val RECORD_MEMBERSHIP_AUTHOR_DATABASE =
            "lezi-record-membership-author-migration-test"
        const val PENDING_REMINDER_DATABASE = "lezi-pending-reminder-migration-test"
        const val PENDING_REMINDER_V19_DATABASE = "lezi-pending-reminder-v19-migration-test"
        const val PENDING_REPLICA_V20_DATABASE = "lezi-pending-replica-v20-migration-test"
        const val PENDING_CALENDAR_V21_DATABASE =
            "lezi-pending-calendar-v21-migration-test"
        /** Shipped product 0.2.4 Room head (see dist/lezi-0.2.4-release.apk + schema 7.json). */
        const val SHIPPED_024_DATABASE = "lezi-shipped-0.2.4-full-chain-migration-test"
    }
}
