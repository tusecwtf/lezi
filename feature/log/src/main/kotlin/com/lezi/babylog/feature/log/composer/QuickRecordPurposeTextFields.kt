package com.lezi.babylog.feature.log.composer
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import com.lezi.babylog.designsystem.LeziThemeExt
import com.lezi.babylog.designsystem.LeziTypography
import com.lezi.babylog.designsystem.LeziFilterChip
import com.lezi.babylog.designsystem.LeziTextButton
import com.lezi.babylog.designsystem.LeziTextField
import com.lezi.babylog.feature.log.*
import com.lezi.babylog.feature.log.timeline.*
import com.lezi.babylog.feature.log.dock.*
import com.lezi.babylog.feature.log.layout.*
import com.lezi.babylog.feature.log.photo.*

@Composable
internal fun CustomTextFields(
    draft: QuickRecordDraft,
    onDraftChange: (QuickRecordDraft) -> Unit,
    highlightedField: ComposerInvalidField? = null,
    fieldFocusRequester: FocusRequester? = null,
    enabled: Boolean = true,
) {
    val titleError = highlightedField == ComposerInvalidField.CustomTitle
    LeziTextField(
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
        enabled = enabled,
        isError = titleError,
        singleLine = true,
        // Snapshot title remains editable; the concrete definition identity stays fixed.
    )
    LeziTextField(
        value = draft.customDetail,
        onValueChange = { onDraftChange(draft.copy(customDetail = it.take(200))) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = false,
        enabled = enabled,
        label = { Text("详情（可选）") },
        minLines = 2,
        maxLines = 6,
    )
}

@Composable
internal fun MeasurementFields(
    draft: QuickRecordDraft,
    onDraftChange: (QuickRecordDraft) -> Unit,
    highlightedField: ComposerInvalidField? = null,
    fieldFocusRequester: FocusRequester? = null,
    enabled: Boolean = true,
) {
    val unit = if (draft.type == RecordType.WEIGHT) "kg" else "cm"
    val measurementError = highlightedField == ComposerInvalidField.MeasurementValue
    DecimalField(
        value = draft.measurementValue,
        label = "${draft.type.presentation.label}（$unit）",
        modifier = Modifier.fillMaxWidth(),
        isError = measurementError,
        focusRequester = fieldFocusRequester.takeIf { measurementError },
        enabled = enabled,
    ) { onDraftChange(draft.copy(measurementValue = it)) }
}

@Composable
internal fun FoodFields(
    draft: QuickRecordDraft,
    birthdayEpochDay: Long?,
    onDraftChange: (QuickRecordDraft) -> Unit,
    highlightedField: ComposerInvalidField? = null,
    fieldFocusRequester: FocusRequester? = null,
    enabled: Boolean = true,
) {
    val contentError = highlightedField == ComposerInvalidField.FoodContent
    val amountError = highlightedField == ComposerInvalidField.FoodAmount
    if (draft.type == RecordType.BABY_FOOD && birthdayEpochDay != null) {
        BabyFoodGuidancePanel(
            birthdayEpochDay = birthdayEpochDay,
            atMillis = draft.timestamp,
            selectedContent = draft.foodContent,
            onSuggestionClick = { suggestion ->
                onDraftChange(draft.copy(foodContent = suggestion.take(80)))
            },
            enabled = enabled,
        )
    }
    LeziTextField(
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
        enabled = enabled,
        isError = contentError,
        singleLine = true,
    )
    LeziTextField(
        value = draft.foodAmount,
        onValueChange = { onDraftChange(draft.copy(foodAmount = it.take(30))) },
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (amountError && fieldFocusRequester != null) {
                    Modifier.focusRequester(fieldFocusRequester)
                } else {
                    Modifier
                },
            ),
        label = { Text("量") },
        placeholder = { Text(if (draft.type == RecordType.DRINK) "例如 80 ml" else "例如 半碗") },
        isError = amountError,
        singleLine = true,
    )
}

@Composable
private fun BabyFoodGuidancePanel(
    birthdayEpochDay: Long,
    atMillis: Long,
    selectedContent: String,
    onSuggestionClick: (String) -> Unit,
    enabled: Boolean = true,
) {
    val guidance = remember(birthdayEpochDay, atMillis) {
        babyFoodGuidanceAt(birthdayEpochDay, atMillis)
    }
    var expanded by remember(guidance.stage.id) { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = LeziThemeExt.controlShape,
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
                        LeziFilterChip(selected = selectedContent == item, onClick = { onSuggestionClick(item) }, label = item, enabled = enabled)
                    }
                }
            }
            LeziTextButton(label = if (expanded) "收起阶段说明" else "为什么这样建议", onClick = { expanded = !expanded })
            AnimatedVisibility(visible = expanded) {
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
    enabled: Boolean = true,
) {
    val nameError = highlightedField == ComposerInvalidField.VaccineName
    LeziTextField(
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
        enabled = enabled,
        isError = nameError,
        singleLine = true,
    )
    LeziTextField(
        value = draft.vaccineBatch,
        onValueChange = { onDraftChange(draft.copy(vaccineBatch = it.take(50))) },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("批次 / 针次（可选）") },
        singleLine = true,
    )
}
