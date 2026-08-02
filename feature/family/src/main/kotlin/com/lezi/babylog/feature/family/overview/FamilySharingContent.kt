package com.lezi.babylog.feature.family.overview

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
import com.lezi.babylog.feature.family.components.FamilyPrimaryCta
import com.lezi.babylog.feature.family.components.FamilyPrimarySurface
import com.lezi.babylog.feature.family.components.buildFamilyOverviewCard
import com.lezi.babylog.feature.family.components.familyMembersForDisplay
import com.lezi.babylog.feature.family.members.MembersDevicesUi
import com.lezi.babylog.sync.AppUpdateMetadata
import com.lezi.babylog.sync.session.FamilyRole
internal fun familyMemberRosterMinimumTouchHeight() = LeziSpacing.Touch

/**
 * Account Tab family zone: overview family card + primary CTAs.
 * Endpoint maintenance opens the independent family network settings page. Device logout and
 * member-only identity deletion remain distinct Account-bottom actions.
 * Full member list opens from the member-count entry (secondary surface).
 */
@Composable
internal fun FamilySharingContent(
    overview: AccountOverviewUi,
    members: MembersDevicesUi,
    primary: FamilyPrimarySurface,
    endpointConfigured: Boolean,
    onOpenMembers: () -> Unit,
    onConnectFamily: () -> Unit,
    onOpenOptionalAppUpdate: (AppUpdateMetadata) -> Unit = {},
    onDismissOptionalAppUpdate: (versionCode: Int) -> Unit = {},
) {
    val visibleMembers = if (overview.enabled) {
        familyMembersForDisplay(
            members.members,
            overview.displayName,
            overview.role,
            overview.membershipId,
            members.membersLoaded,
        )
    } else {
        emptyList()
    }
    val card = buildFamilyOverviewCard(
        isJoined = overview.enabled,
        role = overview.role,
        endpointConfigured = endpointConfigured,
        familyName = overview.familyName,
        babyNickname = overview.current?.nickname,
        localDisplayName = overview.displayName,
        memberCount = visibleMembers.size,
        membersLoaded = members.membersLoaded,
        status = overview.status,
        lastSuccessAt = overview.lastSuccessAt,
        waitingForApproval = overview.pendingMemberLogin != null,
    )

    LeziSurfacePanel(modifier = Modifier.fillMaxWidth(), bottomBand = true) {
        if (overview.enabled) {
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
                        if (overview.role == FamilyRole.Owner) {
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
                pendingCount = members.pendingMemberRequests.size.takeIf {
                    overview.role == FamilyRole.Owner
                } ?: 0,
                onOpenMembers = onOpenMembers,
            )
            Spacer(Modifier.height(LeziSpacing.Xs))
        } else {
            Text(
                card.familyNameLabel,
                style = LeziTypography.TitleSm,
            )
            Spacer(Modifier.height(LeziSpacing.Sm))
        }

        FamilySyncStatusEntry(
            statusLabel = card.syncStatusLabel,
            isError = overview.status == com.lezi.babylog.core.model.SyncStatus.Error,
        )
    }

    val optionalUpdate = overview.optionalAppUpdate
    if (overview.enabled && optionalUpdate != null) {
        OptionalAppUpdateBanner(
            versionName = optionalUpdate.versionName,
            onOpen = { onOpenOptionalAppUpdate(optionalUpdate) },
            onDismiss = { onDismissOptionalAppUpdate(optionalUpdate.versionCode) },
        )
    }

    // Unauthenticated: enter the probe-driven family wizard from one primary action.
    if (primary.showCreateJoin) {
        LeziPrimaryButton(
            if (overview.pendingMemberLogin != null) "查看加入申请" else FamilyPrimaryCta.CONNECT,
            onClick = onConnectFamily,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Account-level operations stay after the independent baby zone. */
@Composable
internal fun FamilyAccountActions(
    overview: AccountOverviewUi,
    onOpenNetworkSettings: () -> Unit = {},
    onLogoutCurrentDevice: () -> Unit = {},
    onLeaveFamily: () -> Unit = {},
    onDeleteFamily: () -> Unit = {},
) {
    if (overview.enabled) {
        LeziSecondaryButton(
            "家庭网络设置",
            onClick = onOpenNetworkSettings,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(LeziSpacing.Xs))
        LeziSecondaryButton(
            "退出这台设备",
            onClick = onLogoutCurrentDevice,
            modifier = Modifier.fillMaxWidth(),
        )
        if (overview.role == FamilyRole.Member) {
            Spacer(Modifier.height(LeziSpacing.Xs))
            LeziSecondaryButton(
                "退出家庭",
                onClick = onLeaveFamily,
                modifier = Modifier.fillMaxWidth(),
            )
        } else if (overview.role == FamilyRole.Owner) {
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
    modifier: Modifier = Modifier,
) {
    Text(
        statusLabel,
        style = LeziTypography.Meta,
        color = if (isError) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * Non-blocking optional-update affordance next to, but outside, the account family card.
 * Style matches existing menu/account panel language (no second design system).
 */
@Composable
internal fun OptionalAppUpdateBanner(
    versionName: String,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = com.lezi.babylog.sync.appupdate.optionalAppUpdateBannerLabel(versionName)
    val description =
        com.lezi.babylog.sync.appupdate.optionalAppUpdateBannerContentDescription(versionName)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = LeziSpacing.Touch)
            .semantics {
                contentDescription = description
                traversalIndex = 1.5f
            },
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = LeziSpacing.Sm, vertical = LeziSpacing.Xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                style = LeziTypography.BodyStrong,
                modifier = Modifier
                    .weight(1f)
                    .clickable(
                        onClickLabel = "查看更新",
                        role = Role.Button,
                        onClick = onOpen,
                    )
                    .padding(vertical = LeziSpacing.Xs),
            )
            Text(
                "稍后",
                style = LeziTypography.Meta,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                modifier = Modifier
                    .clickable(
                        onClickLabel = "稍后提醒",
                        role = Role.Button,
                        onClick = onDismiss,
                    )
                    .padding(start = LeziSpacing.Sm, top = LeziSpacing.Xs, bottom = LeziSpacing.Xs),
            )
        }
    }
}
