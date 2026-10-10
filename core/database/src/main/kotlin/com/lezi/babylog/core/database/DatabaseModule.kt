package com.lezi.babylog.core.database

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.lezi.babylog.core.common.validation.StartupBoundaryObservation
import com.lezi.babylog.core.database.causal.MediaReferenceDao
import com.lezi.babylog.core.database.causal.PrivateSpoolPathPolicy
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
 * coordinated by DefaultLocalDataGate before this provider is requested. Destructive
 * fallback remains forbidden as a final storage-boundary safeguard.
 */
internal fun buildLeziDatabase(
    context: Context,
    name: String = "lezi.db",
): LeziDatabase {
    StartupBoundaryObservation.record("room:construct")
    return Room.databaseBuilder(context, LeziDatabase::class.java, name)
        .addCallback(CurrentSchemaCallback)
        .build()
}

private object CurrentSchemaCallback : RoomDatabase.Callback() {
    override fun onOpen(db: SupportSQLiteDatabase) {
        StartupBoundaryObservation.record("room:open")
    }

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
    @Provides
    @Singleton
    fun privateSpoolPathPolicy(@ApplicationContext context: Context): PrivateSpoolPathPolicy =
        PrivateSpoolPathPolicy(context.filesDir)

    @Provides
    fun babyDao(
        db: LeziDatabase,
        policy: PrivateSpoolPathPolicy,
        transactions: DatabaseTransactionRunner,
    ): BabyDao = policy.guard(db.babyDao(), transactions)
    @Provides fun recordDao(db: LeziDatabase): RecordDao = db.recordDao()
    @Provides fun carePlanDao(db: LeziDatabase): CarePlanDao = db.carePlanDao()
    @Provides fun fulfillmentCandidateDao(db: LeziDatabase): FulfillmentCandidateDao =
        db.fulfillmentCandidateDao()
    @Provides
    fun mediaAssetDao(
        db: LeziDatabase,
        policy: PrivateSpoolPathPolicy,
        transactions: DatabaseTransactionRunner,
    ): MediaAssetDao = policy.guard(db.mediaAssetDao(), transactions)
    @Provides
    fun recordWakeProjectionDao(db: LeziDatabase): RecordWakeProjectionDao = db.timelineWindowDao()
    @Provides fun timelineWindowDao(db: LeziDatabase): TimelineWindowDao = db.timelineWindowDao()
    @Provides fun pendingPublishDao(db: LeziDatabase): PendingPublishDao = db.pendingPublishDao()
    @Provides fun customItemDao(db: LeziDatabase): CustomItemDao = db.customItemDao()
    @Provides fun wakeObservationDao(db: LeziDatabase) = db.wakeObservationDao()
    @Provides fun conflictSummaryDao(db: LeziDatabase) = db.conflictSummaryDao()
    @Provides fun conflictSnapshotCacheDao(db: LeziDatabase) = db.conflictSnapshotCacheDao()
    @Provides fun suspectedDuplicateGroupDao(db: LeziDatabase) = db.suspectedDuplicateGroupDao()
    @Provides fun sourceRelationDao(db: LeziDatabase) = db.sourceRelationDao()
    @Provides
    fun mediaReferenceDao(
        db: LeziDatabase,
        policy: PrivateSpoolPathPolicy,
        transactions: DatabaseTransactionRunner,
    ): MediaReferenceDao = policy.guard(db.mediaReferenceDao(), transactions)
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
