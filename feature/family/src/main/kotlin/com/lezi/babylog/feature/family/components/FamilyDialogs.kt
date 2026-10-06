package com.lezi.babylog.feature.family.components

import com.lezi.babylog.designsystem.LeziAlertDialog
import com.lezi.babylog.designsystem.LeziTextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
internal fun FamilyMessageDialog(copy: String, onDismiss: () -> Unit) {
    LeziAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("提示") },
        text = { Text(copy) },
        confirmButton = { LeziTextButton(label = "知道了", onClick = onDismiss) },
    )
}
