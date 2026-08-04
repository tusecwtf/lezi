package com.lezi.babylog.feature.family.overview

import androidx.compose.runtime.Composable
import com.lezi.babylog.core.ui.AppUpdateOutcomeDialogs
import com.lezi.babylog.sync.appupdate.AppUpdateUiOutcome

/**
 * App-update secondary dialogs owned by the account overview host.
 *
 * Thin adapter over the shared [AppUpdateOutcomeDialogs]: the overview host does
 * not track inline install feedback or the forced unknown-sources hand-off, so
 * those stay off here; the root force shell remains authoritative.
 */
@Composable
internal fun OverviewAppUpdateDialogs(
    host: AccountOverviewHost,
    outcome: AppUpdateUiOutcome?,
    checkingAppUpdate: Boolean,
    installingAppUpdate: Boolean,
) {
    AppUpdateOutcomeDialogs(
        outcome = outcome,
        checkingAppUpdate = checkingAppUpdate,
        installingAppUpdate = installingAppUpdate,
        installFeedback = null,
        forcedInstallPermissionRequired = false,
        onDismissOutcome = host::dismissAppUpdateOutcome,
        onInstallUpdate = host::installOptionalUpdate,
        onRetryCheck = host::checkAppUpdate,
        onForcedInstallPermissionOpened = {},
    )
}
