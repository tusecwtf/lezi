package com.lezi.babylog

import android.content.Intent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.lezi.babylog.feature.settings.calendar.CarePlanReminderReceiver
import com.lezi.babylog.feature.widget.WidgetComposerContract
import com.lezi.babylog.feature.widget.WidgetComposerTarget

/** An external Intent has only enough authority to ask the user for confirmation. */
internal sealed interface UntrustedExternalNavigation {
    data class WidgetComposer(
        val target: WidgetComposerTarget,
    ) : UntrustedExternalNavigation

    data class Fulfill(
        val target: PendingFulfillPlan,
    ) : UntrustedExternalNavigation
}

/** Navigation capability minted only by an explicit confirmation action in the UI. */
internal sealed interface AuthorizedExternalNavigation {
    data class WidgetComposer(
        val target: WidgetComposerTarget,
    ) : AuthorizedExternalNavigation

    data class Fulfill(
        val target: PendingFulfillPlan,
    ) : AuthorizedExternalNavigation
}

internal fun authorizeExternalNavigation(
    request: UntrustedExternalNavigation,
    userConfirmed: Boolean,
): AuthorizedExternalNavigation? {
    if (!userConfirmed) return null
    return when (request) {
        is UntrustedExternalNavigation.WidgetComposer ->
            AuthorizedExternalNavigation.WidgetComposer(request.target)
        is UntrustedExternalNavigation.Fulfill ->
            AuthorizedExternalNavigation.Fulfill(request.target)
    }
}

internal data class FulfillIntentSnapshot(
    val action: String?,
    val scheme: String?,
    val host: String?,
    val pathSegments: List<String>,
    val planIdExtra: Long?,
    val clientUuidExtra: String?,
)

/**
 * Recognizes only the two canonical care-plan entry points. Both results remain untrusted:
 * a BROWSABLE system-calendar URI or this app's own notification content Intent.
 */
internal fun decodeUntrustedFulfill(
    snapshot: FulfillIntentSnapshot,
): PendingFulfillPlan? {
    val browsableUuid = snapshot.pathSegments.singleOrNull()
    if (
        snapshot.action == Intent.ACTION_VIEW &&
        snapshot.scheme == "lezi" &&
        snapshot.host == "care-plan" &&
        !browsableUuid.isNullOrBlank() &&
        snapshot.planIdExtra == null &&
        snapshot.clientUuidExtra == null
    ) {
        return PendingFulfillPlan(planId = null, clientUuid = browsableUuid)
    }

    val notificationPlanId = snapshot.pathSegments
        .takeIf { it.size == 3 && it[0] == "care-plan" && it[2] == "fulfill" }
        ?.get(1)
        ?.toLongOrNull()
    val extraPlanId = snapshot.planIdExtra
    val extraClientUuid = snapshot.clientUuidExtra
    if (
        snapshot.action == Intent.ACTION_MAIN &&
        snapshot.scheme == "lezi" &&
        snapshot.host == "local-reminder" &&
        notificationPlanId != null &&
        notificationPlanId > 0L &&
        notificationPlanId == extraPlanId &&
        !extraClientUuid.isNullOrBlank()
    ) {
        return PendingFulfillPlan(
            planId = notificationPlanId,
            clientUuid = extraClientUuid,
        )
    }
    return null
}

internal fun parseUntrustedExternalNavigation(
    intent: Intent?,
): UntrustedExternalNavigation? {
    if (intent == null) return null
    val widget = WidgetComposerContract.parseUntrusted(intent)
    val data = intent.data
    val fulfill = decodeUntrustedFulfill(
        FulfillIntentSnapshot(
            action = intent.action,
            scheme = data?.scheme,
            host = data?.host,
            pathSegments = data?.pathSegments.orEmpty(),
            planIdExtra = intent
                .takeIf { it.hasExtra(CarePlanReminderReceiver.EXTRA_FULFILL_PLAN_ID) }
                ?.getLongExtra(CarePlanReminderReceiver.EXTRA_FULFILL_PLAN_ID, 0L),
            clientUuidExtra = intent
                .takeIf { it.hasExtra(CarePlanReminderReceiver.EXTRA_FULFILL_PLAN_UUID) }
                ?.getStringExtra(CarePlanReminderReceiver.EXTRA_FULFILL_PLAN_UUID),
        ),
    )
    return when {
        widget != null && fulfill == null ->
            UntrustedExternalNavigation.WidgetComposer(widget)
        fulfill != null && widget == null ->
            UntrustedExternalNavigation.Fulfill(fulfill)
        else -> null
    }
}

internal const val UNTRUSTED_NAVIGATION_DIALOG_TAG = "untrusted_navigation_dialog"
internal const val UNTRUSTED_NAVIGATION_CONFIRM_TAG = "untrusted_navigation_confirm"
internal const val UNTRUSTED_NAVIGATION_CANCEL_TAG = "untrusted_navigation_cancel"

@Composable
internal fun UntrustedExternalNavigationConfirmationDialog(
    request: UntrustedExternalNavigation,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val (title, body) = when (request) {
        is UntrustedExternalNavigation.WidgetComposer -> Pair(
            "打开记录面板？",
            "此请求来自应用外部。继续后将切换宝宝并打开预填记录面板；仍需在面板中确认保存。",
        )
        is UntrustedExternalNavigation.Fulfill -> Pair(
            "打开护理计划？",
            "此请求来自应用外部。继续后将打开护理计划的完成确认面板；确认保存前不会生成记录。",
        )
    }
    AlertDialog(
        modifier = Modifier.testTag(UNTRUSTED_NAVIGATION_DIALOG_TAG),
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(
                modifier = Modifier.testTag(UNTRUSTED_NAVIGATION_CONFIRM_TAG),
                onClick = onConfirm,
            ) {
                Text("继续")
            }
        },
        dismissButton = {
            TextButton(
                modifier = Modifier.testTag(UNTRUSTED_NAVIGATION_CANCEL_TAG),
                onClick = onDismiss,
            ) {
                Text("取消")
            }
        },
    )
}
