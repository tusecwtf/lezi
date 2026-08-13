package com.lezi.babylog.feature.family.overview

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.lezi.babylog.designsystem.LeziSurfacePanel
import com.lezi.babylog.designsystem.LeziDestructiveButton
import com.lezi.babylog.designsystem.LeziPrimaryButton
import com.lezi.babylog.designsystem.LeziSecondaryButton
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziTypography
import com.lezi.babylog.feature.family.components.FamilyPrimaryCta
import com.lezi.babylog.feature.family.components.FamilyPrimarySurface
import com.lezi.babylog.feature.family.components.buildFamilyOverviewCard
import com.lezi.babylog.feature.family.components.familyMembersForDisplay
import com.lezi.babylog.feature.family.components.familyNameSupportingCopy
import com.lezi.babylog.feature.family.components.familyRosterEntryPresentation
import com.lezi.babylog.feature.family.members.MembersDevicesUi
import com.lezi.babylog.sync.AppUpdateMetadata
import com.lezi.babylog.sync.session.ShallowSyncState
import com.lezi.babylog.sync.session.FamilyRole
internal fun familyMemberRosterMinimumTouchHeight() = LeziSpacing.Touch

internal data class FamilyAccountConnectionPresentation(
    val showFamilyContext: Boolean,
    val showRoster: Boolean,
    val primaryCtaLabel: String?,
)

