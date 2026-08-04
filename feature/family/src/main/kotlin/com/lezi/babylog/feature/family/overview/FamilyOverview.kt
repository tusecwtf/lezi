package com.lezi.babylog.feature.family.overview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lezi.babylog.core.model.Baby
import com.lezi.babylog.core.ui.BabyAvatar
import com.lezi.babylog.core.ui.BabyAvatarSizeLarge
import com.lezi.babylog.core.ui.BabyAvatarSizeMedium
import com.lezi.babylog.core.ui.babyMetaLine
import com.lezi.babylog.designsystem.LeziSurfacePanel
import com.lezi.babylog.designsystem.LeziSecondaryButton
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziTypography
import com.lezi.babylog.designsystem.SectionHeading
import com.lezi.babylog.designsystem.LeziTextButton
import com.lezi.babylog.domain.carelog.babyAgeLabel
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.feature.family.components.FamilyDestructiveButton
import com.lezi.babylog.feature.family.components.canManageFamilyBabies

/**
 * Independent baby zone on the account Tab. Device ID / storage / network
 * summaries intentionally stay off this surface (S1 / ticket 03).
 */
@OptIn(ExperimentalLayoutApi::class)
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
        val currentMeta = current?.let { baby ->
            val sex = when (baby.sex?.name) {
                "MALE" -> "男宝"
                "FEMALE" -> "女宝"
                else -> ""
            }
            listOfNotNull(
                babyMetaLine(baby.birthdayEpochDay, baby.birthWeightGrams),
                sex.takeIf { it.isNotBlank() },
                babyAgeLabel(baby.birthdayEpochDay).takeIf { it.isNotBlank() },
            ).joinToString(" · ")
        }.orEmpty()
        BabyProfileCard(
            nickname = current?.nickname.orEmpty(),
            themeColorArgb = current?.themeColorArgb,
            avatarPath = current?.avatarPath,
            avatarSize = BabyAvatarSizeLarge,
            avatarBorderWidth = 3.dp,
            fallbackStyle = LeziTypography.Title,
            eyebrow = "当前宝宝",
            title = current?.nickname ?: "—",
            titleStyle = LeziTypography.TitleSm,
            meta = currentMeta,
            buttons = current?.let {
                {
                    if (canManage) {
                        LeziSecondaryButton("编辑", onClick = { onEditBaby(it) })
                    }
                    if (ui.babies.size > 1) {
                        LeziSecondaryButton("切换", onClick = {
                            val idx = ui.babies.indexOfFirst { b -> b.id == it.id }
                                .takeIf { i -> i >= 0 } ?: 0
                            val next = ui.babies[(idx + 1) % ui.babies.size]
                            onSetCurrent(next.id)
                        })
                    }
                }
            },
        )

        SectionHeading(
            title = "宝宝档案",
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
            BabyProfileCard(
                nickname = b.nickname,
                themeColorArgb = b.themeColorArgb,
                avatarPath = b.avatarPath,
                avatarSize = BabyAvatarSizeMedium,
                avatarBorderWidth = 2.dp,
                fallbackStyle = LeziTypography.TitleSm,
                title = b.nickname + if (selected) "（当前）" else "",
                titleStyle = LeziTypography.BodyStrong,
                meta = "${babyAgeLabel(b.birthdayEpochDay)} · " +
                    babyMetaLine(b.birthdayEpochDay, b.birthWeightGrams) +
                    if (dup) " · 昵称重复" else "",
                metaColor = if (dup) MaterialTheme.colorScheme.error else null,
            ) {
                if (!selected) {
                    LeziSecondaryButton("设为当前", onClick = { onSetCurrent(b.id) })
                }
                if (canManage) {
                    LeziSecondaryButton("编辑", onClick = { onEditBaby(b) })
                }
                if (canManage && ui.babies.size > 1) {
                    FamilyDestructiveButton("合并", onClick = { onMergeBaby(b) })
                    FamilyDestructiveButton("删除", onClick = { onDeleteBaby(b) })
                }
            }
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

/**
 * Shared baby profile card skeleton (当前宝宝大卡与档案列表卡): avatar + title
 * + meta, with an optional button FlowRow underneath.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BabyProfileCard(
    nickname: String,
    themeColorArgb: Int?,
    avatarPath: String?,
    avatarSize: Dp,
    avatarBorderWidth: Dp,
    fallbackStyle: TextStyle,
    title: String,
    titleStyle: TextStyle,
    meta: String,
    modifier: Modifier = Modifier,
    eyebrow: String? = null,
    metaColor: Color? = null,
    buttons: (@Composable () -> Unit)? = null,
) {
    LeziSurfacePanel(modifier = modifier.fillMaxWidth(), bottomBand = true) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BabyAvatar(
                nickname = nickname,
                avatarPath = avatarPath,
                fallbackBackground = themeColorArgb?.let { Color(it) }
                    ?: MaterialTheme.colorScheme.primary,
                fallbackStyle = fallbackStyle,
                modifier = Modifier.size(avatarSize),
                borderWidth = avatarBorderWidth,
                avatarContentDescription = nickname
                    .takeIf { it.isNotBlank() }
                    ?.let { "$it 的头像" },
            )
            Spacer(Modifier.size(LeziSpacing.Sm))
            Column(modifier = Modifier.weight(1f)) {
                if (eyebrow != null) {
                    Text(
                        eyebrow,
                        style = LeziTypography.Meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    title,
                    style = titleStyle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    meta,
                    style = LeziTypography.Meta,
                    color = metaColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (buttons != null) {
            Spacer(Modifier.height(LeziSpacing.Sm))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(LeziSpacing.Xs),
                verticalArrangement = Arrangement.spacedBy(LeziSpacing.Xs),
            ) {
                buttons()
            }
        }
    }
}
