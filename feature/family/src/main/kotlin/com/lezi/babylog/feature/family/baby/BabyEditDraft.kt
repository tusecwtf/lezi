package com.lezi.babylog.feature.family.baby

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.lezi.babylog.core.model.Baby
import com.lezi.babylog.core.model.BirthWeightInputResult
import com.lezi.babylog.core.model.parseBirthWeightKilograms
import com.lezi.babylog.core.ui.OwnedCameraCapture
import com.lezi.babylog.designsystem.normalizeBabyThemeArgb

internal enum class BabyProfileExit {
    Close,
    ConfirmDiscard,
    Saving,
}

/** One in-memory profile session. No Activity, launcher, Bundle or process-restoration state. */
internal class BabyEditDraft(private val original: Baby) {
    val babyId: Long = original.id
    private val originalSex = when (original.sex?.name) {
        "FEMALE" -> "female"
        "MALE" -> "male"
        else -> null
    }
    private val originalWeightText = original.birthWeightGrams?.let { (it / 1000.0).toString() }.orEmpty()
    var nickname by mutableStateOf(original.nickname)
    var sex by mutableStateOf(originalSex)
    var birthday by mutableStateOf(original.birthdayEpochDay)
    var weightText by mutableStateOf(originalWeightText)
    var nicknameError by mutableStateOf<String?>(null)
    var weightError by mutableStateOf<String?>(null)
    var formError by mutableStateOf<String?>(null)
    var localPrefError by mutableStateOf<String?>(null)
    var avatarError by mutableStateOf<String?>(null)
    var themeArgb by mutableStateOf(normalizeBabyThemeArgb(original.themeColorArgb))
    var pickedAvatarUri by mutableStateOf<Uri?>(null)
    var croppedAvatar by mutableStateOf<CroppedAvatar?>(null)
    var removeAvatar by mutableStateOf(false)
    var showDate by mutableStateOf(false)
    var showDiscard by mutableStateOf(false)
        private set
    var saving by mutableStateOf(false)
        private set
    var saved by mutableStateOf(false)
        private set
    private var ownedAvatarCapture: OwnedCameraCapture? = null
    private var closed = false

    fun belongsTo(baby: Baby): Boolean =
        baby.id == babyId && baby.clientUuid == original.clientUuid && baby.familyId == original.familyId

    val avatarChanged: Boolean
        get() = croppedAvatar != null || (removeAvatar && original.avatarPath != null)

    val dirty: Boolean
        get() {
            val weight = parseBirthWeightKilograms(weightText)
            // The legacy Double conversion can truncate the unedited display by one gram
            // (4015g -> "4.015" -> 4014g). That alone is never an unsaved user change.
            val weightChanged = weightText.trim() != originalWeightText &&
                (weight !is BirthWeightInputResult.Valid || weight.grams != original.birthWeightGrams)
            return nickname.trim() != original.nickname || sex != originalSex ||
                birthday != original.birthdayEpochDay ||
                weightChanged || avatarChanged
        }

    fun requestExit(): BabyProfileExit = when {
        saving || saved -> BabyProfileExit.Saving
        dirty -> BabyProfileExit.ConfirmDiscard.also { showDiscard = true }
        else -> BabyProfileExit.Close
    }

    fun keepEditing() {
        showDiscard = false
    }

    fun beginSave(): BirthWeightInputResult.Valid? {
        if (saving || saved || closed) return null
        nicknameError = if (nickname.isBlank()) "请填写昵称" else null
        val weight = parseBirthWeightKilograms(weightText)
        weightError = (weight as? BirthWeightInputResult.Invalid)?.message
        if (nicknameError != null || weight !is BirthWeightInputResult.Valid) return null
        saving = true
        showDiscard = false
        formError = null
        return weight
    }

    fun finishSave(error: String?) {
        if (closed) return
        saving = false
        formError = error
        saved = error == null
    }

    fun consumeSaved(): Boolean {
        if (!saved || closed) return false
        saved = false
        return true
    }

    fun replaceOwnedCapture(capture: OwnedCameraCapture) {
        releaseOwnedCapture()
        ownedAvatarCapture = capture
    }

    fun releaseOwnedCapture() {
        ownedAvatarCapture?.release()
        ownedAvatarCapture = null
    }

    fun close() {
        if (closed) return
        closed = true
        releaseOwnedCapture()
        pickedAvatarUri = null
        croppedAvatar = null
        showDate = false
        showDiscard = false
    }
}

/** Activity/navigation owner retains at most one session through configuration rebuilding. */
internal class BabyEditDraftHost : ViewModel() {
    private var session: BabyEditDraft? = null

    fun open(baby: Baby): BabyEditDraft {
        session?.takeIf { it.belongsTo(baby) }?.let { return it }
        session?.close()
        return BabyEditDraft(baby).also { session = it }
    }

    fun close(draft: BabyEditDraft) {
        draft.close()
        if (session === draft) session = null
    }

    override fun onCleared() {
        session?.close()
        session = null
    }
}