internal fun familyAccountConnectionPresentation(
    isJoined: Boolean,
    retainedFamilyIdentity: Boolean,
    shallowState: ShallowSyncState,
    waitingForApproval: Boolean,
    showCreateJoin: Boolean,
): FamilyAccountConnectionPresentation {
    val requiresReauth = shallowState == ShallowSyncState.ReauthRequired
    return FamilyAccountConnectionPresentation(
        showFamilyContext = isJoined || (requiresReauth && retainedFamilyIdentity),
        showRoster = isJoined,
        primaryCtaLabel = when {
            isJoined -> null
            requiresReauth -> "重新登录或申请"
            waitingForApproval -> "查看加入申请"
            showCreateJoin -> FamilyPrimaryCta.CONNECT
            else -> null
        },
    )
}

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
    onOpenConflictInbox: () -> Unit = {},
) {
    val connection = familyAccountConnectionPresentation(
        isJoined = overview.enabled,
        retainedFamilyIdentity = overview.retainedFamilyIdentity,
        shallowState = overview.shallowSyncLine.state,
        waitingForApproval = overview.pendingMemberLogin != null,
        showCreateJoin = primary.showCreateJoin,
    )
    val visibleMembers = if (connection.showRoster) {
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
        isJoined = connection.showFamilyContext,
        role = overview.role,
        endpointConfigured = endpointConfigured,
        familyName = overview.familyName,
        babyNickname = overview.current?.nickname,
        localDisplayName = overview.displayName,
        memberCount = visibleMembers.size,
        membersLoaded = members.membersLoaded,
        membersLoading = members.membersLoading,
        membersError = members.membersError,
        status = overview.status,
        lastSuccessAt = overview.lastSuccessAt,
        waitingForApproval = overview.pendingMemberLogin != null,
    )

    LeziSurfacePanel(modifier = Modifier.fillMaxWidth(), bottomBand = true) {
        if (connection.showFamilyContext) {
            FamilyCardInfoRow(
                title = card.familyNameLabel,
                titleStyle = LeziTypography.TitleSm,
                meta = familyNameSupportingCopy(overview.role),
            )
            Spacer(Modifier.height(LeziSpacing.Sm))
            FamilyCardInfoRow(
                title = card.selfTitle,
                titleStyle = LeziTypography.BodyStrong,
                meta = "我的家庭称呼",
            )
            Spacer(Modifier.height(LeziSpacing.Sm))
            if (connection.showRoster) {
                FamilyMemberRosterEntry(
                    label = card.memberCountLabel,
                    isError = familyRosterEntryPresentation(
                        visibleCount = visibleMembers.size,
                        loaded = members.membersLoaded,
                        loading = members.membersLoading,
                        error = members.membersError,
                    ).isError,
                    loginRequestCount = members.pendingMemberRequests.size.takeIf {
                        overview.role == FamilyRole.Owner
                    } ?: 0,
                    onOpenMembers = onOpenMembers,
                )
                Spacer(Modifier.height(LeziSpacing.Sm))
            }
        } else {
            Text(
                card.familyNameLabel,
                style = LeziTypography.TitleSm,
            )
            Spacer(Modifier.height(LeziSpacing.Sm))
        }

        FamilySyncStatusEntry(
            statusLabel = overview.shallowSyncLine.text,
            isError = overview.shallowSyncLine.state in setOf(
                ShallowSyncState.Error,
                ShallowSyncState.ReauthRequired,
            ),
            modifier = Modifier.testTag("account_shallow_sync_status"),
            openConflictCount = overview.openConflictCount,
            isJoined = overview.enabled,
            onOpenConflictInbox = onOpenConflictInbox,
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
    connection.primaryCtaLabel?.let { ctaLabel ->
        LeziPrimaryButton(
            ctaLabel,
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
        LeziDestructiveButton(
            "退出这台设备",
            onClick = onLogoutCurrentDevice,
            modifier = Modifier.fillMaxWidth(),
        )
        if (overview.role == FamilyRole.Member) {
            Spacer(Modifier.height(LeziSpacing.Xs))
            LeziDestructiveButton(
                "退出家庭",
                onClick = onLeaveFamily,
                modifier = Modifier.fillMaxWidth(),
            )
        } else if (overview.role == FamilyRole.Owner) {
            Spacer(Modifier.height(LeziSpacing.Xs))
            LeziDestructiveButton(
                "删除家庭",
                onClick = onDeleteFamily,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    Spacer(Modifier.height(LeziSpacing.Xxl))
}

/** Title + meta info row shared by the family card's 家庭名/称呼 lines. */
@Composable
private fun FamilyCardInfoRow(
    title: String,
    titleStyle: TextStyle,
    meta: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = titleStyle)
            Text(
                meta,
                style = LeziTypography.Meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun FamilyMemberRosterEntry(
    label: String,
    isError: Boolean = false,
    loginRequestCount: Int = 0,
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
                contentDescription = if (loginRequestCount > 0) {
                    "家庭成员与设备：$label，$loginRequestCount 个设备登录申请"
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
            color = if (isError) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.primary
            },
            modifier = Modifier.weight(1f),
        )
        if (loginRequestCount > 0) {
            Surface(
                shape = androidx.compose.foundation.shape.CircleShape,
                color = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ) {
                Text(
                    loginRequestCount.coerceAtMost(99).toString(),
                    style = LeziTypography.Label,
                    modifier = Modifier.padding(
                        horizontal = LeziSpacing.Xs,
                        vertical = LeziSpacing.Xxs,
                    ),
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
    openConflictCount: Int = 0,
    isJoined: Boolean = false,
    onOpenConflictInbox: () -> Unit = {},
) {
    val conflict = familyConflictBadgePresentation(isJoined, openConflictCount)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            statusLabel,
            style = LeziTypography.Meta,
            color = if (isError) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = modifier.weight(1f),
        )
        if (conflict.visible) {
            Surface(
                modifier = Modifier
                    .heightIn(min = LeziSpacing.Touch)
                    .clickable(
                        role = Role.Button,
                        onClickLabel = "打开冲突收件箱",
                        onClick = onOpenConflictInbox,
                    )
                    .semantics { contentDescription = conflict.contentDescription }
                    .testTag("family_conflict_inbox_badge"),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ) {
                Text(
                    "冲突 ${conflict.badgeText}",
                    style = LeziTypography.Label,
                    modifier = Modifier.padding(horizontal = LeziSpacing.Sm),
                )
            }
        }
    }
}

internal data class FamilyConflictBadgePresentation(
    val visible: Boolean,
    val badgeText: String,
    val contentDescription: String,
)

internal fun familyConflictBadgePresentation(
    isJoined: Boolean,
    openRootCount: Int,
): FamilyConflictBadgePresentation {
    val count = openRootCount.coerceAtLeast(0)
    return FamilyConflictBadgePresentation(
        visible = isJoined && count > 0,
        badgeText = if (count > 99) "99+" else count.toString(),
        contentDescription = "家庭同步冲突，${count}项待处理，打开冲突收件箱",
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
                    .heightIn(min = LeziSpacing.Touch)
                    .clickable(
                        onClickLabel = "稍后提醒",
                        role = Role.Button,
                        onClick = onDismiss,
                    )
                    .padding(start = LeziSpacing.Sm, top = LeziSpacing.Xs, bottom = LeziSpacing.Xs)
                    .wrapContentHeight(Alignment.CenterVertically),
            )
        }
    }
}
