package com.lezi.babylog.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.lezi.babylog.core.model.limitBabyNicknameInput
import com.lezi.babylog.designsystem.LeziDatePicker
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziTypography
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Shared 昵称/性别/出生日期/出生体重 field group for baby-profile dialogs
 * (settings 添加宝宝 + family 编辑宝宝档案). Keeps the fuller edit-dialog
 * affordances: nickname error supportingText and weight placeholder/hint.
 */
@Composable
fun BabyProfileFormFields(
    nickname: String,
    onNicknameChange: (String) -> Unit,
    nicknameError: String?,
    sex: String?,
    onSexChange: (String?) -> Unit,
    birthdayEpochDay: Long,
    onPickBirthday: () -> Unit,
    weightText: String,
    onWeightTextChange: (String) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val dateLabel = LocalDate.ofEpochDay(birthdayEpochDay)
        .format(DateTimeFormatter.ofPattern("yyyy年M月d日"))
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm),
    ) {
        OutlinedTextField(
            value = nickname,
            enabled = enabled,
            onValueChange = { onNicknameChange(limitBabyNicknameInput(it)) },
            label = { Text("昵称（不可重复）") },
            singleLine = true,
            isError = nicknameError != null,
            supportingText = nicknameError?.let { { Text(it) } },
            modifier = Modifier.fillMaxWidth(),
        )
        Text("性别", style = LeziTypography.Label)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Home-LAN wire values only (female/male/null), not Kotlin enum names.
            listOf(
                "female" to "女宝",
                "male" to "男宝",
                null to "未设置",
            ).forEach { (key, label) ->
                FilterChip(
                    selected = sex == key,
                    enabled = enabled,
                    onClick = { onSexChange(key) },
                    label = { Text(label) },
                )
            }
        }
        Text("出生日期", style = LeziTypography.Label)
        OutlinedButton(
            enabled = enabled,
            onClick = onPickBirthday,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(dateLabel) }
        OutlinedTextField(
            value = weightText,
            enabled = enabled,
            onValueChange = { onWeightTextChange(it.filter { ch -> ch.isDigit() || ch == '.' }) },
            label = { Text("出生体重（kg，可选）") },
            placeholder = { Text("例如 3.20") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            supportingText = { Text("可填千克，保存时换算为克") },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Shared UTC-safe birthday DatePickerDialog for baby-profile dialogs. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BabyBirthdayDatePickerDialog(
    birthdayEpochDay: Long,
    onDismiss: () -> Unit,
    onSelect: (Long) -> Unit,
) {
    val initialUtc = LocalDate.ofEpochDay(birthdayEpochDay)
        .atStartOfDay(ZoneOffset.UTC)
        .toInstant()
        .toEpochMilli()
    val dateState = rememberDatePickerState(initialSelectedDateMillis = initialUtc)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    dateState.selectedDateMillis?.let { ms ->
                        onSelect(
                            Instant.ofEpochMilli(ms)
                                .atZone(ZoneOffset.UTC)
                                .toLocalDate()
                                .toEpochDay(),
                        )
                    }
                    onDismiss()
                },
            ) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    ) {
        LeziDatePicker(state = dateState)
    }
}
