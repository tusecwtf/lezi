package com.lezi.babylog.feature.log.composer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable

@Composable
internal fun rememberRecordComposerDiscardPrompt(requestKey: Any?): MutableState<Boolean> =
    rememberSaveable(requestKey) { mutableStateOf(false) }

@Composable
internal fun RecordComposerDiscardDialog(
    busy: Boolean,
    onContinueEditing: () -> Unit,
    onDiscard: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onContinueEditing() },
        title = { Text("放弃未保存的更改？") },
        text = { Text("当前修改尚未保存。放弃后会关闭编辑，并清理本草稿新导入的照片。") },
        dismissButton = {
            TextButton(
                enabled = !busy,
                onClick = onContinueEditing,
            ) {
                Text("继续编辑")
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = onDiscard,
            ) {
                Text("放弃", color = MaterialTheme.colorScheme.error)
            }
        },
    )
}
