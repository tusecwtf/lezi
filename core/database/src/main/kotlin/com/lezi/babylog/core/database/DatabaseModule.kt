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

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): LeziDatabase =
        Room.databaseBuilder(context, LeziDatabase::class.java, "lezi.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
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
