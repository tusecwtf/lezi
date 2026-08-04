package com.lezi.babylog.designsystem

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.lezi.babylog.core.model.NextFeedPlanEffect
import com.lezi.babylog.core.model.NextFeedPlanEvent
import com.lezi.babylog.core.model.NextFeedPlanOrigin
import com.lezi.babylog.core.model.NextFeedPlanPhase
import com.lezi.babylog.core.model.NextFeedPlanReconciliation
import com.lezi.babylog.core.model.NextFeedPlanState
import com.lezi.babylog.core.model.reduceNextFeedPlan
import com.lezi.babylog.core.model.restoreNextFeedPlanState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The single post-fact next-feed interaction used by RecordComposer and nursing timer.
 * The fact is already durable; this surface owns only the optional CarePlan choice and retry.
 */
@Composable
fun LeziNextFeedPlanFlow(
    flowKey: String,
    origin: NextFeedPlanOrigin,
    factMessage: String,
    suggestedAtMillis: Long,
    scheduledMessage: String,
    minuteStep: Int,
    timePickerStyle: String,
    preferredHand: String,
    nowMillis: () -> Long = System::currentTimeMillis,
    onSchedule: (atMillis: Long, onResult: (Boolean) -> Unit) -> Unit,
    onReconcile: (onResult: (NextFeedPlanReconciliation) -> Unit) -> Unit,
    onFinishedScheduled: () -> Unit,
    onFinishedWithoutPlan: () -> Unit,
) {
    var state by rememberSaveable(flowKey, stateSaver = NextFeedPlanStateSaver) {
        androidx.compose.runtime.mutableStateOf(
            NextFeedPlanState.initial(suggestedAtMillis, origin),
        )
    }

    fun dispatch(event: NextFeedPlanEvent) {
        val transition = reduceNextFeedPlan(state, event)
        state = transition.state
        when (val effect = transition.effect) {
            is NextFeedPlanEffect.Schedule -> onSchedule(effect.atMillis) { success ->
                dispatch(
                    if (success) {
                        NextFeedPlanEvent.ScheduleSucceeded
                    } else {
                        NextFeedPlanEvent.ScheduleOutcomeUnknown
                    },
                )
            }
            NextFeedPlanEffect.Reconcile -> onReconcile { result ->
                dispatch(NextFeedPlanEvent.ReconciliationCompleted(result))
            }
            NextFeedPlanEffect.FinishScheduled -> onFinishedScheduled()
            NextFeedPlanEffect.FinishWithoutPlan -> onFinishedWithoutPlan()
            null -> Unit
        }
    }

    LaunchedEffect(flowKey, state.phase) {
        if (state.phase == NextFeedPlanPhase.ReconciliationRequired) {
            dispatch(NextFeedPlanEvent.Reconcile)
        }
    }

    // Phase transitions crossfade dialog content instead of hard-swapping windows.
    AnimatedContent(
        targetState = state.phase,
        transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(120)) },
        label = "nextFeedPlanPhase",
    ) { phase ->
        when (phase) {
        NextFeedPlanPhase.EditingTime -> LeziClockDialDialog(
            title = "选择下次喂养时间",
            value = Instant.ofEpochMilli(state.selectedAtMillis)
                .atZone(ZoneId.systemDefault()),
            minuteStep = minuteStep,
            timePickerStyle = timePickerStyle,
            preferredHand = preferredHand,
            onConfirm = { picked ->
                dispatch(
                    NextFeedPlanEvent.TimeSelected(
                        atMillis = picked.toInstant().toEpochMilli(),
                        nowMillis = nowMillis(),
                    ),
                )
            },
            onDismiss = { dispatch(NextFeedPlanEvent.TimeEditCancelled) },
        )
        NextFeedPlanPhase.Scheduled -> LeziAlertDialog(
            onDismissRequest = {},
            title = { Text("已安排下次喂养") },
            text = { Text(scheduledMessage, style = LeziTypography.Body) },
            confirmButton = {
                LeziTextButton(
                    label = "完成",
                    onClick = { dispatch(NextFeedPlanEvent.AcknowledgeScheduled) },
                    tone = LeziTextButtonTone.Primary,
                )
            },
        )
        NextFeedPlanPhase.Skipped -> Unit
        NextFeedPlanPhase.ReconciliationRequired,
        NextFeedPlanPhase.Reconciling,
        -> LeziAlertDialog(
            onDismissRequest = {},
            title = { Text("正在核对下次喂养") },
            text = { Text("记录已保存；正在确认护理计划是否已经写入。", style = LeziTypography.Body) },
            confirmButton = {
                LeziTextButton(
                    label = "正在核对…",
                    onClick = {},
                    enabled = false,
                    tone = LeziTextButtonTone.Primary,
                )
            },
        )
        NextFeedPlanPhase.ReconciliationFailed -> LeziAlertDialog(
            onDismissRequest = {},
            title = { Text("无法确认下次喂养") },
            text = {
                Text(state.scheduleError ?: "持久化状态暂时不可用，请重新核对。")
            },
            confirmButton = {
                LeziTextButton(
                    label = "重新核对",
                    onClick = { dispatch(NextFeedPlanEvent.Reconcile) },
                    tone = LeziTextButtonTone.Primary,
                )
            },
        )
        else -> {
            val scheduling = state.phase == NextFeedPlanPhase.Scheduling
            LeziAlertDialog(
                onDismissRequest = {},
                title = { Text("安排下次喂养？") },
                text = {
                    Column {
                        Text("$factMessage。你可以创建家庭护理计划，或选择不安排。")
                        Text(
                            "计划时间：${formatNextFeedTime(state.selectedAtMillis)}",
                            style = LeziTypography.BodyStrong,
                        )
                        state.validationError?.let {
                            Text(it, color = MaterialTheme.colorScheme.error)
                        }
                        state.scheduleError?.let {
                            Text(
                                "记录已保存；$it",
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                },
                confirmButton = {
                    LeziTextButton(
                        label = if (scheduling) "正在安排…" else "确认安排",
                        onClick = {
                            dispatch(NextFeedPlanEvent.Schedule(nowMillis()))
                        },
                        enabled = !scheduling,
                        tone = LeziTextButtonTone.Primary,
                    )
                },
                dismissButton = {
                    // Stack secondaries so dual Touch-height actions do not crowd one
                    // Material dismiss slot (matches other LeziAlertDialog hierarchies).
                    Column(horizontalAlignment = Alignment.End) {
                        LeziTextButton(
                            label = "调整时间",
                            onClick = { dispatch(NextFeedPlanEvent.EditTime) },
                            enabled = !scheduling,
                        )
                        LeziTextButton(
                            label = "不安排",
                            onClick = { dispatch(NextFeedPlanEvent.Skip) },
                            enabled = !scheduling,
                        )
                    }
                },
            )
        }
        }
    }
}

fun nextFeedPlanSuccessMessage(notificationPermissionGranted: Boolean): String =
    if (notificationPermissionGranted) {
        "护理计划已加入乐记日程"
    } else {
        "护理计划已加入乐记日程；通知权限未开启，本机提醒已降级"
    }

private fun formatNextFeedTime(epochMillis: Long): String = Instant.ofEpochMilli(epochMillis)
    .atZone(ZoneId.systemDefault())
    .format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))

private val NextFeedPlanStateSaver: Saver<NextFeedPlanState, Any> =
    listSaver(
        save = { state ->
            listOf(
                state.origin.name,
                state.suggestedAtMillis,
                state.selectedAtMillis,
                state.phase.name,
                state.validationError.orEmpty(),
                state.scheduleError.orEmpty(),
            )
        },
        restore = { saved ->
            restoreNextFeedPlanState(
                NextFeedPlanState(
                    origin = NextFeedPlanOrigin.valueOf(saved[0] as String),
                    suggestedAtMillis = saved[1] as Long,
                    selectedAtMillis = saved[2] as Long,
                    phase = NextFeedPlanPhase.valueOf(saved[3] as String),
                    validationError = (saved[4] as String).ifBlank { null },
                    scheduleError = (saved[5] as String).ifBlank { null },
                ),
            )
        },
    )
