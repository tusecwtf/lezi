package com.lezi.babylog.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.lezi.babylog.core.database.causal.ConflictDetailCacheDao
import com.lezi.babylog.core.database.causal.ConflictDetailCacheEntity
import com.lezi.babylog.core.database.causal.ConflictSummaryDao
import com.lezi.babylog.core.database.causal.ConflictSummaryEntity
import com.lezi.babylog.core.database.causal.MediaReferenceDao
import com.lezi.babylog.core.database.causal.MediaReferenceEntity
import com.lezi.babylog.core.database.causal.SourceRelationDao
import com.lezi.babylog.core.database.causal.SourceRelationDeclarationEntity
import com.lezi.babylog.core.database.causal.SourceRelationEntity
import com.lezi.babylog.core.database.causal.SourceRelationMemberEntity
import com.lezi.babylog.core.database.causal.SuspectedDuplicateGroupDao
import com.lezi.babylog.core.database.causal.SuspectedDuplicateGroupEntity
import com.lezi.babylog.core.database.causal.WakeObservationDao
import com.lezi.babylog.core.database.causal.WakeObservationEntity

@Database(
    entities = [
        LocalUserEntity::class,
        FamilyEntity::class,
        MembershipEntity::class,
        BabyEntity::class,
        RecordEntity::class,
        CarePlanEntity::class,
        FulfillmentCandidateEntity::class,
        MediaAssetEntity::class,
        CustomItemEntity::class,
        PendingReminderCleanupEntity::class,
        PendingReplicaCleanupEntity::class,
        WakeObservationEntity::class,
        ConflictSummaryEntity::class,
        ConflictDetailCacheEntity::class,
        SuspectedDuplicateGroupEntity::class,
        SourceRelationEntity::class,
        SourceRelationMemberEntity::class,
        SourceRelationDeclarationEntity::class,
        MediaReferenceEntity::class,
    ],
    version = 27,
    exportSchema = true,
)
abstract class LeziDatabase : RoomDatabase() {
    abstract fun localUserDao(): LocalUserDao
    abstract fun familyDao(): FamilyDao
    abstract fun membershipDao(): MembershipDao
    abstract fun babyDao(): BabyDao
    abstract fun recordDao(): RecordDao
    abstract fun carePlanDao(): CarePlanDao
    abstract fun fulfillmentCandidateDao(): FulfillmentCandidateDao
    abstract fun mediaAssetDao(): MediaAssetDao
    abstract fun timelineWindowDao(): TimelineWindowDao
    abstract fun pendingPublishDao(): PendingPublishDao
    abstract fun customItemDao(): CustomItemDao
    abstract fun pendingReminderCleanupDao(): PendingReminderCleanupDao
    abstract fun pendingReplicaCleanupDao(): PendingReplicaCleanupDao
    abstract fun wakeObservationDao(): WakeObservationDao
    abstract fun conflictSummaryDao(): ConflictSummaryDao
    abstract fun conflictDetailCacheDao(): ConflictDetailCacheDao
    abstract fun suspectedDuplicateGroupDao(): SuspectedDuplicateGroupDao
    abstract fun sourceRelationDao(): SourceRelationDao
    abstract fun mediaReferenceDao(): MediaReferenceDao
}
