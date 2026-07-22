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
    ],
    version = 1,
    exportSchema = true,
)
abstract class LeziDatabase : RoomDatabase() {
    abstract fun localUserDao(): LocalUserDao
    abstract fun familyDao(): FamilyDao
    abstract fun membershipDao(): MembershipDao
    abstract fun babyDao(): BabyDao
    abstract fun recordDao(): RecordDao
}
