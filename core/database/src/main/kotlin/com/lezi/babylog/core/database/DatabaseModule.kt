package com.lezi.babylog.core.database

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

internal val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `outbox` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `familyId` TEXT NOT NULL,
                `entityType` TEXT NOT NULL,
                `clientUuid` TEXT NOT NULL,
                `payloadJson` TEXT NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                `deletedAt` INTEGER,
                `createdAt` INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `custom_items` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `clientUuid` TEXT NOT NULL,
                `familyId` INTEGER NOT NULL,
                `name` TEXT NOT NULL,
                `iconSlot` INTEGER NOT NULL,
                `sortOrder` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                `deletedAt` INTEGER
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `calendar_events` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `clientUuid` TEXT NOT NULL,
                `babyId` INTEGER NOT NULL,
                `title` TEXT NOT NULL,
                `note` TEXT,
                `eventAt` INTEGER NOT NULL,
                `remindAt` INTEGER,
                `updatedAt` INTEGER NOT NULL,
                `deletedAt` INTEGER
            )
            """.trimIndent(),
        )
    }
}

internal val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE babies ADD COLUMN birthWeightGrams INTEGER")
    }
}

internal val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE babies ADD COLUMN avatarPath TEXT")
    }
}

internal val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS media_assets_v5 (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                recordId INTEGER,
                clientUuid TEXT NOT NULL DEFAULT '',
                kind TEXT NOT NULL DEFAULT 'log',
                babyId INTEGER,
                localUri TEXT NOT NULL,
                remoteUri TEXT,
                mime TEXT,
                width INTEGER,
                height INTEGER,
                byteSize INTEGER NOT NULL DEFAULT 0,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL DEFAULT 0,
                deletedAt INTEGER
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO media_assets_v5 (
                id, recordId, clientUuid, kind, babyId, localUri, remoteUri,
                mime, width, height, byteSize, createdAt, updatedAt, deletedAt
            )
            SELECT
                id, recordId, 'legacy-' || id, 'log', NULL, localUri, remoteUri,
                mime, width, height, 0, createdAt, createdAt, NULL
            FROM media_assets
            """.trimIndent(),
        )
        db.execSQL("DROP TABLE media_assets")
        db.execSQL("ALTER TABLE media_assets_v5 RENAME TO media_assets")
        db.execSQL(
            """
            DELETE FROM outbox
            WHERE id NOT IN (
                SELECT MAX(id)
                FROM outbox
                GROUP BY familyId, entityType, clientUuid
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_media_assets_clientUuid " +
                "ON media_assets(clientUuid)",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_outbox_familyId_entityType_clientUuid " +
                "ON outbox(familyId, entityType, clientUuid)",
        )
    }
}

internal val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE records ADD COLUMN createdByDeviceId TEXT")
        db.execSQL(
            "ALTER TABLE babies ADD COLUMN syncDirty INTEGER NOT NULL DEFAULT 1",
        )
        db.execSQL("ALTER TABLE babies ADD COLUMN avatarMediaUuid TEXT")
        db.execSQL(
            "ALTER TABLE records ADD COLUMN syncDirty INTEGER NOT NULL DEFAULT 1",
        )
        db.execSQL(
            "ALTER TABLE media_assets ADD COLUMN syncDirty INTEGER NOT NULL DEFAULT 1",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_babies_updatedAt ON babies(updatedAt)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_babies_syncDirty ON babies(syncDirty)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_records_updatedAt ON records(updatedAt)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_records_syncDirty ON records(syncDirty)")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_media_assets_updatedAt ON media_assets(updatedAt)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_media_assets_syncDirty ON media_assets(syncDirty)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_media_assets_recordId ON media_assets(recordId)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_media_assets_babyId_kind_deletedAt " +
                "ON media_assets(babyId, kind, deletedAt)",
        )
    }
}

internal val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("UPDATE media_assets SET babyId = NULL WHERE kind = 'log'")
        db.execSQL("UPDATE media_assets SET recordId = NULL WHERE kind = 'avatar'")
    }
}

internal val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `pending_reminder_cleanup` (
                `operation` TEXT NOT NULL,
                `calendarEventIds` TEXT NOT NULL,
                `familyServerRetained` INTEGER NOT NULL,
                PRIMARY KEY(`operation`)
            )
            """.trimIndent(),
        )
    }
}

/**
 * Additive ownership stamp for shared custom item definitions.
 * Existing rows keep empty creator membership (local-only / pre-family) without
 * rewriting custom_item_id references on records.
 */
internal val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE custom_items ADD COLUMN createdByMembershipId TEXT NOT NULL DEFAULT ''",
        )
    }
}

/** Local care plan table for schedule → fulfill tracer (family sync later). */
internal val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `care_plans` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `clientUuid` TEXT NOT NULL,
                `babyId` INTEGER NOT NULL,
                `type` TEXT NOT NULL,
                `customItemId` INTEGER,
                `scheduledAt` INTEGER NOT NULL,
                `scheduledZoneId` TEXT NOT NULL,
                `note` TEXT,
                `payloadJson` TEXT NOT NULL,
                `schemaVersion` INTEGER NOT NULL,
                `status` TEXT NOT NULL,
                `createdByMembershipId` TEXT NOT NULL,
                `fulfilledRecordClientUuid` TEXT,
                `fulfilledAt` INTEGER,
                `updatedAt` INTEGER NOT NULL,
                `deletedAt` INTEGER
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_care_plans_clientUuid` ON `care_plans` (`clientUuid`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_care_plans_babyId_scheduledAt` ON `care_plans` (`babyId`, `scheduledAt`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_care_plans_status` ON `care_plans` (`status`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_care_plans_updatedAt` ON `care_plans` (`updatedAt`)",
        )
    }
}

