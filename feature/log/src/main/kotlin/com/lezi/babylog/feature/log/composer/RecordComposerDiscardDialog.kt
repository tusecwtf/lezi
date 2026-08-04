package com.lezi.babylog.feature.log.composer
import com.lezi.babylog.designsystem.LeziAlertDialog
import com.lezi.babylog.designsystem.LeziTextButton
import com.lezi.babylog.designsystem.LeziTextButtonTone
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
    LeziAlertDialog(
        onDismissRequest = { if (!busy) onContinueEditing() },
        title = { Text("放弃未保存的更改？") },
        text = { Text("当前修改尚未保存。放弃后会关闭编辑，并清理本草稿新导入的照片。") },
        dismissButton = {
            LeziTextButton(label = "继续编辑", onClick = onContinueEditing, enabled = !busy)
        },
        confirmButton = {
            LeziTextButton(label = "放弃", onClick = onDiscard, tone = LeziTextButtonTone.Destructive, enabled = !busy)
        },
    )
}
