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
            )
            .build()

    @Provides fun localUserDao(db: LeziDatabase): LocalUserDao = db.localUserDao()
    @Provides fun familyDao(db: LeziDatabase): FamilyDao = db.familyDao()
    @Provides fun membershipDao(db: LeziDatabase): MembershipDao = db.membershipDao()
    @Provides fun babyDao(db: LeziDatabase): BabyDao = db.babyDao()
    @Provides fun recordDao(db: LeziDatabase): RecordDao = db.recordDao()
    @Provides fun mediaAssetDao(db: LeziDatabase): MediaAssetDao = db.mediaAssetDao()
    @Provides fun outboxDao(db: LeziDatabase): OutboxDao = db.outboxDao()
    @Provides fun customItemDao(db: LeziDatabase): CustomItemDao = db.customItemDao()
    @Provides fun calendarEventDao(db: LeziDatabase): CalendarEventDao = db.calendarEventDao()

    @Provides
    @Singleton
    fun transactionRunner(db: LeziDatabase): DatabaseTransactionRunner =
        RoomDatabaseTransactionRunner(db)
}