/** Plan photos: additive carePlanId owner column on media_assets. */
internal val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE media_assets ADD COLUMN carePlanId INTEGER")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_media_assets_carePlanId` ON `media_assets` (`carePlanId`)",
        )
    }
}

/** Family-sync dirty flag for shared custom item definitions. */
internal val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE custom_items ADD COLUMN syncDirty INTEGER NOT NULL DEFAULT 1",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_custom_items_syncDirty` ON `custom_items` (`syncDirty`)",
        )
    }
}

/**
 * Provenance link when a fact Record is explicitly converted to a CarePlan.
 * Additive nullable column; existing plans keep null (created directly).
 */
internal val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE care_plans ADD COLUMN sourceRecordClientUuid TEXT",
        )
    }
}

/**
 * Family-sync dirty flag for care plans. Existing local plans need a first
 * publish after upgrade (DEFAULT 1); server LWW/ACL stamps creators on commit.
 */
internal val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE care_plans ADD COLUMN syncDirty INTEGER NOT NULL DEFAULT 1",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_care_plans_syncDirty` ON `care_plans` (`syncDirty`)",
        )
    }
}

/**
 * Durable fulfillment candidates for cross-member fulfill family publish.
 * Empty on upgrade; new fulfills write rows going forward.
 */
internal val MIGRATION_14_15 = object : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `fulfillment_candidates` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `clientUuid` TEXT NOT NULL,
                `carePlanClientUuid` TEXT NOT NULL,
                `recordClientUuid` TEXT NOT NULL,
                `actualTimestamp` INTEGER,
                `confirmedAt` INTEGER NOT NULL,
                `submitterMembershipId` TEXT NOT NULL DEFAULT '',
                `submitterRole` TEXT NOT NULL DEFAULT '',
                `updatedAt` INTEGER NOT NULL,
                `deletedAt` INTEGER,
                `syncDirty` INTEGER NOT NULL DEFAULT 1
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_fulfillment_candidates_clientUuid` " +
                "ON `fulfillment_candidates` (`clientUuid`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_fulfillment_candidates_carePlanClientUuid` " +
                "ON `fulfillment_candidates` (`carePlanClientUuid`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_fulfillment_candidates_recordClientUuid` " +
                "ON `fulfillment_candidates` (`recordClientUuid`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_fulfillment_candidates_syncDirty` " +
                "ON `fulfillment_candidates` (`syncDirty`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_fulfillment_candidates_updatedAt` " +
                "ON `fulfillment_candidates` (`updatedAt`)",
        )
    }
}

/**
 * Local adoptionStatus for multi-candidate fulfillment authority (ticket 26).
 * Existing candidates stay empty until the next resolve pass.
 */
internal val MIGRATION_15_16 = object : Migration(15, 16) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE fulfillment_candidates ADD COLUMN `adoptionStatus` TEXT NOT NULL DEFAULT ''",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_fulfillment_candidates_adoptionStatus` " +
                "ON `fulfillment_candidates` (`adoptionStatus`)",
        )
    }
}

/**
 * Local convert pointer for admin conflict-not-adopted → independent Record (ticket 27).
 * Keeps convert idempotent without flipping adoption or plan authority.
 */
internal val MIGRATION_16_17 = object : Migration(16, 17) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE fulfillment_candidates ADD COLUMN " +
                "`convertedRecordClientUuid` TEXT NOT NULL DEFAULT ''",
        )
    }
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): LeziDatabase =
        Room.databaseBuilder(context, LeziDatabase::class.java, "lezi.db")
            .addMigrations(
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
                MIGRATION_6_7,
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
            )
            .build()

    @Provides fun localUserDao(db: LeziDatabase): LocalUserDao = db.localUserDao()
    @Provides fun familyDao(db: LeziDatabase): FamilyDao = db.familyDao()
    @Provides fun membershipDao(db: LeziDatabase): MembershipDao = db.membershipDao()
    @Provides fun babyDao(db: LeziDatabase): BabyDao = db.babyDao()
    @Provides fun recordDao(db: LeziDatabase): RecordDao = db.recordDao()
    @Provides fun carePlanDao(db: LeziDatabase): CarePlanDao = db.carePlanDao()
    @Provides fun fulfillmentCandidateDao(db: LeziDatabase): FulfillmentCandidateDao =
        db.fulfillmentCandidateDao()
    @Provides fun mediaAssetDao(db: LeziDatabase): MediaAssetDao = db.mediaAssetDao()
    @Provides fun outboxDao(db: LeziDatabase): OutboxDao = db.outboxDao()
    @Provides fun customItemDao(db: LeziDatabase): CustomItemDao = db.customItemDao()
    @Provides fun calendarEventDao(db: LeziDatabase): CalendarEventDao = db.calendarEventDao()
    @Provides
    @Singleton
    fun pendingReminderCleanupStore(db: LeziDatabase): PendingReminderCleanupStore =
        RoomPendingReminderCleanupStore(db.pendingReminderCleanupDao())

    @Provides
    @Singleton
    fun transactionRunner(db: LeziDatabase): DatabaseTransactionRunner =
        RoomDatabaseTransactionRunner(db)
}
