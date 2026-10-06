package com.lezi.babylog.feature.family.overview

import com.lezi.babylog.core.model.Sex
import com.lezi.babylog.core.ui.babyMetaLine
import com.lezi.babylog.domain.carelog.babyAgeLabel
import java.time.LocalDate

/**
 * Current-baby card meta: birthday/weight line, optional sex, age.
 * Birthday/weight always come from [babyMetaLine] (ticket 12).
 */
internal fun familyCurrentBabyMeta(
    birthdayEpochDay: Long,
    birthWeightGrams: Int?,
    sex: Sex?,
    today: LocalDate = LocalDate.now(),
): String {
    val sexLabel = when (sex) {
        Sex.MALE -> "男宝"
        Sex.FEMALE -> "女宝"
        Sex.UNKNOWN, null -> ""
    }
    return listOfNotNull(
        babyMetaLine(birthdayEpochDay, birthWeightGrams),
        sexLabel.takeIf { it.isNotBlank() },
        babyAgeLabel(birthdayEpochDay, today).takeIf { it.isNotBlank() },
    ).joinToString(" · ")
}

/**
 * Baby-list card meta: age · birthday/weight · optional nickname-dup flag.
 */
internal fun familyListBabyMeta(
    birthdayEpochDay: Long,
    birthWeightGrams: Int?,
    nicknameDuplicate: Boolean,
    today: LocalDate = LocalDate.now(),
): String {
    val base = "${babyAgeLabel(birthdayEpochDay, today)} · " +
        babyMetaLine(birthdayEpochDay, birthWeightGrams)
    return if (nicknameDuplicate) "$base · 昵称重复" else base
}
