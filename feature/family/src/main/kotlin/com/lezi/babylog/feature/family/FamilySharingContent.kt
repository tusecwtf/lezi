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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
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
 * Network ops (host/SSID/sync/leave/delete) live in the network settings sheet.
 * Full member list opens from the member-count entry (secondary surface).
 */
@Composable
internal fun FamilySharingContent(
    ui: FamilyUi,
    controls: FamilyControlVisibility,
    primary: FamilyPrimarySurface,
    networkConfigured: Boolean,
    onOpenMembers: () -> Unit,
    onOpenNetwork: () -> Unit,
    onCreateFamily: () -> Unit,
    onJoinFamily: () -> Unit,
    onCreateInvite: () -> Unit,
    onEditMyDisplayName: () -> Unit = {},
    onRenameFamily: () -> Unit = {},
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
                if (card.showRenameFamily) {
                    TextButton(onClick = onRenameFamily) { Text("改名") }
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
                TextButton(onClick = onEditMyDisplayName) { Text("改称呼") }
            }
            Spacer(Modifier.height(LeziSpacing.Sm))
            FamilyMemberRosterEntry(
                label = card.memberCountLabel,
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
            statusLabel = card.syncStatusLabel,
            isError = ui.status == com.lezi.babylog.core.model.SyncStatus.Error,
            onOpenNetwork = onOpenNetwork,
        )
        Text(
            "点同步状态查看网络设置",
            style = LeziTypography.Meta,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    LeziSecondaryButton(
        if (primary.compactJoined) "网络设置" else "家庭网络设置",
        onClick = onOpenNetwork,
        modifier = Modifier.fillMaxWidth(),
    )

    // Unjoined: wizard CTAs only (scan lives inside join wizard). Owner: 邀请家人 on overview.
    if (primary.showCreateJoin) {
        if (controls.showCreateFamily) {
            LeziPrimaryButton(
                primary.createLabel,
                onClick = onCreateFamily,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (controls.showJoin) {
            LeziSecondaryButton(
                primary.joinLabel,
                onClick = onJoinFamily,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    if (primary.showInvite) {
        LeziPrimaryButton(
            primary.inviteLabel,
            onClick = onCreateInvite,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Spacer(Modifier.height(LeziSpacing.Xxl))
}

@Composable
internal fun FamilyMemberRosterEntry(
    label: String,
    onOpenMembers: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Text(
        label,
        style = LeziTypography.BodyStrong,
        color = MaterialTheme.colorScheme.primary,
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
                contentDescription = "家人名单：$label"
                traversalIndex = 0f
            },
    )
}

@Composable
internal fun FamilySyncStatusEntry(
    statusLabel: String,
    isError: Boolean,
    onOpenNetwork: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Text(
        statusLabel,
        style = LeziTypography.BodyStrong,
        color = if (isError) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = LeziSpacing.Touch)
            .clickable(
                onClickLabel = "打开网络设置",
                role = Role.Button,
                onClick = onOpenNetwork,
            )
            .padding(vertical = LeziSpacing.Xs)
            .semantics {
                contentDescription = "同步状态：$statusLabel"
                traversalIndex = 1f
            },
    )
}
