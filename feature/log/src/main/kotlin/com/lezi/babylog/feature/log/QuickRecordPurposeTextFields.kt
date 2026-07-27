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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.ui.presentation
import com.lezi.babylog.designsystem.LeziShapes
import com.lezi.babylog.designsystem.LeziTypography

@Composable
internal fun CustomTextFields(
    draft: QuickRecordDraft,
    onDraftChange: (QuickRecordDraft) -> Unit,
    highlightedField: ComposerInvalidField? = null,
    fieldFocusRequester: FocusRequester? = null,
) {
    val titleError = highlightedField == ComposerInvalidField.CustomTitle
    OutlinedTextField(
        value = draft.customTitle,
        onValueChange = { onDraftChange(draft.copy(customTitle = it.take(30))) },
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (titleError && fieldFocusRequester != null) {
                    Modifier.focusRequester(fieldFocusRequester)
                } else {
                    Modifier
                },
            ),
        label = {
            Text(
                "项目名称",
            )
        },
        isError = titleError,
        singleLine = true,
        // Snapshot title remains editable; the concrete definition identity stays fixed.
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
    highlightedField: ComposerInvalidField? = null,
    fieldFocusRequester: FocusRequester? = null,
) {
    val unit = if (draft.type == RecordType.WEIGHT) "kg" else "cm"
    val measurementError = highlightedField == ComposerInvalidField.MeasurementValue
    DecimalField(
        value = draft.measurementValue,
        label = "${draft.type.presentation.label}（$unit）",
        modifier = Modifier.fillMaxWidth(),
        isError = measurementError,
        focusRequester = fieldFocusRequester.takeIf { measurementError },
    ) { onDraftChange(draft.copy(measurementValue = it)) }
}

@Composable
internal fun FoodFields(
    draft: QuickRecordDraft,
    birthdayEpochDay: Long?,
    onDraftChange: (QuickRecordDraft) -> Unit,
    highlightedField: ComposerInvalidField? = null,
    fieldFocusRequester: FocusRequester? = null,
) {
    val contentError = highlightedField == ComposerInvalidField.FoodContent
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
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (contentError && fieldFocusRequester != null) {
                    Modifier.focusRequester(fieldFocusRequester)
                } else {
                    Modifier
                },
            ),
        label = { Text("内容") },
        placeholder = { Text(if (draft.type == RecordType.DRINK) "例如 温水" else "例如 南瓜米糊") },
        isError = contentError,
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
    highlightedField: ComposerInvalidField? = null,
    fieldFocusRequester: FocusRequester? = null,
) {
    val nameError = highlightedField == ComposerInvalidField.VaccineName
    OutlinedTextField(
        value = draft.vaccineName,
        onValueChange = { onDraftChange(draft.copy(vaccineName = it.take(80))) },
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (nameError && fieldFocusRequester != null) {
                    Modifier.focusRequester(fieldFocusRequester)
                } else {
                    Modifier
                },
            ),
        label = { Text("疫苗名称") },
        isError = nameError,
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
