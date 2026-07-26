package com.lezi.babylog.core.database

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        LocalUserEntity::class,
        FamilyEntity::class,
        MembershipEntity::class,
        BabyEntity::class,
        RecordEntity::class,
        MediaAssetEntity::class,
        OutboxEntity::class,
        CustomItemEntity::class,
        CalendarEventEntity::class,
        PendingReminderCleanupEntity::class,
    ],
    version = 8,
    exportSchema = true,
)
abstract class LeziDatabase : RoomDatabase() {
    abstract fun localUserDao(): LocalUserDao
    abstract fun familyDao(): FamilyDao
    abstract fun membershipDao(): MembershipDao
    abstract fun babyDao(): BabyDao
    abstract fun recordDao(): RecordDao
    abstract fun mediaAssetDao(): MediaAssetDao
    abstract fun outboxDao(): OutboxDao
    abstract fun customItemDao(): CustomItemDao
    abstract fun calendarEventDao(): CalendarEventDao
    abstract fun pendingReminderCleanupDao(): PendingReminderCleanupDao
}
