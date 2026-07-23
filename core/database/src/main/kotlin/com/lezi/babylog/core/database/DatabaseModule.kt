package com.lezi.babylog.core.database

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): LeziDatabase =
        Room.databaseBuilder(context, LeziDatabase::class.java, "lezi.db")
            .fallbackToDestructiveMigration()
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
}
