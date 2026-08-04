package com.lezi.babylog.feature.family.overview

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lezi.babylog.core.model.Baby
import com.lezi.babylog.core.ui.BabyAvatar
import com.lezi.babylog.core.ui.BabyAvatarSizeMedium
import com.lezi.babylog.designsystem.LeziIconButton
import com.lezi.babylog.designsystem.LeziSurfacePanel
import com.lezi.babylog.designsystem.LeziSecondaryButton
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziTextButton
import com.lezi.babylog.designsystem.LeziThemeExt
import com.lezi.babylog.designsystem.LeziTypography
import com.lezi.babylog.designsystem.SectionHeading
import com.lezi.babylog.designsystem.normalizeBabyThemeArgb
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.feature.family.components.canManageFamilyBabies

/**
 * Single baby roster on the account tab: one card per baby, current marked by
 * theme-color border only (no hero "当前宝宝" card, no menu baby section).
 *
 * - Tap card (non-current) → set current.
 * - Overflow ⋯ → edit/local dialog, merge, delete by role.
 */
@Composable
internal fun FamilyOverview(
    ui: AccountOverviewUi,
    onAddBaby: () -> Unit,
    onSetCurrent: (Long) -> Unit,
    onEditBaby: (Baby) -> Unit,
    onMergeBaby: (Baby) -> Unit,
    onDeleteBaby: (Baby) -> Unit,
) {
    val current = ui.current
    val canManage = canManageFamilyBabies(ui.role)
    val nickCounts = ui.babies.groupingBy { it.nickname.trim() }.eachCount()
    Column(verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm)) {
        SectionHeading(
            title = "宝宝",
            trailing = {
                if (canManage) {
                    LeziTextButton(label = "添加宝宝", onClick = onAddBaby)
                } else {
                    Text("家庭管理员管理", style = LeziTypography.Meta)
                }
            },
        )
        if (ui.role == FamilyRole.Member && ui.babies.isEmpty()) {
            Text(
                "等待家庭管理员添加宝宝",
                style = LeziTypography.Body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ui.babies.forEach { b ->
            val selected = b.id == current?.id
            val dup = (nickCounts[b.nickname.trim()] ?: 0) > 1
            BabyRosterCard(
                baby = b,
                selected = selected,
                meta = familyListBabyMeta(
                    birthdayEpochDay = b.birthdayEpochDay,
                    birthWeightGrams = b.birthWeightGrams,
                    nicknameDuplicate = dup,
                ),
                metaError = dup,
                canManage = canManage,
                showMerge = canManage && ui.babies.size > 1,
                onSelectCurrent = { if (!selected) onSetCurrent(b.id) },
                onOpenManage = { onEditBaby(b) },
                onMerge = { onMergeBaby(b) },
                onDelete = { onDeleteBaby(b) },
            )
        }
        if (ui.role == FamilyRole.Member && ui.localOrphanBabies.isNotEmpty()) {
            SectionHeading(title = "待并入的本机记录")
            Text(
                "这些宝宝档案仅用于定位加入家庭前的本机记录，不会上传为家庭宝宝。",
                style = LeziTypography.Meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ui.localOrphanBabies.forEach { orphan ->
                LeziSurfacePanel(modifier = Modifier.fillMaxWidth(), bottomBand = true) {
                    Text(orphan.nickname, style = LeziTypography.BodyStrong)
                    Text(
                        "本机孤宝宝档案",
                        style = LeziTypography.Meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(LeziSpacing.Xs))
                    if (ui.babies.isNotEmpty()) {
                        LeziSecondaryButton(
                            "并入家庭宝宝",
                            onClick = { onMergeBaby(orphan) },
                        )
                    } else {
                        Text(
                            "等待家庭管理员添加宝宝后可并入",
                            style = LeziTypography.Meta,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BabyRosterCard(
    baby: Baby,
    selected: Boolean,
    meta: String,
    metaError: Boolean,
    canManage: Boolean,
    showMerge: Boolean,
    onSelectCurrent: () -> Unit,
    onOpenManage: () -> Unit,
    onMerge: () -> Unit,
    onDelete: () -> Unit,
) {
    val themeArgb = normalizeBabyThemeArgb(baby.themeColorArgb)
    val cardShape = LeziThemeExt.cardShape
    var menuOpen by remember(baby.id) { mutableStateOf(false) }
    val manageLabel = if (canManage) "编辑" else "本机"
    LeziSurfacePanel(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (selected) {
                    Modifier.border(
                        width = 2.dp,
                        color = Color(themeArgb),
                        shape = cardShape,
                    )
                } else {
                    Modifier
                },
            )
            .semantics { this.selected = selected }
            .clickable(
                role = Role.Button,
                onClickLabel = if (selected) {
                    "${baby.nickname}，当前宝宝"
                } else {
                    "将${baby.nickname}设为当前宝宝"
                },
                onClick = onSelectCurrent,
            ),
        bottomBand = true,
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BabyAvatar(
                nickname = baby.nickname,
                avatarPath = baby.avatarPath,
                fallbackBackground = Color(themeArgb),
                fallbackStyle = LeziTypography.TitleSm,
                modifier = Modifier.size(BabyAvatarSizeMedium),
                borderWidth = 2.dp,
                avatarContentDescription = baby.nickname
                    .takeIf { it.isNotBlank() }
                    ?.let { "$it 的头像" },
            )
            Spacer(Modifier.size(LeziSpacing.Sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    baby.nickname,
                    style = LeziTypography.BodyStrong,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    meta,
                    style = LeziTypography.Meta,
                    color = if (metaError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box {
                LeziIconButton(
                    onClick = { menuOpen = true },
                    contentDescription = "${baby.nickname}的更多操作",
                ) {
                    Icon(Icons.Default.MoreVert, contentDescription = null)
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(manageLabel) },
                        onClick = {
                            menuOpen = false
                            onOpenManage()
                        },
                    )
                    if (showMerge) {
                        DropdownMenuItem(
                            text = { Text("合并") },
                            onClick = {
                                menuOpen = false
                                onMerge()
                            },
                        )
                    }
                    if (canManage && showMerge) {
                        DropdownMenuItem(
                            text = { Text("删除") },
                            onClick = {
                                menuOpen = false
                                onDelete()
                            },
                        )
                    }
                }
            }
        }
    }
}
