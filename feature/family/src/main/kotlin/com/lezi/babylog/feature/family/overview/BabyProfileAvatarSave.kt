package com.lezi.babylog.feature.family.overview

import com.lezi.babylog.core.model.Baby
import com.lezi.babylog.domain.CareLog
import com.lezi.babylog.domain.DuplicateBabyNicknameException
import com.lezi.babylog.domain.UpdateBabyInput
import com.lezi.babylog.feature.family.baby.BabyAvatarFileStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Pure avatar + profile write/rollback transaction used by [AccountOverviewHost].
 * Keeps the overview host a thin façade without inventing a new deep port.
 */
internal suspend fun saveBabyProfileWithAvatar(
    careLog: CareLog,
    avatarFileStore: BabyAvatarFileStore,
    existing: Baby,
    nickname: String,
    sex: String?,
    birthdayEpochDay: Long,
    birthWeightGrams: Int?,
    avatarJpeg: ByteArray?,
    removeAvatar: Boolean,
    mayEditAvatar: Boolean,
): String? {
    var writtenAvatarPath: String? = null
    var profileCommitted = false

    suspend fun rollbackWrittenAvatar() {
        val path = writtenAvatarPath ?: return
        withContext(NonCancellable) {
            try {
                avatarFileStore.delete(path)
            } catch (_: Throwable) {
                // Preserve the original save failure or cancellation.
            }
        }
    }

    return try {
        val avatarPath = when {
            mayEditAvatar && avatarJpeg != null -> {
                avatarFileStore.write(existing.clientUuid, avatarJpeg).also {
                    writtenAvatarPath = it
                }
            }
            mayEditAvatar && removeAvatar -> null
            else -> existing.avatarPath
        }
        currentCoroutineContext().ensureActive()
        withContext(NonCancellable) {
            careLog.updateBabyProfile(
                existing.id,
                UpdateBabyInput(
                    nickname = nickname,
                    sex = sex,
                    birthdayEpochDay = birthdayEpochDay,
                    birthWeightGrams = birthWeightGrams,
                    avatarPath = avatarPath,
                    themeColorArgb = existing.themeColorArgb,
                ),
            )
            profileCommitted = true
        }
        currentCoroutineContext().ensureActive()
        null
    } catch (cancelled: CancellationException) {
        if (!profileCommitted) rollbackWrittenAvatar()
        throw cancelled
    } catch (error: Throwable) {
        if (!profileCommitted) rollbackWrittenAvatar()
        if (error is DuplicateBabyNicknameException) {
            error.message
        } else {
            "保存失败，请重试"
        }
    }
}

/** Soft-delete baby profile; domain cleanup rechecks every media reference after commit. */
internal suspend fun deleteBabyProfileWithAvatar(
    careLog: CareLog,
    babyId: Long,
): Boolean = withContext(NonCancellable) {
    careLog.deleteBaby(babyId)
}
