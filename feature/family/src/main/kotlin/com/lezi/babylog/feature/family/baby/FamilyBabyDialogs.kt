package com.lezi.babylog.feature.family.baby

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.lezi.babylog.designsystem.LeziAlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lezi.babylog.core.model.Baby
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.domain.BabyMergePreview
import com.lezi.babylog.feature.family.components.FamilyDialog
import com.lezi.babylog.feature.family.components.FamilyDestructiveAction
import com.lezi.babylog.feature.family.components.FamilyScopeRow
import com.lezi.babylog.feature.family.components.familyDestructiveConfirmPresentation

internal data class BabyProfileUpdate(
    val nickname: String,
    val sex: String?,
    val birthdayEpochDay: Long,
    val birthWeightGrams: Int?,
    val avatarJpeg: ByteArray?,
    val removeAvatar: Boolean,
)

@Composable
internal fun FamilyBabyDialog(
    dialog: FamilyDialog,
    babies: List<Baby>,
    canEditAvatar: Boolean,
    onDismiss: () -> Unit,
    onDelete: (Long) -> Unit,
    onPreviewMerge: (Long, Long) -> Unit,
    onMerge: (BabyMergePreview) -> Unit,
    onUpdate: (Baby, BabyProfileUpdate, onFinished: () -> Unit) -> Unit,
    destructiveBusy: Boolean = false,
) {
    when (dialog) {
        is FamilyDialog.DeleteBaby -> DeleteBabyDialog(
            baby = dialog.baby,
            busy = destructiveBusy,
            onDelete = onDelete,
            onDismiss = onDismiss,
        )
        is FamilyDialog.MergeBaby -> MergeBabyDialog(
            source = dialog.source,
            babies = babies,
            onPreview = onPreviewMerge,
            onDismiss = onDismiss,
        )
        is FamilyDialog.MergePreview -> MergePreviewDialog(
            preview = dialog.preview,
            busy = destructiveBusy,
            onMerge = onMerge,
            onDismiss = onDismiss,
        )
        is FamilyDialog.EditBaby -> BabyEditDialog(
            baby = dialog.baby,
            canEditAvatar = canEditAvatar,
            onDismiss = onDismiss,
            onSave = { nickname, sex, birthday, grams, avatar, removeAvatar, onFinished ->
                onUpdate(
                    dialog.baby,
                    BabyProfileUpdate(nickname, sex, birthday, grams, avatar, removeAvatar),
                    onFinished,
                )
            },
        )
        else -> Unit
    }
}

@Composable
private fun DeleteBabyDialog(
    baby: Baby,
    busy: Boolean,
    onDelete: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val presentation = familyDestructiveConfirmPresentation(
        FamilyDestructiveAction.DeleteBaby,
        busy,
    )
    LeziAlertDialog(
        onDismissRequest = { if (presentation.dismissible) onDismiss() },
        title = { Text("删除「${baby.nickname}」？") },
        text = { Text("删除后该档案不可恢复。记录仍会留在本机但不再出现在当前宝宝视图中。") },
        confirmButton = {
            TextButton(
                onClick = { onDelete(baby.id) },
                enabled = presentation.enabled,
            ) {
                Text(presentation.label, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = presentation.dismissible) { Text("取消") }
        },
    )
}

@Composable
private fun MergeBabyDialog(
    source: Baby,
    babies: List<Baby>,
    onPreview: (Long, Long) -> Unit,
    onDismiss: () -> Unit,
) {
    LeziAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("把「${source.nickname}」合并到…") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = LeziSpacing.DialogContentMax).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                babies.filter { it.id != source.id }.forEach { target ->
                    OutlinedButton(
                        onClick = { onPreview(source.id, target.id) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            "保留「${target.nickname}」",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun MergePreviewDialog(
    preview: BabyMergePreview,
    busy: Boolean,
    onMerge: (BabyMergePreview) -> Unit,
    onDismiss: () -> Unit,
) {
    val presentation = familyDestructiveConfirmPresentation(
        FamilyDestructiveAction.MergeBaby,
        busy,
    )
    LeziAlertDialog(
        onDismissRequest = { if (presentation.dismissible) onDismiss() },
        title = { Text("确认合并宝宝档案？") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(LeziSpacing.Xs)) {
                FamilyScopeRow("来源", "移出档案", preview.sourceNickname)
                FamilyScopeRow("保留", "目标档案", preview.targetNickname)
                FamilyScopeRow(
                    "迁移",
                    "关联数据",
                    mergeDataSummary(preview),
                )
                Text("操作不可撤销", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onMerge(preview) },
                enabled = presentation.enabled,
            ) {
                Text(presentation.label, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = presentation.dismissible) { Text("取消") }
        },
    )
}

internal fun mergeDataSummary(preview: BabyMergePreview): String =
    "${preview.recordCount} 条记录 · ${preview.carePlanCount} 个护理计划"
