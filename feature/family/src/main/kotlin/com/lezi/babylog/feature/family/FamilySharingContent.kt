package com.lezi.babylog.feature.family

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.lezi.babylog.core.model.SyncStatus
import com.lezi.babylog.designsystem.LeziCard
import com.lezi.babylog.designsystem.LeziPrimaryButton
import com.lezi.babylog.designsystem.LeziSecondaryButton
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziTypography
import com.lezi.babylog.designsystem.SectionHeading
import com.lezi.babylog.sync.FamilyRole
import com.lezi.babylog.sync.PUBLIC_CLEARTEXT_WARNING
import com.lezi.babylog.sync.isPublicCleartextBaseUrl

@Composable
internal fun FamilySharingContent(
    ui: FamilyUi,
    controls: FamilyControlVisibility,
    primary: FamilyPrimarySurface,
    networkConfigured: Boolean,
    savedSummaryBaseUrl: String,
    onRefreshMembers: () -> Unit,
    onOpenNetwork: () -> Unit,
    onCreateFamily: () -> Unit,
    onJoinFamily: () -> Unit,
    onScanInvite: () -> Unit,
    onCreateInvite: () -> Unit,
    onSync: () -> Unit,
    onLeave: () -> Unit,
    onDeleteFamily: () -> Unit,
) {
    SectionHeading(title = "家人一起记")
    LeziCard(modifier = Modifier.fillMaxWidth()) {
        Text("家庭同步", style = LeziTypography.BodyStrong)
        Spacer(Modifier.height(LeziSpacing.Sm))
        FamilyGuideRow(
            step = "1",
            title = "家庭网络",
            detail = if (networkConfigured) {
                buildString {
                    append(ui.allowedSsids.joinToString(" / "))
                    if (savedSummaryBaseUrl.isNotBlank()) append(" · $savedSummaryBaseUrl")
                }
            } else {
                "待设置服务器与 Wi-Fi"
            },
            complete = networkConfigured,
        )
        FamilyGuideRow(
            step = "2",
            title = "家庭身份",
            detail = if (ui.enabled) "已加入 · ${familyRoleLabel(ui.role)}" else "待新建或加入",
            complete = ui.enabled,
        )
        FamilyGuideRow(
            step = "3",
            title = "同步状态",
            detail = compactSyncStatusLabel(ui.status, ui.enabled) +
                (ui.lastSuccessAt?.let {
                    " · ${java.text.DateFormat.getDateTimeInstance().format(it)}"
                } ?: ""),
            complete = ui.enabled && ui.status == SyncStatus.Idle,
        )
        if (savedSummaryBaseUrl.isNotBlank() && isPublicCleartextBaseUrl(savedSummaryBaseUrl)) {
            Spacer(Modifier.height(LeziSpacing.Xs))
            Text(
                PUBLIC_CLEARTEXT_WARNING,
                style = LeziTypography.Meta,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }

    if (ui.enabled) {
        val visibleMembers = familyMembersForDisplay(
            ui.members,
            ui.displayName,
            ui.role,
            ui.membersLoaded,
        )
        LeziCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("共享中的成员", style = LeziTypography.BodyStrong)
                    Text(
                        familyMemberSummary(visibleMembers.size, ui.role, ui.membersLoaded),
                        style = LeziTypography.Meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onRefreshMembers, enabled = !ui.membersLoading) {
                    Text(if (ui.membersLoading) "刷新中…" else "刷新")
                }
            }
            Spacer(Modifier.height(LeziSpacing.Xs))
            visibleMembers.forEach { FamilyMemberRow(it) }
            ui.membersError?.let { error ->
                Text(
                    error,
                    style = LeziTypography.Meta,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = LeziSpacing.Xs),
                )
            } ?: if (!ui.membersLoaded && !ui.membersLoading) {
                Text(
                    "连接家庭 Wi-Fi 后刷新完整列表",
                    style = LeziTypography.Meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = LeziSpacing.Xs),
                )
            } else Unit
        }
    }

    LeziSecondaryButton(
        if (primary.compactJoined) "网络设置" else "家庭网络设置",
        onClick = onOpenNetwork,
        modifier = Modifier.fillMaxWidth(),
    )

    if (primary.showCreateJoin) {
        if (controls.showCreateFamily) {
            LeziPrimaryButton("新建家庭", onClick = onCreateFamily, modifier = Modifier.fillMaxWidth())
        }
        if (controls.showJoin) {
            LeziSecondaryButton("输入邀请码", onClick = onJoinFamily, modifier = Modifier.fillMaxWidth())
            LeziSecondaryButton("扫码加入", onClick = onScanInvite, modifier = Modifier.fillMaxWidth())
        }
    }
    if (primary.showInvite) {
        LeziPrimaryButton("生成邀请二维码", onClick = onCreateInvite, modifier = Modifier.fillMaxWidth())
    }
    if (primary.showJoinedActions) {
        LeziSecondaryButton("立即同步", onClick = onSync, modifier = Modifier.fillMaxWidth())
        if (primary.showLeave) {
            LeziSecondaryButton("离开家庭", onClick = onLeave, modifier = Modifier.fillMaxWidth())
        }
        if (ui.role == FamilyRole.Owner) {
            LeziSecondaryButton(
                "删除家庭数据",
                onClick = onDeleteFamily,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    Spacer(Modifier.height(LeziSpacing.Xxl))
}
