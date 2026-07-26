package com.lezi.babylog.feature.timer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lezi.babylog.designsystem.LeziDetailTopBar
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimerRoute(
    onDone: () -> Unit,
    initialNote: String = "",
    initialAmountMl: String = "",
    /** When set, bind this open nursing care plan to the timer session (ticket 16). */
    carePlanId: Long? = null,
    babyId: Long? = null,
    vm: TimerViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(carePlanId, babyId) {
        vm.bindCarePlanIfIdle(carePlanId = carePlanId, babyId = babyId)
    }
    val timeStepMin by vm.timeStepMin.collectAsStateWithLifecycle()
    val timePickerStyle by vm.timePickerStyle.collectAsStateWithLifecycle()
    val preferredHand by vm.preferredHand.collectAsStateWithLifecycle()
    var tick by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val running = state.leftRunning || state.rightRunning
    LaunchedEffect(running) {
        tick = SystemClock.elapsedRealtime()
        while (running) {
            delay(200)
            tick = SystemClock.elapsedRealtime()
        }
    }
    val leftMs = state.leftMs(tick)
    val rightMs = state.rightMs(tick)
    var completionDraft by remember { mutableStateOf<NursingCompletionDraft?>(null) }
    var completionSaving by remember { mutableStateOf(false) }
    var completionSaveError by remember { mutableStateOf<String?>(null) }
    var showDiscardConfirmation by remember { mutableStateOf(false) }
    var savedAwaitingReminder by rememberSaveable { mutableStateOf(false) }
    var pendingReminderAt by rememberSaveable { mutableStateOf<Long?>(null) }
    var reminderScheduleError by rememberSaveable { mutableStateOf<String?>(null) }
    var reminderScheduling by remember { mutableStateOf(false) }
    fun finishReminderSchedule(success: Boolean) {
        reminderScheduling = false
        if (success) {
            savedAwaitingReminder = false
            reminderScheduleError = null
            onDone()
        } else {
            reminderScheduleError = "提醒设置失败，请重试或选择不提醒"
        }
    }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            reminderScheduling = false
            savedAwaitingReminder = false
            onDone()
        } else {
            vm.scheduleReminder(pendingReminderAt, ::finishReminderSchedule)
        }
    }
    fun requestOrSchedule(atMillis: Long?) {
        if (reminderScheduling) return
        pendingReminderAt = atMillis
        reminderScheduleError = null
        reminderScheduling = true
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            vm.scheduleReminder(atMillis, ::finishReminderSchedule)
        } else {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val completionSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    Scaffold(
        topBar = {
            LeziDetailTopBar(title = "喂奶计时", onBack = onDone)
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    when (state.lastSide) {
                        "L" -> "上次停在左侧"
                        "R" -> "上次停在右侧"
                        else -> "点左右大圆开始"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "合计 ${fmtMs(leftMs + rightMs)}",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            BoxWithConstraints(
                Modifier.fillMaxWidth(),
            ) {
                val buttonSize = ((maxWidth - 12.dp) / 2)
                    .coerceIn(120.dp, 148.dp)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SideButton(
                        label = "左",
                        time = fmtMs(leftMs),
                        running = state.leftRunning,
                        color = MaterialTheme.colorScheme.primary,
                        runningContentColor = MaterialTheme.colorScheme.onPrimary,
                        size = buttonSize,
                        onClick = vm::toggleLeft,
                    )
                    SideButton(
                        label = "右",
                        time = fmtMs(rightMs),
                        running = state.rightRunning,
                        color = MaterialTheme.colorScheme.tertiary,
                        runningContentColor = MaterialTheme.colorScheme.onTertiary,
                        size = buttonSize,
                        onClick = vm::toggleRight,
                    )
                }
            }

            Column(Modifier.fillMaxWidth()) {
                Button(
                    onClick = {
                        completionSaveError = null
                        completionDraft = vm.freezeCompletion(
                            initialNote = initialNote,
                            initialAmountMl = initialAmountMl,
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                ) { Text("完成并记录") }
                TextButton(
                    onClick = {
                        if (state.hasTimerData()) {
                            showDiscardConfirmation = true
                        } else {
                            vm.clear(onDone)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("丢弃") }
            }
        }
    }

    if (showDiscardConfirmation) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirmation = false },
            title = { Text("丢弃本次计时？") },
            text = { Text("已累计的喂奶计时将不会保存，此操作无法撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardConfirmation = false
                        vm.clear(onDone)
                    },
                ) {
                    Text(
                        "确认丢弃",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardConfirmation = false }) {
                    Text("继续计时")
                }
            },
        )
    }

    completionDraft?.let { draft ->
        ModalBottomSheet(
            onDismissRequest = {
                if (!completionSaving) {
                    completionDraft = null
                    completionSaveError = null
                }
            },
            sheetState = completionSheetState,
        ) {
            NursingCompletionSheet(
                draft = draft,
                saving = completionSaving,
                saveError = completionSaveError,
                timeStepMin = timeStepMin,
                timePickerStyle = timePickerStyle,
                preferredHand = preferredHand,
                onDraftChange = {
                    completionSaveError = null
                    completionDraft = it
                },
                onDismiss = {
                    if (!completionSaving) {
                        completionDraft = null
                        completionSaveError = null
                    }
                },
                onConfirm = { confirmed ->
                    completionSaving = true
                    completionSaveError = null
                    vm.complete(
                        draft = confirmed,
                        onDone = {
                            completionSaving = false
                            completionDraft = null
                            savedAwaitingReminder = true
                        },
                        onError = {
                            completionSaving = false
                            completionSaveError = it
                        },
                    )
                },
            )
        }
    }

    if (savedAwaitingReminder) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("设置下次喂养提醒？") },
            text = {
                Column {
                    Text("记录已保存。请选择提醒时间。")
                    reminderScheduleError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { requestOrSchedule(null) },
                    enabled = !reminderScheduling,
                ) {
                    Text(if (reminderScheduling) "正在设置…" else "确认提醒")
                }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = {
                            requestOrSchedule(System.currentTimeMillis() + 60 * 60_000L)
                        },
                        enabled = !reminderScheduling,
                    ) { Text("60 分钟") }
                    TextButton(
                        onClick = {
                            savedAwaitingReminder = false
                            onDone()
                        },
                        enabled = !reminderScheduling,
                    ) { Text("不提醒") }
                }
            },
        )
    }
}

@Composable
private fun SideButton(
    label: String,
    time: String,
    running: Boolean,
    color: Color,
    runningContentColor: Color,
    size: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(if (running) color else color.copy(alpha = 0.18f))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    label,
                    color = if (running) runningContentColor else color,
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    time,
                    color = if (running) runningContentColor else MaterialTheme.colorScheme.onBackground,
                    fontSize = if (time.length >= 6) 22.sp else 28.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
                Text(
                    if (running) "暂停" else "开始",
                    color = if (running) {
                        runningContentColor.copy(alpha = 0.9f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

private fun fmtMs(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d".format(m, s)
}
