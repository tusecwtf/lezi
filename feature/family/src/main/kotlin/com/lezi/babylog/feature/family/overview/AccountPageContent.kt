package com.lezi.babylog.feature.family.overview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.lezi.babylog.core.model.Baby
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziTypography
import com.lezi.babylog.designsystem.PageHero
import com.lezi.babylog.feature.family.components.FamilyPrimarySurface
import com.lezi.babylog.feature.family.members.MembersDevicesUi
import com.lezi.babylog.sync.AppUpdateMetadata

internal data class AccountBabyActions(
    val add: () -> Unit = {},
    val setCurrent: (Long) -> Unit = {},
    val edit: (Baby) -> Unit = {},
    val merge: (Baby) -> Unit = {},
    val delete: (Baby) -> Unit = {},
)

internal data class AccountFamilySectionActions(
    val openMembers: () -> Unit = {},
    val connect: () -> Unit = {},
    val openOptionalAppUpdate: (AppUpdateMetadata) -> Unit = {},
    val dismissOptionalAppUpdate: (versionCode: Int) -> Unit = {},
    val openConflictInbox: () -> Unit = {},
    val explainSyncFailure: () -> Unit = {},
)

internal data class AccountBottomActions(
    val openNetworkSettings: () -> Unit = {},
    val logoutCurrentDevice: () -> Unit = {},
    val leaveFamily: () -> Unit = {},
    val deleteFamily: () -> Unit = {},
)

/** Product ordering for the Account overview, kept beside its read model and sections. */
@Composable
internal fun AccountPageContent(
    overview: AccountOverviewUi,
    members: MembersDevicesUi,
    primary: FamilyPrimarySurface,
    endpointConfigured: Boolean,
    babyActions: AccountBabyActions = AccountBabyActions(),
    familyActions: AccountFamilySectionActions = AccountFamilySectionActions(),
    bottomActions: AccountBottomActions = AccountBottomActions(),
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm),
    ) {
        PageHero(eyebrow = "", title = "账户")
        if (!overview.hydrated) {
            Text(
                "正在读取账户…",
                style = LeziTypography.Meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        FamilySharingContent(
            overview = overview,
            members = members,
            primary = primary,
            endpointConfigured = endpointConfigured,
            onOpenMembers = familyActions.openMembers,
            onConnectFamily = familyActions.connect,
            onOpenOptionalAppUpdate = familyActions.openOptionalAppUpdate,
            onDismissOptionalAppUpdate = familyActions.dismissOptionalAppUpdate,
            onOpenConflictInbox = familyActions.openConflictInbox,
            onExplainSyncFailure = familyActions.explainSyncFailure,
        )
        FamilyOverview(
            ui = overview,
            onAddBaby = babyActions.add,
            onSetCurrent = babyActions.setCurrent,
            onEditBaby = babyActions.edit,
            onMergeBaby = babyActions.merge,
            onDeleteBaby = babyActions.delete,
        )
        FamilyAccountActions(
            overview = overview,
            onOpenNetworkSettings = bottomActions.openNetworkSettings,
            onLogoutCurrentDevice = bottomActions.logoutCurrentDevice,
            onLeaveFamily = bottomActions.leaveFamily,
            onDeleteFamily = bottomActions.deleteFamily,
        )
    }
}
