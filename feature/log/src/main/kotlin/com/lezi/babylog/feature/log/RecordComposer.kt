package com.lezi.babylog.feature.log

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.lezi.babylog.core.datastore.SettingsStore
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.ui.presentation
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.StateContainer
import com.lezi.babylog.designsystem.StateKind
import com.lezi.babylog.domain.CareLog
import com.lezi.babylog.domain.FeedReminderPort
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface RecordComposerRequest {
    data class New(
        val babyId: Long,
        val type: RecordType,
        val timestamp: Long,
        val historical: Boolean,
        val openSleepId: Long? = null,
        val lastAmountMl: Int? = null,
    ) : RecordComposerRequest

    data class Edit(val recordId: Long) : RecordComposerRequest
}

internal data class RecordComposerUiState(
    val activeRequest: RecordComposerRequest? = null,
    val loading: Boolean = false,
    val draft: QuickRecordDraft? = null,
    val babyId: Long? = null,
    /** Used for age-based tips (e.g. complementary food). */
    val birthdayEpochDay: Long? = null,
    val amountStepMl: Int = 5,
    val timeStepMin: Int = 1,
    val timePickerStyle: String = "dropdown",
    val preferredHand: String = "right",
    val canStartNursingTimer: Boolean = false,
    val saving: Boolean = false,
    val deleting: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class RecordComposerViewModel @Inject constructor(
    private val careLog: CareLog,
    private val settingsStore: SettingsStore,
    private val feedReminder: FeedReminderPort,
) : ViewModel() {
    private val _state = MutableStateFlow(RecordComposerUiState())
    internal val state = _state.asStateFlow()
    private val sessionGate = RecordComposerSessionGate()
    private var loadJob: Job? = null
    private var actionJob: Job? = null

    internal fun open(request: RecordComposerRequest) {
        val session = sessionGate.open()
        loadJob?.cancel()
        actionJob?.cancel()
        _state.value = RecordComposerUiState(activeRequest = request, loading = true)
        loadJob = viewModelScope.launch {
            val settings = try {
                settingsStore.settings.first()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                currentCoroutineContext().ensureActive()
                sessionGate.deliver(session) {
                    _state.value = RecordComposerUiState(
                        activeRequest = request,
                        error = error.message ?: "记录设置加载失败",
                    )
                }
                return@launch
            }
            val loaded = try {
                when (request) {
                    is RecordComposerRequest.New -> {
                        val baby = careLog.listBabies().firstOrNull { it.id == request.babyId }
                            ?: error("宝宝档案不存在，请返回后重试")
                        val draft = if (request.openSleepId != null) {
                            val openSleep = careLog.getRecord(request.openSleepId)
                            check(
                                openSleep != null &&
                                    openSleep.babyId == request.babyId &&
                                    openSleep.type == RecordType.SLEEP &&
                                    openSleep.endTimestamp == null,
                            ) {
                                "睡眠状态已变化，请关闭后重试"
                            }
                            QuickRecordDraft.wakeSleep(openSleep, request.timestamp)
                        } else {
                            QuickRecordDraft.create(
                                type = request.type,
                                timestamp = request.timestamp,
                                lastAmountMl = request.lastAmountMl,
                                historical = request.historical,
                            )
                        }
                        Triple(request.babyId, baby.birthdayEpochDay, draft)
                    }
                    is RecordComposerRequest.Edit -> {
                        val record = careLog.getRecord(request.recordId)
                            ?: error("这条记录不存在或已被删除")
                        val birthday = careLog.listBabies()
                            .firstOrNull { it.id == record.babyId }
                            ?.birthdayEpochDay
                        Triple(record.babyId, birthday, QuickRecordDraft.fromRecord(record))
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                currentCoroutineContext().ensureActive()
                sessionGate.deliver(session) {
                    _state.value = RecordComposerUiState(
                        activeRequest = request,
                        amountStepMl = settings.amountStepMl,
                        timeStepMin = settings.timeStepMin,
                        timePickerStyle = settings.timePickerStyle,
                        preferredHand = settings.preferredHand,
                        error = error.message ?: "记录加载失败",
                    )
                }
                return@launch
            }
            currentCoroutineContext().ensureActive()
            val (babyId, birthdayEpochDay, draft) = loaded
            sessionGate.deliver(session) {
                _state.value = RecordComposerUiState(
                    activeRequest = request,
                    draft = draft,
                    babyId = babyId,
                    birthdayEpochDay = birthdayEpochDay,
                    amountStepMl = settings.amountStepMl,
                    timeStepMin = settings.timeStepMin,
                    timePickerStyle = settings.timePickerStyle,
                    preferredHand = settings.preferredHand,
                    canStartNursingTimer = request is RecordComposerRequest.New &&
                        !request.historical &&
                        request.type == RecordType.NURSING &&
                        settings.timerEnabled,
                )
            }
        }
    }

    internal fun close() {
        sessionGate.close()
        loadJob?.cancel()
        actionJob?.cancel()
        loadJob = null
        actionJob = null
        _state.value = RecordComposerUiState()
    }

    internal fun updateDraft(draft: QuickRecordDraft) {
        _state.update { it.copy(draft = draft, error = null) }
    }

    internal fun save(onSaved: (String) -> Unit) {
        val snapshot = _state.value
        val draft = snapshot.draft ?: return
        val babyId = snapshot.babyId ?: return
        if (snapshot.saving || snapshot.deleting) return
        val validation = draft.validationError()
        if (validation != null) {
            _state.update { it.copy(error = validation) }
            return
        }
        val session = sessionGate.current() ?: return
        _state.update { it.copy(saving = true, error = null) }
        actionJob = viewModelScope.launch {
            val message = try {
                val command = draft.toSaveCommand()
                val statefulSleep = command.type == RecordType.SLEEP &&
                    draft.sleepAction in setOf(
                        SleepDraftAction.SleepDown,
                        SleepDraftAction.WakeUp,
                    )
                when {
                    statefulSleep -> careLog.confirmSleep(
                        babyId = babyId,
                        expectedOpenSleepId = command.existingRecordId,
                        timestamp = command.timestamp,
                        endTimestamp = command.endTimestamp,
                        note = command.note,
                        payloadJson = command.payloadJson,
                    )
                    command.existingRecordId != null -> careLog.updateRecord(
                        id = command.existingRecordId,
                        timestamp = command.timestamp,
                        endTimestamp = command.endTimestamp,
                        note = command.note,
                        payloadJson = command.payloadJson,
                    )
                    else -> careLog.addRecord(
                        babyId = babyId,
                        type = command.type,
                        timestamp = command.timestamp,
                        endTimestamp = command.endTimestamp,
                        note = command.note,
                        payloadJson = command.payloadJson,
                    )
                }
                if (
                    command.existingRecordId == null &&
                    command.type in FEED_TYPES &&
                    Instant.ofEpochMilli(command.timestamp)
                        .atZone(ZoneId.systemDefault())
                        .toLocalDate() == LocalDate.now()
                ) {
                    try {
                        feedReminder.scheduleAfterFeed()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        // The record is already durable; reminder failure is non-fatal.
                    }
                }
                when {
                    draft.sleepAction == SleepDraftAction.SleepDown &&
                        draft.endTimestamp == null -> "已开始睡眠"
                    draft.sleepAction == SleepDraftAction.SleepDown -> "已记录睡眠"
                    draft.sleepAction == SleepDraftAction.WakeUp -> "已记录醒来"
                    draft.isEditing -> "已保存修改"
                    else -> "已记录${command.type.presentation.label}"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                currentCoroutineContext().ensureActive()
                sessionGate.deliver(session) {
                    _state.update {
                        it.copy(
                            saving = false,
                            error = error.message ?: "保存失败，请重试",
                        )
                    }
                }
                return@launch
            }
            currentCoroutineContext().ensureActive()
            sessionGate.deliver(session) {
                onSaved(message)
            }
        }
    }

    internal fun delete(onDeleted: (String) -> Unit) {
        val snapshot = _state.value
        val recordId = snapshot.draft?.existingRecordId ?: return
        if (snapshot.saving || snapshot.deleting) return
        val session = sessionGate.current() ?: return
        _state.update { it.copy(deleting = true, error = null) }
        actionJob = viewModelScope.launch {
            try {
                careLog.deleteRecord(recordId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                currentCoroutineContext().ensureActive()
                sessionGate.deliver(session) {
                    _state.update {
                        it.copy(
                            deleting = false,
                            error = error.message ?: "删除失败，请重试",
                        )
                    }
                }
                return@launch
            }
            currentCoroutineContext().ensureActive()
            sessionGate.deliver(session) {
                onDeleted("已删除记录")
            }
        }
    }

    private companion object {
        val FEED_TYPES = setOf(
            RecordType.NURSING,
            RecordType.FORMULA,
            RecordType.PUMPED_FEED,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordComposerHost(
    request: RecordComposerRequest?,
    onDismiss: () -> Unit,
    onSaved: (String) -> Unit,
    onStartNursingTimer: (note: String, amountMl: String) -> Unit,
    vm: RecordComposerViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var confirmDelete by remember(request) { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

    LaunchedEffect(request) {
        if (request == null) {
            vm.close()
        } else {
            vm.open(request)
        }
    }

    if (request != null) {
        ModalBottomSheet(
            onDismissRequest = {
                if (!state.saving && !state.deleting) {
                    vm.close()
                    onDismiss()
                }
            },
            sheetState = sheetState,
        ) {
            val ready = state.activeRequest == request
            val draft = state.draft.takeIf { ready }
            when {
                !ready || state.loading -> ComposerState(
                    kind = StateKind.Loading,
                    title = "正在准备记录",
                    message = "请稍候…",
                )
                draft == null -> ComposerState(
                    kind = StateKind.Error,
                    title = "无法打开记录",
                    message = state.error ?: "记录加载失败",
                    actionLabel = "关闭",
                    onAction = {
                        vm.close()
                        onDismiss()
                    },
                )
                else -> QuickRecordSheet(
                    draft = draft,
                    amountStepMl = state.amountStepMl,
                    timeStepMin = state.timeStepMin,
                    timePickerStyle = state.timePickerStyle,
                    preferredHand = state.preferredHand,
                    birthdayEpochDay = state.birthdayEpochDay,
                    saving = state.saving,
                    deleting = state.deleting,
                    saveError = state.error,
                    canStartNursingTimer =
                        state.canStartNursingTimer,
                    onDraftChange = vm::updateDraft,
                    onDismiss = {
                        vm.close()
                        onDismiss()
                    },
                    onDelete = if (draft.isEditing) {
                        { confirmDelete = true }
                    } else {
                        null
                    },
                    onConfirm = { vm.save(onSaved) },
                    onStartNursingTimer = {
                        onStartNursingTimer(draft.note, draft.nursingAmountMl)
                    },
                )
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = {
                if (!state.deleting) confirmDelete = false
            },
            title = { Text("删除这条记录？") },
            text = { Text("删除后会从时间轴和汇总中移除，无法撤销。") },
            confirmButton = {
                TextButton(
                    enabled = !state.deleting,
                    onClick = {
                        vm.delete { message ->
                            confirmDelete = false
                            onSaved(message)
                        }
                    },
                ) {
                    Text(
                        if (state.deleting) "删除中…" else "确认删除",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !state.deleting,
                    onClick = { confirmDelete = false },
                ) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
private fun ComposerState(
    kind: StateKind,
    title: String,
    message: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 300.dp)
            .padding(LeziSpacing.Lg),
        contentAlignment = Alignment.Center,
    ) {
        StateContainer(
            kind = kind,
            title = title,
            message = message,
            actionLabel = actionLabel,
            onAction = onAction,
        )
    }
}
