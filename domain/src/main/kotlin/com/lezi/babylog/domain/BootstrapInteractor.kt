package com.lezi.babylog.domain

import com.lezi.babylog.core.common.newClientUuid
import com.lezi.babylog.core.database.BabyDao
import com.lezi.babylog.core.database.BabyEntity
import com.lezi.babylog.core.database.FamilyDao
import com.lezi.babylog.core.database.FamilyEntity
import com.lezi.babylog.core.database.LocalUserDao
import com.lezi.babylog.core.database.LocalUserEntity
import com.lezi.babylog.core.database.MembershipDao
import com.lezi.babylog.core.database.MembershipEntity
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class CreateBabyInput(
    val nickname: String,
    val sex: String? = null,
    val birthdayEpochDay: Long,
    val themeColorArgb: Int = 0xFF2F6FED.toInt(),
)

@Singleton
class BootstrapInteractor @Inject constructor(
    private val localUserDao: LocalUserDao,
    private val familyDao: FamilyDao,
    private val membershipDao: MembershipDao,
    private val babyDao: BabyDao,
) {
    fun observeHasBaby(): Flow<Boolean> = babyDao.observeAll().map { it.isNotEmpty() }

    suspend fun createBaby(input: CreateBabyInput): Long {
        val now = System.currentTimeMillis()
        val userId = localUserDao.get()?.id ?: localUserDao.upsert(
            LocalUserEntity(
                deviceId = UUID.randomUUID().toString(),
                createdAt = now,
            ),
        )
        val existingFamily = familyDao.get(1)
        val familyId = existingFamily?.id ?: run {
            val id = familyDao.insert(
                FamilyEntity(ownerUserId = userId, createdAt = now),
            )
            membershipDao.upsert(
                MembershipEntity(
                    familyId = id,
                    userId = userId,
                    role = "owner",
                    status = "active",
                    joinedAt = now,
                ),
            )
            id
        }
        return babyDao.upsert(
            BabyEntity(
                familyId = familyId,
                nickname = input.nickname.trim().ifBlank { "宝宝" },
                sex = input.sex,
                birthdayEpochDay = input.birthdayEpochDay,
                themeColorArgb = input.themeColorArgb,
                clientUuid = newClientUuid(),
                updatedAt = now,
            ),
        )
    }
}
