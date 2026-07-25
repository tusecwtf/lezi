package com.lezi.babylog.feature.log

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.lezi.babylog.core.common.productUiError
import com.lezi.babylog.core.datastore.SettingsStore
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.ui.presentation
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.StateContainer
import com.lezi.babylog.designsystem.StateKind
import com.lezi.babylog.domain.CareLog
import com.lezi.babylog.domain.CustomRecordItem
import com.lezi.babylog.domain.FeedReminderPort
import dagger.hilt.android.lifecycle.HiltViewModel
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
    val infantFeverAdviceEnabled: Boolean = true,
    val customItems: List<CustomRecordItem> = emptyList(),
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
    private val photoStore: RecordPhotoStore,
) : ViewModel() {
    private val _state = MutableStateFlow(RecordComposerUiState())
    internal val state = _state.asStateFlow()
    private val sessionGate = RecordComposerSessionGate()
    private var loadJob: Job? = null
    private var actionJob: Job? = null

    internal fun open(request: RecordComposerRequest) {
        cleanupUnpersistedPhotos(_state.value.draft)
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
                        error = productUiError(error, "记录设置加载失败"),
                    )
                }
                return@launch
            }
            val customItems = try {
                careLog.observeCustomItems().first()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                emptyList()
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
                            val recentAmounts = if (
                                request.type in setOf(
                                    RecordType.FORMULA,
                                    RecordType.PUMPED_FEED,
                                    RecordType.PUMP_EXPRESS,
                                )
                            ) {
                                careLog.recentMilkAmounts(request.babyId, request.type)
                            } else {
                                emptyList()
                            }
                            QuickRecordDraft.create(
                                type = request.type,
                                timestamp = request.timestamp,
                                lastAmountMl = request.lastAmountMl,
                                recentAmountMl = recentAmounts,
                                recentNotes = careLog.recentNotes(
                                    request.babyId,
                                    request.type,
                                ),
                                historical = request.historical,
                            ).let { created ->
                                if (request.type == RecordType.CUSTOM) {
                                    customItems.firstOrNull()?.let { item ->
                                        created.copy(
                                            customTitle = item.name,
                                            customItemId = item.id,
                                            customIconSlot = item.iconSlot,
                                        )
                                    } ?: created
                                } else {
                                    created
                                }
                            }
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
                        infantFeverAdviceEnabled = settings.infantFeverAdviceEnabled,
                        error = productUiError(error, "记录加载失败"),
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
                    infantFeverAdviceEnabled = settings.infantFeverAdviceEnabled,
                    customItems = customItems,
                    canStartNursingTimer = request is RecordComposerRequest.New &&
                        !request.historical &&
                        request.type == RecordType.NURSING &&
                        settings.timerEnabled,
                )
            }
        }
    }

    internal fun close() {
        cleanupUnpersistedPhotos(_state.value.draft)
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

    internal fun importPhotos(uris: List<Uri>) {
        val draft = _state.value.draft ?: return
        if (draft.mode != QuickRecordMode.Text || uris.isEmpty()) return
        val session = sessionGate.current() ?: return
        actionJob = viewModelScope.launch {
            val imported = try {
                photoStore.import(
                    uris.take((MAX_RECORD_PHOTOS - draft.photos.size).coerceAtLeast(0)),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                currentCoroutineContext().ensureActive()
                sessionGate.deliver(session) {
                    _state.update { it.copy(error = productUiError(error, "图片导入失败")) }
                }
                return@launch
            }
            currentCoroutineContext().ensureActive()
            sessionGate.deliver(session) {
                _state.update { state ->
                    val current = state.draft ?: return@update state
                    state.copy(
                        draft = current.copy(
                            photos = (current.photos + imported).distinct().take(MAX_RECORD_PHOTOS),
                        ),
                        error = null,
                    )
                }
            }
        }
    }

    internal fun removePhoto(path: String) {
        val draft = _state.value.draft ?: return
        _state.update { it.copy(draft = draft.copy(photos = draft.photos - path)) }
        if (path !in draft.sourcePhotos) {
            viewModelScope.launch { photoStore.delete(listOf(path)) }
        }
    }

    internal fun save(onSaved: (message: String, offerReminder: Boolean) -> Unit) {
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
                        schemaVersion = command.schemaVersion,
                    )
                    command.existingRecordId != null -> careLog.updateRecord(
                        id = command.existingRecordId,
                        timestamp = command.timestamp,
                        endTimestamp = command.endTimestamp,
                        note = command.note,
                        payloadJson = command.payloadJson,
                        schemaVersion = command.schemaVersion,
                    )
                    else -> careLog.addRecord(
                        babyId = babyId,
                        type = command.type,
                        timestamp = command.timestamp,
                        endTimestamp = command.endTimestamp,
                        note = command.note,
                        payloadJson = command.payloadJson,
                        schemaVersion = command.schemaVersion,
                    )
                }
                photoStore.delete(draft.sourcePhotos - draft.photos.toSet())
                val message = when {
                    draft.sleepAction == SleepDraftAction.SleepDown &&
                        draft.endTimestamp == null -> "已开始睡眠"
                    draft.sleepAction == SleepDraftAction.SleepDown -> "已记录睡眠"
                    draft.sleepAction == SleepDraftAction.WakeUp -> "已记录醒来"
                    draft.isEditing -> "已保存修改"
                    else -> "已记录${command.type.presentation.label}"
                }
                message to (
                    command.existingRecordId == null &&
                        command.type in FEED_TYPES
                    )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                currentCoroutineContext().ensureActive()
                sessionGate.deliver(session) {
                    _state.update {
                        it.copy(
                            saving = false,
                            error = productUiError(error, "保存失败，请重试"),
                        )
                    }
                }
                return@launch
            }
            currentCoroutineContext().ensureActive()
            // Post-write UI (sourcePhotos mark + success callback) only when this
            // request is still active — never side-write a newer draft/session.
            sessionGate.deliver(session) {
                _state.update {
                    it.copy(draft = it.draft?.copy(sourcePhotos = draft.photos))
                }
                onSaved(message.first, message.second)
            }
        }
    }

    internal fun scheduleReminder(atMillis: Long?, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val success = runCatching {
                feedReminder.scheduleAfterFeed(atMillis)
            }.isSuccess
            onResult(success)
        }
    }

    internal fun delete(onDeleted: (String) -> Unit) {
        val snapshot = _state.value
        val draft = snapshot.draft ?: return
        val recordId = draft.existingRecordId ?: return
        if (snapshot.saving || snapshot.deleting) return
        val session = sessionGate.current() ?: return
        _state.update { it.copy(deleting = true, error = null) }
        actionJob = viewModelScope.launch {
            try {
                careLog.deleteRecord(recordId)
                photoStore.delete(draft.photos)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                currentCoroutineContext().ensureActive()
                sessionGate.deliver(session) {
                    _state.update {
                        it.copy(
                            deleting = false,
                            error = productUiError(error, "删除失败，请重试"),
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

    private fun cleanupUnpersistedPhotos(draft: QuickRecordDraft?) {
        val paths = draft?.photos.orEmpty() - draft?.sourcePhotos.orEmpty().toSet()
        if (paths.isNotEmpty()) {
            viewModelScope.launch { photoStore.delete(paths) }
        }
    }

    private companion object {
        val FEED_TYPES = setOf(
            RecordType.NURSING,
            RecordType.FORMULA,
            RecordType.PUMPED_FEED,
        )
        const val MAX_RECORD_PHOTOS = 9
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
    var pendingSavedMessage by remember(request) { mutableStateOf<String?>(null) }
    var adjustReminder by remember(request) { mutableStateOf(false) }
    var pendingReminderAt by remember(request) { mutableStateOf<Long?>(null) }
    val context = LocalContext.current
    fun finishSaved(message: String) {
        pendingSavedMessage = null
        adjustReminder = false
        pendingReminderAt = null
        onSaved(message)
    }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val message = pendingSavedMessage ?: return@rememberLauncherForActivityResult
        if (!granted) {
            finishSaved("$message；通知权限未开启，未设置提醒")
        } else {
            vm.scheduleReminder(pendingReminderAt) { scheduled ->
                finishSaved(
                    if (scheduled) "$message；提醒已设置"
                    else "$message；提醒设置失败，可在设置中重试",
                )
            }
        }
    }
    fun requestOrScheduleReminder(atMillis: Long?) {
        pendingReminderAt = atMillis
        val hasPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        if (hasPermission) {
            val message = pendingSavedMessage ?: return
            vm.scheduleReminder(atMillis) { scheduled ->
                finishSaved(
                    if (scheduled) "$message；提醒已设置"
                    else "$message；提醒设置失败，可在设置中重试",
                )
            }
        } else {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
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
                    interactionKey = request,
                    amountStepMl = state.amountStepMl,
                    timeStepMin = state.timeStepMin,
                    timePickerStyle = state.timePickerStyle,
                    preferredHand = state.preferredHand,
                    birthdayEpochDay = state.birthdayEpochDay,
                    infantFeverAdviceEnabled = state.infantFeverAdviceEnabled,
                    customItems = state.customItems,
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
                    onConfirm = {
                        vm.save { message, offerReminder ->
                            if (offerReminder) {
                                pendingSavedMessage = message
                            } else {
                                onSaved(message)
                            }
                        }
                    },
                    onStartNursingTimer = {
                        onStartNursingTimer(draft.note, draft.nursingAmountMl)
                    },
                    onImportPhotos = vm::importPhotos,
                    onRemovePhoto = vm::removePhoto,
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

    pendingSavedMessage?.let { message ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text(if (adjustReminder) "调整提醒时间" else "设置下次喂养提醒？") },
            text = {
                Text(
                    if (adjustReminder) {
                        "选择从现在起的提醒间隔。记录已经安全保存。"
                    } else {
                        "记录已经安全保存。你可以按设置间隔提醒、调整时间，或不提醒。"
                    },
                )
            },
            confirmButton = {
                if (adjustReminder) {
                    TextButton(
                        onClick = {
                            requestOrScheduleReminder(System.currentTimeMillis() + 60 * 60_000L)
                        },
                    ) { Text("60 分钟") }
                } else {
                    TextButton(onClick = { requestOrScheduleReminder(null) }) {
                        Text("确认提醒")
                    }
                }
            },
            dismissButton = {
                if (adjustReminder) {
                    TextButton(
                        onClick = {
                            requestOrScheduleReminder(System.currentTimeMillis() + 120 * 60_000L)
                        },
                    ) { Text("120 分钟") }
                } else {
                    TextButton(onClick = { adjustReminder = true }) {
                        Text("调整时间")
                    }
                    TextButton(onClick = { finishSaved("$message；未设置提醒") }) {
                        Text("不提醒")
                    }
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
