package com.lezi.babylog.feature.log

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.ui.presentation
import com.lezi.babylog.designsystem.LeziShapes
import com.lezi.babylog.designsystem.LeziTypography
import com.lezi.babylog.domain.CustomRecordItem

@Composable
internal fun CustomTextFields(
    draft: QuickRecordDraft,
    customItems: List<CustomRecordItem>,
    onDraftChange: (QuickRecordDraft) -> Unit,
) {
    if (draft.type == RecordType.CUSTOM && customItems.isNotEmpty()) {
        Text("选择自定义项目", style = LeziTypography.Label)
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            customItems.forEach { item ->
                FilterChip(
                    selected = draft.customItemId == item.id,
                    onClick = {
                        onDraftChange(
                            draft.copy(
                                customTitle = item.name,
                                customItemId = item.id,
                                customIconSlot = item.iconSlot,
                            ),
                        )
                    },
                    label = { Text(item.name) },
                )
            }
        }
    }
    OutlinedTextField(
        value = draft.customTitle,
        onValueChange = { onDraftChange(draft.copy(customTitle = it.take(30))) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(if (draft.type == RecordType.CUSTOM) "自定义项目名称" else "标题") },
        singleLine = true,
    )
    OutlinedTextField(
        value = draft.customDetail,
        onValueChange = { onDraftChange(draft.copy(customDetail = it.take(200))) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("详情（可选）") },
        minLines = 2,
    )
}

@Composable
internal fun MeasurementFields(
    draft: QuickRecordDraft,
    onDraftChange: (QuickRecordDraft) -> Unit,
) {
    val unit = if (draft.type == RecordType.WEIGHT) "kg" else "cm"
    DecimalField(
        value = draft.measurementValue,
        label = "${draft.type.presentation.label}（$unit）",
        modifier = Modifier.fillMaxWidth(),
    ) { onDraftChange(draft.copy(measurementValue = it)) }
}

@Composable
internal fun FoodFields(
    draft: QuickRecordDraft,
    birthdayEpochDay: Long?,
    onDraftChange: (QuickRecordDraft) -> Unit,
) {
    if (draft.type == RecordType.BABY_FOOD && birthdayEpochDay != null) {
        BabyFoodGuidancePanel(
            birthdayEpochDay = birthdayEpochDay,
            atMillis = draft.timestamp,
            selectedContent = draft.foodContent,
            onSuggestionClick = { suggestion ->
                onDraftChange(draft.copy(foodContent = suggestion.take(80)))
            },
        )
    }
    OutlinedTextField(
        value = draft.foodContent,
        onValueChange = { onDraftChange(draft.copy(foodContent = it.take(80))) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("内容") },
        placeholder = { Text(if (draft.type == RecordType.DRINK) "例如 温水" else "例如 南瓜米糊") },
        singleLine = true,
    )
    OutlinedTextField(
        value = draft.foodAmount,
        onValueChange = { onDraftChange(draft.copy(foodAmount = it.take(30))) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("量（可选）") },
        placeholder = { Text(if (draft.type == RecordType.DRINK) "例如 80 ml" else "例如 半碗") },
        singleLine = true,
    )
}

@Composable
private fun BabyFoodGuidancePanel(
    birthdayEpochDay: Long,
    atMillis: Long,
    selectedContent: String,
    onSuggestionClick: (String) -> Unit,
) {
    val guidance = remember(birthdayEpochDay, atMillis) {
        babyFoodGuidanceAt(birthdayEpochDay, atMillis)
    }
    var expanded by remember(guidance.stage.id) { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = LeziShapes.Sm,
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
    ) {
        Column(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "${guidance.ageLabel} · ${guidance.stage.title}",
                style = LeziTypography.BodyStrong,
            )
            if (guidance.stage.suggestions.isEmpty()) {
                Text(
                    "此阶段一般仍以母乳或配方奶为主；满约 6 月龄再考虑泥糊起步。",
                    style = LeziTypography.Meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "本阶段可尝试（点选填入内容）",
                    style = LeziTypography.Meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    guidance.stage.suggestions.forEach { item ->
                        FilterChip(
                            selected = selectedContent == item,
                            onClick = { onSuggestionClick(item) },
                            label = { Text(item) },
                        )
                    }
                }
            }
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "收起阶段说明" else "为什么这样建议")
            }
            if (expanded) {
                Text(
                    guidance.stage.explanation,
                    style = LeziTypography.Meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                BABY_FOOD_DISCLAIMER,
                style = LeziTypography.Meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun VaccineFields(
    draft: QuickRecordDraft,
    onDraftChange: (QuickRecordDraft) -> Unit,
) {
    OutlinedTextField(
        value = draft.vaccineName,
        onValueChange = { onDraftChange(draft.copy(vaccineName = it.take(80))) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("疫苗名称") },
        singleLine = true,
    )
    OutlinedTextField(
        value = draft.vaccineBatch,
        onValueChange = { onDraftChange(draft.copy(vaccineBatch = it.take(50))) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("批次 / 针次（可选）") },
        singleLine = true,
    )
}
