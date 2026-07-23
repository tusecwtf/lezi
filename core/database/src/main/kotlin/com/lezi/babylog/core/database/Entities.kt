package com.lezi.babylog.core.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "local_users")
data class LocalUserEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val displayName: String? = null,
    val deviceId: String,
    val createdAt: Long,
)

@Entity(tableName = "families")
data class FamilyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ownerUserId: Long,
    val createdAt: Long,
)

@Entity(
    tableName = "memberships",
    primaryKeys = ["familyId", "userId"],
)
data class MembershipEntity(
    val familyId: Long,
    val userId: Long,
    val role: String,
    val status: String,
    val joinedAt: Long,
)

@Entity(
    tableName = "babies",
    indices = [
        Index("familyId"),
        Index(value = ["clientUuid"], unique = true),
    ],
)
data class BabyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val familyId: Long,
    val nickname: String,
    val sex: String? = null,
    val birthdayEpochDay: Long,
    /** Birth weight in grams; null when not set. */
    val birthWeightGrams: Int? = null,
    val dueDateEpochDay: Long? = null,
    val themeColorArgb: Int,
    val sortOrder: Int = 0,
    val clientUuid: String,
    val updatedAt: Long,
    val deletedAt: Long? = null,
)

@Entity(
    tableName = "records",
    indices = [
        Index("babyId", "timestamp"),
        Index(value = ["clientUuid"], unique = true),
        Index("babyId", "type", "timestamp"),
    ],
)
data class RecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val clientUuid: String,
    val babyId: Long,
    val type: String,
    val timestamp: Long,
    val endTimestamp: Long? = null,
    val note: String? = null,
    val createdByUserId: Long,
    val payloadJson: String = "{}",
    val schemaVersion: Int = 1,
    val updatedAt: Long,
    val deletedAt: Long? = null,
)

@Entity(tableName = "media_assets")
data class MediaAssetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recordId: Long,
    val localUri: String,
    val remoteUri: String? = null,
    val mime: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val createdAt: Long,
)
