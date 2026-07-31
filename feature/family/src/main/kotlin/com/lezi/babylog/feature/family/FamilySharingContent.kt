package com.lezi.babylog.feature.family

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.unit.dp
import com.lezi.babylog.designsystem.LeziSurfacePanel
import com.lezi.babylog.designsystem.LeziPrimaryButton
import com.lezi.babylog.designsystem.LeziSecondaryButton
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziTypography
import com.lezi.babylog.designsystem.SectionHeading
import com.lezi.babylog.sync.FamilyRole

internal fun familyMemberRosterMinimumTouchHeight() = LeziSpacing.Touch

/**
 * Account Tab family zone: overview family card + primary CTAs.
 * Network ops stay in the network sheet; device logout and member-only identity deletion
 * remain distinct Account-bottom actions.
 * Full member list opens from the member-count entry (secondary surface).
 */
@Composable
internal fun FamilySharingContent(
    ui: FamilyUi,
    primary: FamilyPrimarySurface,
    networkConfigured: Boolean,
    onOpenMembers: () -> Unit,
    onConnectFamily: () -> Unit,
    onScanMemberLoginQr: () -> Unit,
    onLogoutCurrentDevice: () -> Unit = {},
    onLeaveFamily: () -> Unit = {},
    onDeleteFamily: () -> Unit = {},
) {
    val visibleMembers = if (ui.enabled) {
        familyMembersForDisplay(
            ui.members,
            ui.displayName,
            ui.role,
            ui.membershipId,
            ui.membersLoaded,
        )
    } else {
        emptyList()
    }
    val card = buildFamilyOverviewCard(
        isJoined = ui.enabled,
        role = ui.role,
        networkConfigured = networkConfigured,
        familyName = ui.familyName,
        babyNickname = ui.current?.nickname,
        localDisplayName = ui.displayName,
        memberCount = visibleMembers.size,
        membersLoaded = ui.membersLoaded,
        status = ui.status,
    )

    SectionHeading(title = "我们家")
    LeziSurfacePanel(modifier = Modifier.fillMaxWidth(), bottomBand = true) {
        if (ui.enabled) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        card.familyNameLabel,
                        style = LeziTypography.TitleSm,
                    )
                    Text(
                        if (ui.role == FamilyRole.Owner) {
                            "共享家庭名 · 管理员可改"
                        } else {
                            "共享家庭名"
                        },
                        style = LeziTypography.Meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(LeziSpacing.Sm))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        card.selfTitle,
                        style = LeziTypography.BodyStrong,
                    )
                    Text(
                        "我的家庭称呼",
                        style = LeziTypography.Meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(LeziSpacing.Sm))
            FamilyMemberRosterEntry(
                label = card.memberCountLabel,
                pendingCount = ui.pendingMemberRequests.size.takeIf {
                    ui.role == FamilyRole.Owner
                } ?: 0,
                onOpenMembers = onOpenMembers,
            )
            Spacer(Modifier.height(LeziSpacing.Xs))
        } else {
            Text(
                card.familyNameLabel,
                style = LeziTypography.TitleSm,
            )
            Spacer(Modifier.height(LeziSpacing.Xs))
            Text(
                unjoinedFamilyCardSubtitle(),
                style = LeziTypography.Meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(LeziSpacing.Sm))
        }

        FamilySyncStatusEntry(
            statusLabel = if (ui.pendingMemberLogin != null) {
                "等待管理员确认"
            } else {
                card.syncStatusLabel
            },
            isError = ui.status == com.lezi.babylog.core.model.SyncStatus.Error,
            onOpenNetwork = null,
        )
        if (ui.enabled) {
            Text(
                formatLastSuccessAt(ui.lastSuccessAt),
                style = LeziTypography.Meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    // Unjoined: wizard CTAs only (scan lives inside join wizard). Owner: 邀请家人 on overview.
    if (primary.showCreateJoin) {
        LeziPrimaryButton(
            if (ui.pendingMemberLogin != null) "查看加入申请" else FamilyPrimaryCta.CONNECT,
            onClick = onConnectFamily,
            modifier = Modifier.fillMaxWidth(),
        )
        if (ui.pendingMemberLogin == null) {
            LeziSecondaryButton(
                "扫描成员登录二维码",
                onClick = onScanMemberLoginQr,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    if (ui.enabled) {
        Spacer(Modifier.height(LeziSpacing.Sm))
        LeziSecondaryButton(
            "退出这台设备",
            onClick = onLogoutCurrentDevice,
            modifier = Modifier.fillMaxWidth(),
        )
        if (ui.role == FamilyRole.Member) {
            Spacer(Modifier.height(LeziSpacing.Xs))
            LeziSecondaryButton(
                "退出家庭",
                onClick = onLeaveFamily,
                modifier = Modifier.fillMaxWidth(),
            )
        } else if (ui.role == FamilyRole.Owner) {
            Spacer(Modifier.height(LeziSpacing.Xs))
            LeziSecondaryButton(
                "删除家庭",
                onClick = onDeleteFamily,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    Spacer(Modifier.height(LeziSpacing.Xxl))
}

@Composable
internal fun FamilyMemberRosterEntry(
    label: String,
    pendingCount: Int = 0,
    onOpenMembers: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = familyMemberRosterMinimumTouchHeight())
            .clickable(
                onClickLabel = "打开家人名单",
                role = Role.Button,
                onClick = onOpenMembers,
            )
            .padding(vertical = LeziSpacing.Xs)
            .semantics {
                contentDescription = if (pendingCount > 0) {
                    "家庭成员与设备：$label，$pendingCount 个待确认设备"
                } else {
                    "家庭成员与设备：$label"
                }
                traversalIndex = 0f
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "家庭成员与设备 · $label",
            style = LeziTypography.BodyStrong,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        if (pendingCount > 0) {
            Surface(
                shape = androidx.compose.foundation.shape.CircleShape,
                color = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ) {
                Text(
                    pendingCount.coerceAtMost(99).toString(),
                    style = LeziTypography.Label,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
internal fun FamilySyncStatusEntry(
    statusLabel: String,
    isError: Boolean,
    onOpenNetwork: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val baseModifier = modifier
        .fillMaxWidth()
        .heightIn(min = LeziSpacing.Touch)
    val interactionModifier = if (onOpenNetwork == null) {
        baseModifier
    } else {
        baseModifier.clickable(
            onClickLabel = "打开网络设置",
            role = Role.Button,
            onClick = onOpenNetwork,
        )
    }
    Text(
        statusLabel,
        style = LeziTypography.BodyStrong,
        color = if (isError) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        modifier = interactionModifier
            .padding(vertical = LeziSpacing.Xs)
            .semantics {
                contentDescription = "同步状态：$statusLabel"
                traversalIndex = 1f
            },
    )
}
