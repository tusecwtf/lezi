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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lezi.babylog.core.model.Baby
import com.lezi.babylog.core.ui.BabyAvatar
import com.lezi.babylog.designsystem.LeziSurfacePanel
import com.lezi.babylog.designsystem.LeziSecondaryButton
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziTypography
import com.lezi.babylog.designsystem.SectionHeading
import com.lezi.babylog.domain.carelog.babyAgeLabel
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.feature.family.components.canManageFamilyBabies
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

internal fun formatBirthWeightKg(grams: Int): String =
    if (grams % 1000 == 0) {
        "${grams / 1000}kg"
    } else {
        String.format(Locale.ROOT, "%.2fkg", grams / 1000.0)
    }

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
        LeziSurfacePanel(modifier = Modifier.fillMaxWidth(), bottomBand = true) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val accent = current?.themeColorArgb?.let { Color(it) }
                        ?: MaterialTheme.colorScheme.primary
                    BabyAvatar(
                        nickname = current?.nickname.orEmpty(),
                        avatarPath = current?.avatarPath,
                        fallbackBackground = accent,
                        fallbackStyle = LeziTypography.Title,
                        modifier = Modifier.size(56.dp),
                        borderWidth = 3.dp,
                        avatarContentDescription = current?.let { "${it.nickname}的头像" },
                    )
                    Spacer(Modifier.size(LeziSpacing.Sm))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("当前宝宝", style = LeziTypography.Meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            current?.nickname ?: "—",
                            style = LeziTypography.TitleSm,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val age = current?.let { babyAgeLabel(it.birthdayEpochDay) }.orEmpty()
                        val sex = when (current?.sex?.name) {
                            "MALE" -> "男宝"
                            "FEMALE" -> "女宝"
                            else -> ""
                        }
                        val birth = current?.let {
                            LocalDate.ofEpochDay(it.birthdayEpochDay).toString()
                        }.orEmpty()
                        val weight = current?.birthWeightGrams
                            ?.let(::formatBirthWeightKg)
                            .orEmpty()
                        Text(
                            listOfNotNull(
                                birth.takeIf { it.isNotBlank() }?.let { "${it}出生" },
                                weight.takeIf { it.isNotBlank() }?.let { "出生体重 $it" },
                                sex.takeIf { it.isNotBlank() },
                                age.takeIf { it.isNotBlank() },
                            ).joinToString(" · "),
                            style = LeziTypography.Meta,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (current != null) {
                Spacer(Modifier.height(LeziSpacing.Sm))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (canManage) {
                        LeziSecondaryButton("编辑", onClick = { onEditBaby(current) })
                    }
                    if (ui.babies.size > 1) {
                        LeziSecondaryButton("切换", onClick = {
                            val cur = ui.current?.id
                            val idx = ui.babies.indexOfFirst { it.id == cur }.takeIf { it >= 0 } ?: 0
                            val next = ui.babies[(idx + 1) % ui.babies.size]
                            onSetCurrent(next.id)
                        })
                    }
                }
            }
        }

        SectionHeading(
            title = "宝宝档案",
            trailing = {
                if (canManage) {
                    TextButton(onClick = onAddBaby) { Text("添加宝宝") }
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
            LeziSurfacePanel(modifier = Modifier.fillMaxWidth(), bottomBand = true) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f),
                    ) {
                        BabyAvatar(
                            nickname = b.nickname,
                            avatarPath = b.avatarPath,
                            fallbackBackground = Color(b.themeColorArgb),
                            fallbackStyle = LeziTypography.TitleSm,
                            modifier = Modifier.size(40.dp),
                            borderWidth = 2.dp,
                            avatarContentDescription = "${b.nickname}的头像",
                        )
                        Spacer(Modifier.size(LeziSpacing.Sm))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                b.nickname + if (selected) "（当前）" else "",
                                style = LeziTypography.BodyStrong,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val birth = LocalDate.ofEpochDay(b.birthdayEpochDay)
                                .format(DateTimeFormatter.ofPattern("yyyy年M月d日"))
                            val weight = b.birthWeightGrams?.let { " · 出生 ${it}g" }.orEmpty()
                            Text(
                                "${babyAgeLabel(b.birthdayEpochDay)} · $birth$weight" +
                                    if (dup) " · 昵称重复" else "",
                                style = LeziTypography.Meta,
                                color = if (dup) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(LeziSpacing.Sm))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (!selected) {
                        LeziSecondaryButton("设为当前", onClick = { onSetCurrent(b.id) })
                    }
                    if (canManage) {
                        LeziSecondaryButton("编辑", onClick = { onEditBaby(b) })
                    }
                    if (canManage && ui.babies.size > 1) {
                        LeziSecondaryButton("合并", onClick = { onMergeBaby(b) })
                        LeziSecondaryButton("删除", onClick = { onDeleteBaby(b) })
                    }
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
