package com.lezi.babylog.designsystem

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.DatePickerDialog as MaterialDatePickerDialog

/**
 * Product text field — one outlined style with [LeziTypography] labels.
 * Material [OutlinedTextField] stays inside designsystem only.
 */
@Composable
fun LeziTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    isError: Boolean = false,
    label: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
    supportingText: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    colors: TextFieldColors = OutlinedTextFieldDefaults.colors(),
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        readOnly = readOnly,
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        isError = isError,
        label = label,
        placeholder = placeholder,
        supportingText = supportingText,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        keyboardOptions = keyboardOptions,
        visualTransformation = visualTransformation,
        colors = colors,
        textStyle = LeziTypography.Body,
        shape = LeziThemeExt.controlShape,
    )
}

/** String-label convenience for the common form field. */
@Composable
fun LeziTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    isError: Boolean = false,
    placeholder: String? = null,
    supportingText: String? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    colors: TextFieldColors = OutlinedTextFieldDefaults.colors(),
) {
    LeziTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        readOnly = readOnly,
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        isError = isError,
        label = { Text(label, style = LeziTypography.Label) },
        placeholder = placeholder?.let { { Text(it, style = LeziTypography.Body) } },
        supportingText = supportingText?.let { { Text(it, style = LeziTypography.Meta) } },
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        keyboardOptions = keyboardOptions,
        visualTransformation = visualTransformation,
        colors = colors,
    )
}

/**
 * Colors for a text field that looks enabled while `enabled = false`
 * (read-only birthday pickers that still need a full-tap target).
 */
@Composable
fun leziReadOnlyTextFieldColors(): TextFieldColors =
    OutlinedTextFieldDefaults.colors(
        disabledTextColor = androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
        disabledBorderColor = androidx.compose.material3.MaterialTheme.colorScheme.outline,
        disabledLabelColor = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
        disabledPlaceholderColor = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
    )

/** Product switch with tokenized colors; Material [Switch] stays in designsystem. */
@Composable
fun LeziSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedTrackColor = androidx.compose.material3.MaterialTheme.colorScheme.primary,
        ),
    )
}

/**
 * Date-picker dialog shell: confirm/dismiss use [LeziTextButton] so product
 * sites never wire raw Material dialog buttons. Put [LeziDatePicker] in [content].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LeziDatePickerDialog(
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    confirmLabel: String = "确定",
    dismissLabel: String = "取消",
    confirmEnabled: Boolean = true,
    dismissEnabled: Boolean = true,
    properties: DialogProperties = DialogProperties(),
    content: @Composable () -> Unit,
) {
    MaterialDatePickerDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            LeziTextButton(
                label = confirmLabel,
                onClick = onConfirm,
                enabled = confirmEnabled,
                tone = LeziTextButtonTone.Primary,
            )
        },
        dismissButton = {
            LeziTextButton(
                label = dismissLabel,
                onClick = onDismissRequest,
                enabled = dismissEnabled,
            )
        },
        modifier = modifier.fillMaxWidth(),
        properties = properties,
    ) {
        content()
    }
}
