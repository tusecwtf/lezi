package com.lezi.babylog.feature.family.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

@Composable
internal fun FamilyMessageDialog(copy: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("提示") },
        text = { Text(copy) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } },
    )
}
