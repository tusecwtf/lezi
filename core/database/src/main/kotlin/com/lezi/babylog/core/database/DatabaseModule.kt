package com.lezi.babylog.core.database

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Builds the current Room schema for a fresh installation.
 *
 * Deliberately registers neither historical migrations nor a destructive
 * fallback. A database from any older schema therefore fails to open instead
 * of being silently upgraded or erased.
 */
internal fun buildLeziDatabase(
    context: Context,
    name: String = "lezi.db",
): LeziDatabase = Room.databaseBuilder(context, LeziDatabase::class.java, name)
    .addCallback(CurrentSchemaCallback)
    .build()

private object CurrentSchemaCallback : RoomDatabase.Callback() {
    override fun onCreate(db: SupportSQLiteDatabase) {
        db.execSQL(mediaAssetOwnerTrigger("media_assets_owner_insert", "INSERT"))
        db.execSQL(mediaAssetOwnerTrigger("media_assets_owner_update", "UPDATE"))
    }
}

private fun mediaAssetOwnerTrigger(name: String, operation: String): String =
    """
    CREATE TRIGGER $name
    BEFORE $operation ON media_assets
    WHEN NOT (
        (
            NEW.kind = 'log'
            AND NEW.babyId IS NULL
            AND (
                (NEW.recordId IS NOT NULL AND NEW.carePlanId IS NULL)
                OR (NEW.recordId IS NULL AND NEW.carePlanId IS NOT NULL)
            )
        )
        OR (
            NEW.kind = 'avatar'
            AND NEW.babyId IS NOT NULL
            AND NEW.recordId IS NULL
            AND NEW.carePlanId IS NULL
        )
    )
    BEGIN
        SELECT RAISE(ABORT, 'invalid media asset ownership');
    END
    """.trimIndent()

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): LeziDatabase =
        buildLeziDatabase(context)

    @Provides fun localUserDao(db: LeziDatabase): LocalUserDao = db.localUserDao()
    @Provides fun familyDao(db: LeziDatabase): FamilyDao = db.familyDao()
    @Provides fun membershipDao(db: LeziDatabase): MembershipDao = db.membershipDao()
    @Provides fun babyDao(db: LeziDatabase): BabyDao = db.babyDao()
    @Provides fun recordDao(db: LeziDatabase): RecordDao = db.recordDao()
    @Provides fun carePlanDao(db: LeziDatabase): CarePlanDao = db.carePlanDao()
    @Provides fun fulfillmentCandidateDao(db: LeziDatabase): FulfillmentCandidateDao =
        db.fulfillmentCandidateDao()
    @Provides fun mediaAssetDao(db: LeziDatabase): MediaAssetDao = db.mediaAssetDao()
    @Provides fun timelineWindowDao(db: LeziDatabase): TimelineWindowDao = db.timelineWindowDao()
    @Provides fun outboxDao(db: LeziDatabase): OutboxDao = db.outboxDao()
    @Provides fun customItemDao(db: LeziDatabase): CustomItemDao = db.customItemDao()
    @Provides
    @Singleton
    fun pendingReminderCleanupStore(db: LeziDatabase): PendingReminderCleanupStore =
        RoomPendingReminderCleanupStore(db.pendingReminderCleanupDao())

    @Provides
    @Singleton
    fun pendingReplicaCleanupStore(db: LeziDatabase): PendingReplicaCleanupStore =
        RoomPendingReplicaCleanupStore(db.pendingReplicaCleanupDao())

    @Provides
    @Singleton
    fun transactionRunner(db: LeziDatabase): DatabaseTransactionRunner =
        RoomDatabaseTransactionRunner(db)
}
