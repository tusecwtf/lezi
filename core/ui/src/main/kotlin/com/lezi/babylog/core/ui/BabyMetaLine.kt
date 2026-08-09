package com.lezi.babylog.core.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Shared baby meta formatting for settings and family surfaces (P3 §2.7/§2.8).
 * One semantically complete format:「2024年1月2日出生 · 出生体重 3.20kg」.
 */
fun babyMetaLine(
    birthdayEpochDay: Long,
    birthWeightGrams: Int?,
): String = listOfNotNull(
    "${formatBabyBirthday(birthdayEpochDay)}出生",
    birthWeightGrams?.let { "出生体重 ${formatBirthWeightKg(it)}" },
).joinToString(" · ")

/** Settings baby row subtitle: shared meta + local-only chrome note. */
fun settingsBabyLocalSubtitle(
    birthdayEpochDay: Long,
    birthWeightGrams: Int?,
): String = babyMetaLine(birthdayEpochDay, birthWeightGrams) + " · 本机外观与顺序"

fun formatBabyBirthday(birthdayEpochDay: Long): String =
    LocalDate.ofEpochDay(birthdayEpochDay)
        .format(DateTimeFormatter.ofPattern("yyyy年M月d日"))

fun formatBirthWeightKg(grams: Int): String =
    if (grams % 1000 == 0) {
        "${grams / 1000}kg"
    } else {
        String.format(Locale.ROOT, "%.2fkg", grams / 1000.0)
    }

/** 宝宝列表 / 设置菜单行。 */
val BabyAvatarSizeMedium: Dp = 40.dp

/** 编辑对话框「保存效果预览」特意放大，便于检查裁切与成像效果。 */
val BabyAvatarSizeEditPreview: Dp = 76.dp
