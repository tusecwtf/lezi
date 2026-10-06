package com.lezi.babylog.designsystem

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.DialogProperties

/**
 * AlertDialog with the template dialog chrome (shape + tonal elevation) applied,
 * so call sites stop drifting between the M3 default 28dp corner and the
 * warm(8)/journal(18) `dialogShape` token. Prefer this over raw [AlertDialog];
 * behavior and slot API are otherwise identical.
 */
@Composable
fun LeziAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    properties: DialogProperties = DialogProperties(),
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        modifier = modifier,
        dismissButton = dismissButton,
        icon = icon,
        title = title,
        text = text,
        shape = LeziThemeExt.dialogShape,
        containerColor = containerColor,
        tonalElevation = LeziThemeExt.modalElevation,
        properties = properties,
    )
}
