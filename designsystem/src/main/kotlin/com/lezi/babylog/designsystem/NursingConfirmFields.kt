package com.lezi.babylog.designsystem

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardType
import com.lezi.babylog.core.model.NURSING_ORDER_CHOICES
import com.lezi.babylog.core.model.NursingConfirmField
import com.lezi.babylog.core.model.NursingConfirmInput

/** Shared nursing fields used by ordinary Record Composer and timer completion. */
@Composable
fun LeziNursingConfirmFields(
    input: NursingConfirmInput,
    onInputChange: (NursingConfirmInput) -> Unit,
    modifier: Modifier = Modifier,
    highlightedField: NursingConfirmField? = null,
    enabled: Boolean = true,
) {
    val durationFocus = remember { FocusRequester() }
    val orderFocus = remember { FocusRequester() }
    val amountFocus = remember { FocusRequester() }

    LaunchedEffect(highlightedField) {
        val target = when (highlightedField) {
            NursingConfirmField.Duration -> durationFocus
            NursingConfirmField.Order -> orderFocus
            NursingConfirmField.Amount -> amountFocus
            null -> null
        }
        if (target != null) runCatching { target.requestFocus() }
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(LeziSpacing.Md),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            // Sm (12dp) nearest named step to the prior 10.dp field gap (off-grid).
            horizontalArrangement = Arrangement.spacedBy(LeziSpacing.Sm),
        ) {
            NursingIntegerField(
                value = input.leftMinutes,
                label = "左侧（分钟）",
                maxDigits = 4,
                isError = highlightedField == NursingConfirmField.Duration,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(durationFocus),
                enabled = enabled,
                onValueChange = { onInputChange(input.copy(leftMinutes = it)) },
            )
            NursingIntegerField(
                value = input.rightMinutes,
                label = "右侧（分钟）",
                maxDigits = 4,
                isError = highlightedField == NursingConfirmField.Duration,
                modifier = Modifier.weight(1f),
                enabled = enabled,
                onValueChange = { onInputChange(input.copy(rightMinutes = it)) },
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(LeziSpacing.Xxs)) {
            Text("喂养顺序", style = LeziTypography.Label)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(LeziSpacing.Xs),
            ) {
                NURSING_ORDER_CHOICES.forEachIndexed { index, (value, label) ->
                    LeziFilterChip(
                        selected = input.order == value,
                        onClick = { onInputChange(input.copy(order = value)) },
                        label = label,
                        enabled = enabled,
                        modifier = if (index == 0) {
                            Modifier.focusRequester(orderFocus)
                        } else {
                            Modifier
                        },
                    )
                }
            }
        }

        NursingIntegerField(
            value = input.amountMl,
            label = "奶量 ml（可选）",
            maxDigits = 3,
            isError = highlightedField == NursingConfirmField.Amount,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(amountFocus),
            enabled = enabled,
            onValueChange = { onInputChange(input.copy(amountMl = it)) },
        )
    }
}

@Composable
private fun NursingIntegerField(
    value: String,
    label: String,
    maxDigits: Int,
    isError: Boolean,
    modifier: Modifier,
    enabled: Boolean = true,
    onValueChange: (String) -> Unit,
) {
    LeziTextField(
        value = value,
        onValueChange = { onValueChange(it.filter(Char::isDigit).take(maxDigits)) },
        modifier = modifier,
        label = label,
        isError = isError,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
    )
}
