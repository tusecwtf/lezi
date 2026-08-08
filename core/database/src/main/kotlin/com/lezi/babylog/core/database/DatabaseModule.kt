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
 * Builds the Room schema after the process-wide local-data gate has verified it.
 *
 * Contract v1 has no predecessor migration. Future adjacent migrations are
 * coordinated by LocalDataGate before this provider is requested. Destructive
 * fallback remains forbidden as a final storage-boundary safeguard.
 */
internal fun buildLeziDatabase(
    context: Context,
    name: String = "lezi.db",
): LeziDatabase = Room.databaseBuilder(context, LeziDatabase::class.java, name)
    .addCallback(CurrentSchemaCallback)
    .build()

private object CurrentSchemaCallback : RoomDatabase.Callback() {
    override fun onCreate(db: SupportSQLiteDatabase) {
        db.execSQL(mediaAssetOwnerTriggerSql("media_assets_owner_insert", "INSERT"))
        db.execSQL(mediaAssetOwnerTriggerSql("media_assets_owner_update", "UPDATE"))
    }
}

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
    @Provides fun pendingPublishDao(db: LeziDatabase): PendingPublishDao = db.pendingPublishDao()
    @Provides fun customItemDao(db: LeziDatabase): CustomItemDao = db.customItemDao()
    @Provides fun wakeObservationDao(db: LeziDatabase) = db.wakeObservationDao()
    @Provides fun conflictSummaryDao(db: LeziDatabase) = db.conflictSummaryDao()
    @Provides fun conflictDetailCacheDao(db: LeziDatabase) = db.conflictDetailCacheDao()
    @Provides fun suspectedDuplicateGroupDao(db: LeziDatabase) = db.suspectedDuplicateGroupDao()
    @Provides fun sourceRelationDao(db: LeziDatabase) = db.sourceRelationDao()
    @Provides fun mediaReferenceDao(db: LeziDatabase) = db.mediaReferenceDao()
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
