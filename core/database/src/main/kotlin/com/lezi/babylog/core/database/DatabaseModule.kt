package com.lezi.babylog.core.database

import android.content.Context
import androidx.room.Room
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
): LeziDatabase = Room.databaseBuilder(context, LeziDatabase::class.java, name).build()

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
    @Provides fun outboxDao(db: LeziDatabase): OutboxDao = db.outboxDao()
    @Provides fun customItemDao(db: LeziDatabase): CustomItemDao = db.customItemDao()
    @Provides fun calendarEventDao(db: LeziDatabase): CalendarEventDao = db.calendarEventDao()
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
