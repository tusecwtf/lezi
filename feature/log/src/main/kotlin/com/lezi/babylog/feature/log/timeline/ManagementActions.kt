package com.lezi.babylog.feature.log.timeline
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.lezi.babylog.feature.log.*
import com.lezi.babylog.feature.log.dock.*
import com.lezi.babylog.feature.log.composer.*
import com.lezi.babylog.feature.log.layout.*
import com.lezi.babylog.feature.log.photo.*

internal enum class ManagementActionKind(
    val busyMessage: String,
    val successMessage: String,
    val failureMessage: String,
) {
    SkipPlan(
        busyMessage = "正在跳过护理计划",
        successMessage = "已跳过护理计划",
        failureMessage = "跳过失败，请重试",
    ),
    DeletePlan(
        busyMessage = "正在删除护理计划",
        successMessage = "已删除护理计划",
        failureMessage = "删除失败，请重试",
    ),
    DeleteRecord(
        busyMessage = "正在删除记录",
        successMessage = "已删除记录",
        failureMessage = "删除失败，请重试",
    ),
}

internal data class ManagementActionRequest(
    val kind: ManagementActionKind,
    val targetId: Long,
) {
    init {
        require(targetId > 0L) { "management target id must be positive" }
    }
}

internal sealed interface ManagementActionState {
    data object Idle : ManagementActionState

    data class Running(val request: ManagementActionRequest) : ManagementActionState

    data class Failed(
        val request: ManagementActionRequest,
        val message: String,
    ) : ManagementActionState
}

internal data class ManagementActionTransition(
    val state: ManagementActionState,
    val accepted: Boolean,
    val announcement: String? = null,
)

/** Starts one operation and rejects every duplicate while an operation is in flight. */
internal fun beginManagementAction(
    state: ManagementActionState,
    request: ManagementActionRequest,
): ManagementActionTransition = if (state is ManagementActionState.Running) {
    ManagementActionTransition(state = state, accepted = false)
} else {
    ManagementActionTransition(
        state = ManagementActionState.Running(request),
        accepted = true,
        announcement = request.kind.busyMessage,
    )
}

/** Applies only the matching completion, so a stale callback cannot close newer UI. */
internal fun finishManagementAction(
    state: ManagementActionState,
    request: ManagementActionRequest,
    result: Result<String>,
): ManagementActionTransition {
    if (state != ManagementActionState.Running(request)) {
        return ManagementActionTransition(state = state, accepted = false)
    }
    return result.fold(
        onSuccess = { message ->
            val announcement = message.trim().ifEmpty { request.kind.successMessage }
            ManagementActionTransition(
                state = ManagementActionState.Idle,
                accepted = true,
                announcement = announcement,
            )
        },
        onFailure = { error ->
            val message = error.message?.trim().takeUnless { it.isNullOrEmpty() }
                ?: request.kind.failureMessage
            ManagementActionTransition(
                state = ManagementActionState.Failed(request, message),
                accepted = true,
                announcement = message,
            )
        },
    )
}

/** Permission-exact non-gesture actions attached to the same row callbacks as swipe/touch. */
internal fun rowManagementCustomActions(
    targetLabel: String,
    canEdit: Boolean,
    canDelete: Boolean,
    canSkip: Boolean = false,
    skipEnabled: Boolean = true,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onSkip: () -> Boolean = { false },
): List<CustomAccessibilityAction> = buildList {
    if (canEdit) {
        add(
            CustomAccessibilityAction("编辑$targetLabel") {
                onEdit()
                true
            },
        )
    }
    if (canDelete) {
        add(
            CustomAccessibilityAction("删除$targetLabel") {
                onDelete()
                true
            },
        )
    }
    if (canSkip && skipEnabled) {
        add(CustomAccessibilityAction("跳过$targetLabel", onSkip))
    }
}

internal fun Modifier.managementActions(
    actions: List<CustomAccessibilityAction>,
): Modifier = semantics { customActions = actions }

internal fun managementActionFeedback(
    state: ManagementActionState,
    request: ManagementActionRequest,
): String? = when (state) {
    ManagementActionState.Idle -> null
    is ManagementActionState.Running -> state.request
        .takeIf { it == request }
        ?.kind
        ?.busyMessage
    is ManagementActionState.Failed -> state.message.takeIf { state.request == request }
}

/** Visible and politely announced feedback; callers keep success in the app Snackbar host. */
@Composable
internal fun ManagementActionFeedback(
    message: String,
    isError: Boolean,
    modifier: Modifier = Modifier,
) {
    Text(
        text = message,
        modifier = modifier.semantics {
            liveRegion = LiveRegionMode.Polite
            stateDescription = message
        },
        color = if (isError) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        style = MaterialTheme.typography.bodySmall,
    )
}
