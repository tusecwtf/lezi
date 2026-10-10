package com.lezi.babylog.feature.family.baby

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.lezi.babylog.designsystem.LeziAlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lezi.babylog.core.model.Baby
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziTextButton
import com.lezi.babylog.designsystem.LeziTextButtonTone
import com.lezi.babylog.designsystem.LeziSecondaryButton
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
    currentBabyId: Long?,
    canEditProfile: Boolean,
    canEditAvatar: Boolean,
    createBusy: Boolean,
    onDismiss: () -> Unit,
    onDelete: (Long) -> Unit,
    onPreviewMerge: (Long, Long) -> Unit,
    onMerge: (BabyMergePreview) -> Unit,
    onUpdate: (Baby, BabyProfileUpdate, onFinished: () -> Unit) -> Unit,
    onCreate: (
        nickname: String,
        sex: String?,
        birthdayEpochDay: Long,
        birthWeightGrams: Int?,
        themeColorArgb: Int,
        avatarJpeg: ByteArray?,
        onFinished: (String?) -> Unit,
    ) -> Unit,
    onLocalTheme: (babyId: Long, argb: Int, onDone: (String?) -> Unit) -> Unit,
    onMoveLocal: (babyId: Long, delta: Int, onDone: (String?) -> Unit) -> Unit,
    onSetCurrent: (Long) -> Unit,
    destructiveBusy: Boolean = false,
    createError: String? = null,
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
        is FamilyDialog.EditBaby -> {
            val baby = dialog.baby
            val position = babies.indexOfFirst { it.id == baby.id }
            BabyEditDialog(
                baby = baby,
                canEditProfile = canEditProfile,
                canEditAvatar = canEditAvatar,
                isCurrent = baby.id == currentBabyId,
                canMoveEarlier = position > 0,
                canMoveLater = position in 0 until babies.lastIndex,
                onDismiss = onDismiss,
                onSaveProfile = { nickname, sex, birthday, grams, avatar, removeAvatar, onFinished ->
                    onUpdate(
                        baby,
                        BabyProfileUpdate(nickname, sex, birthday, grams, avatar, removeAvatar),
                        onFinished,
                    )
                },
                onLocalTheme = { argb, onDone -> onLocalTheme(baby.id, argb, onDone) },
                onMoveLocal = { delta, onDone -> onMoveLocal(baby.id, delta, onDone) },
                onSetCurrent = { onSetCurrent(baby.id) },
            )
        }
        is FamilyDialog.AddBaby -> BabyCreateDialog(
            busy = createBusy,
            error = createError,
            onDismiss = onDismiss,
            onCreate = onCreate,
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
            LeziTextButton(label = presentation.label, onClick = { onDelete(baby.id) }, enabled = presentation.enabled, tone = LeziTextButtonTone.Destructive)
        },
        dismissButton = {
            LeziTextButton(label = "取消", onClick = onDismiss, enabled = presentation.dismissible)
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
                    LeziSecondaryButton(label = "保留「${target.nickname}」", onClick = { onPreview(source.id, target.id) }, modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {},
        dismissButton = { LeziTextButton(label = "取消", onClick = onDismiss) },
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
            LeziTextButton(label = presentation.label, onClick = { onMerge(preview) }, enabled = presentation.enabled, tone = LeziTextButtonTone.Destructive)
        },
        dismissButton = {
            LeziTextButton(label = "取消", onClick = onDismiss, enabled = presentation.dismissible)
        },
    )
}

internal fun mergeDataSummary(preview: BabyMergePreview): String =
    "${preview.recordCount} 条记录 · ${preview.carePlanCount} 个护理计划"
