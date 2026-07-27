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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.lezi.babylog.core.common.productUiError
import com.lezi.babylog.core.datastore.SettingsStore
import com.lezi.babylog.core.model.MAX_RECORD_PHOTOS
import com.lezi.babylog.core.model.RecordItemIdentity
import com.lezi.babylog.core.model.RecordTime
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface RecordComposerRequest : java.io.Serializable {
    /**
     * Create a new fact or plan for a concrete record item.
     *
     * [type] remains the storage [RecordType]. When [type] is [RecordType.CUSTOM],
     * [customItemId] identifies the specific custom definition (required for new
     * catalog picks). [createIntent] freezes an explicit Calendar scheduling path;
     * otherwise the shared Composer derives fact versus plan from [timestamp].
     * CUSTOM creation always carries a concrete definition id.
     */
    data class New(
        val babyId: Long,
        val type: RecordType,
        val timestamp: Long,
        val historical: Boolean,
        val openSleepId: Long? = null,
        val lastAmountMl: Int? = null,
        /** Concrete custom definition when [type] is CUSTOM; null for built-ins. */
        val customItemId: Long? = null,
        /** Explicit Calendar scheduling remains a plan even if its initial time expires. */
        val createIntent: ComposerCreateIntent = ComposerCreateIntent.DeriveFromTimestamp,
    ) : RecordComposerRequest {
        init {
            require(type != RecordType.CUSTOM || customItemId?.takeIf { it > 0L } != null) {
                "CUSTOM requires positive customItemId"
            }
        }

        fun itemIdentity(): RecordItemIdentity = if (type == RecordType.CUSTOM) {
            RecordItemIdentity.custom(requireNotNull(customItemId))
        } else {
            RecordItemIdentity.BuiltIn(type)
        }
    }

    data class Edit(val recordId: Long) : RecordComposerRequest

    /** Open Composer in fulfill mode for a local care plan. */
    data class Fulfill(val carePlanId: Long) : RecordComposerRequest

    /** Open Composer to edit an open local care plan (not fulfill). */
    data class EditPlan(val carePlanId: Long) : RecordComposerRequest
}

/** SavedStateHandle adapter for the restorable Composer request/draft pair. */
internal class RecordComposerSavedState(
    private val handle: SavedStateHandle,
) {
    fun save(request: RecordComposerRequest, draft: QuickRecordDraft) {
        handle[REQUEST_KEY] = request
        handle[DRAFT_KEY] = draft
    }

    fun restore(request: RecordComposerRequest): QuickRecordDraft? =
        handle.get<RecordComposerRequest>(REQUEST_KEY)
            ?.takeIf { it == request }
            ?.let { handle[DRAFT_KEY] }

    fun draftForCleanup(): QuickRecordDraft? = handle[DRAFT_KEY]

    fun clear() {
        handle.remove<RecordComposerRequest>(REQUEST_KEY)
        handle.remove<QuickRecordDraft>(DRAFT_KEY)
    }

    private companion object {
        const val REQUEST_KEY = "record_composer_saved_request"
        const val DRAFT_KEY = "record_composer_saved_draft"
    }
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
    /** Settings.timerEnabled snapshot for the open session (ticket 16 workMode gating). */
    val timerEnabledSetting: Boolean = false,
    /** Device has enabled system calendar + chosen writable target (ticket 21). */
    val systemCalendarConfigured: Boolean = false,
    val saving: Boolean = false,
    val deleting: Boolean = false,
    val error: String? = null,
)

/** One immutable write decision for a confirm attempt; never resample wall-clock mode mid-save. */
internal enum class ComposerWriteDecision {
    UpdateCarePlan,
    FulfillCarePlan,
    ConvertRecordToCarePlan,
    ConfirmSleep,
    UpdateRecord,
    CreateCarePlan,
    AddRecord,
}

internal fun QuickRecordDraft.writeDecision(nowMillis: Long): ComposerWriteDecision = when {
    isEditingCarePlan -> ComposerWriteDecision.UpdateCarePlan
    carePlanId != null -> ComposerWriteDecision.FulfillCarePlan
    needsConvertToCarePlan(nowMillis) -> ComposerWriteDecision.ConvertRecordToCarePlan
    workMode(nowMillis) == ComposerWorkMode.ScheduleCare -> ComposerWriteDecision.CreateCarePlan
    type == RecordType.SLEEP && sleepAction in setOf(
        SleepDraftAction.SleepDown,
        SleepDraftAction.WakeUp,
    ) -> ComposerWriteDecision.ConfirmSleep
    existingRecordId != null -> ComposerWriteDecision.UpdateRecord
    else -> ComposerWriteDecision.AddRecord
}

@HiltViewModel
class RecordComposerViewModel @Inject constructor(
    private val careLog: CareLog,
    private val settingsStore: SettingsStore,
    private val feedReminder: FeedReminderPort,
    private val photoStore: RecordPhotoStore,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val _state = MutableStateFlow(RecordComposerUiState())
    internal val state = _state.asStateFlow()
    private val sessionGate = RecordComposerSessionGate()
    private val savedState = RecordComposerSavedState(savedStateHandle)
    private var loadJob: Job? = null
    private var actionJob: Job? = null
    private var settingsObserveJob: Job? = null

    internal fun open(request: RecordComposerRequest) {
        val current = _state.value
        if (current.activeRequest == request && (current.loading || current.draft != null)) return
        val restoredDraft = savedState.restore(request)
        val session = sessionGate.open()
        loadJob?.cancel()
        actionJob?.cancel()
        settingsObserveJob?.cancel()
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
                            // Record photos are hydrated exclusively from MediaAsset rows.
                            val photoPaths = careLog.listRecordPhotoPaths(openSleep.id)
                            QuickRecordDraft.wakeSleep(openSleep, request.timestamp).copy(
                                photos = photoPaths,
                                sourcePhotos = photoPaths,
                            )
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
                                customItemId = request.customItemId,
                                createIntent = request.createIntent,
                            ).let { created ->
                                // Bind the concrete custom definition from the request.
                                if (request.type == RecordType.CUSTOM) {
                                    val requestedId = request.customItemId
                                    val item = requestedId?.let { id ->
                                        customItems.firstOrNull { it.id == id }
                                    }
                                    if (item != null) {
                                        created.copy(
                                            customTitle = item.name,
                                            customItemId = item.id,
                                            customIconSlot = item.iconSlot,
                                        )
                                    } else {
                                        created
                                    }
                                } else {
                                    created
                                }
                            }
                        }
                        Triple(request.babyId, baby.birthdayEpochDay, draft)
                    }
                    is RecordComposerRequest.Fulfill -> {
                        val plan = careLog.getCarePlan(request.carePlanId)
                            ?: error("护理计划不存在，请返回后重试")
                        val birthday = careLog.listBabies()
                            .firstOrNull { it.id == plan.babyId }
                            ?.birthdayEpochDay
                        val planPhotos = careLog.listCarePlanPhotoPaths(plan.id)
                        // Hydrate plan field snapshot + plan photos into fulfill form.
                        // sourcePhotos empty so fulfill save does not delete plan-owned files.
                        val draft = QuickRecordDraft.fromCarePlan(plan).copy(
                            photos = planPhotos,
                            sourcePhotos = emptyList(),
                        )
                        Triple(plan.babyId, birthday, draft)
                    }
                    is RecordComposerRequest.EditPlan -> {
                        val plan = careLog.getCarePlan(request.carePlanId)
                            ?: error("护理计划不存在，请返回后重试")
                        val birthday = careLog.listBabies()
                            .firstOrNull { it.id == plan.babyId }
                            ?.birthdayEpochDay
                        val planPhotos = careLog.listCarePlanPhotoPaths(plan.id)
                        val draft = QuickRecordDraft.fromCarePlanForEdit(plan).copy(
                            photos = planPhotos,
                            sourcePhotos = planPhotos,
                        )
                        Triple(plan.babyId, birthday, draft)
                    }
                    is RecordComposerRequest.Edit -> {
                        val record = careLog.getRecord(request.recordId)
                            ?: error("这条记录不存在或已被删除")
                        val birthday = careLog.listBabies()
                            .firstOrNull { it.id == record.babyId }
                            ?.birthdayEpochDay
                        val photoPaths = careLog.listRecordPhotoPaths(record.id)
                        val draft = QuickRecordDraft.fromRecord(record).copy(
                            photos = photoPaths,
                            sourcePhotos = photoPaths,
                        )
                        Triple(record.babyId, birthday, draft)
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
                val activeDraft = restoredDraft ?: draft
                val systemCalConfigured = settings.systemCalendarEnabled &&
                    !settings.systemCalendarId.isNullOrBlank()
                _state.value = RecordComposerUiState(
                    activeRequest = request,
                    draft = activeDraft,
                    babyId = babyId,
                    birthdayEpochDay = birthdayEpochDay,
                    amountStepMl = settings.amountStepMl,
                    timeStepMin = settings.timeStepMin,
                    timePickerStyle = settings.timePickerStyle,
                    preferredHand = settings.preferredHand,
                    infantFeverAdviceEnabled = settings.infantFeverAdviceEnabled,
                    customItems = customItems,
                    timerEnabledSetting = settings.timerEnabled,
                    canStartNursingTimer = computeCanStartNursingTimer(
                        request = request,
                        draft = activeDraft,
                        timerEnabled = settings.timerEnabled,
                    ),
                    systemCalendarConfigured = systemCalConfigured,
                )
                savedState.save(request, activeDraft)
                // Keep projection chrome in sync if user completes setup mid-sheet.
                settingsObserveJob?.cancel()
                settingsObserveJob = viewModelScope.launch {
                    settingsStore.settings.collect { live ->
                        if (sessionGate.current() != session) return@collect
                        val configured = live.systemCalendarEnabled &&
                            !live.systemCalendarId.isNullOrBlank()
                        _state.update { cur ->
                            if (cur.activeRequest != request) cur
                            else cur.copy(systemCalendarConfigured = configured)
                        }
                    }
                }
            }
        }
    }

    internal fun close() {
        cleanupUnpersistedPhotos(_state.value.draft ?: savedState.draftForCleanup())
        savedState.clear()
        sessionGate.close()
        loadJob?.cancel()
        actionJob?.cancel()
        settingsObserveJob?.cancel()
        loadJob = null
        actionJob = null
        settingsObserveJob = null
        _state.value = RecordComposerUiState()
    }

    internal fun updateDraft(draft: QuickRecordDraft) {
        _state.update { cur ->
            val request = cur.activeRequest
            val nextCan = if (request == null) {
                false
            } else {
                computeCanStartNursingTimer(
                    request = request,
                    draft = draft,
                    timerEnabled = cur.timerEnabledSetting,
                )
            }
            cur.copy(draft = draft, error = null, canStartNursingTimer = nextCan)
        }
        _state.value.activeRequest?.let { request ->
            savedState.save(request, draft)
        }
    }

    internal fun importPhotos(uris: List<Uri>) {
        val draft = _state.value.draft ?: return
        if (uris.isEmpty()) return
        val session = sessionGate.current() ?: return
        actionJob = viewModelScope.launch {
            val imported = try {
                photoStore.import(
                    uris.take(RecordPhotoChrome.remainingSlots(draft.photos.size)),
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
                persistCurrentDraft()
            }
        }
    }

    internal fun removePhoto(path: String) {
        val draft = _state.value.draft ?: return
        _state.update { it.copy(draft = draft.copy(photos = draft.photos - path)) }
        persistCurrentDraft()
        if (path !in draft.sourcePhotos) {
            viewModelScope.launch { photoStore.delete(listOf(path)) }
        }
    }

    internal fun save(onSaved: (message: String, offerReminder: Boolean) -> Unit) {
        val snapshot = _state.value
        val draft = snapshot.draft ?: return
        val babyId = snapshot.babyId ?: return
        if (snapshot.saving || snapshot.deleting) return
        val nowMillis = RecordTime.currentTimeMillis()
        val validation = draft.validationError(nowMillis)
        if (validation != null) {
            _state.update { it.copy(error = validation) }
            return
        }
        val writeDecision = draft.writeDecision(nowMillis)
        val session = sessionGate.current() ?: return
        _state.update { it.copy(saving = true, error = null) }
        actionJob = viewModelScope.launch {
            val message = try {
                val command = draft.toSaveCommand()
                when (writeDecision) {
                    ComposerWriteDecision.UpdateCarePlan -> careLog.updateCarePlan(
                        carePlanId = requireNotNull(draft.carePlanId),
                        scheduledAt = command.timestamp,
                        note = command.note,
                        payloadJson = command.payloadJson,
                        schemaVersion = command.schemaVersion,
                        photoLocalPaths = draft.photos,
                        projectToSystemCalendar = draft.projectToSystemCalendar,
                    )
                    ComposerWriteDecision.FulfillCarePlan -> careLog.fulfillCarePlan(
                        carePlanId = requireNotNull(draft.carePlanId),
                        actualTimestamp = command.timestamp,
                        endTimestamp = command.endTimestamp.takeIf {
                            command.type == RecordType.SLEEP
                        },
                        note = command.note,
                        payloadJson = command.payloadJson,
                        schemaVersion = command.schemaVersion,
                        photoLocalPaths = draft.photos,
                    )
                    ComposerWriteDecision.ConvertRecordToCarePlan ->
                        careLog.convertRecordToCarePlan(
                            recordId = requireNotNull(command.existingRecordId),
                            scheduledAt = command.timestamp,
                            note = command.note,
                            payloadJson = command.payloadJson,
                            schemaVersion = command.schemaVersion,
                            photoLocalPaths = draft.photos,
                            projectToSystemCalendar = draft.projectToSystemCalendar,
                        )
                    ComposerWriteDecision.ConfirmSleep -> careLog.confirmSleep(
                        babyId = babyId,
                        expectedOpenSleepId = command.existingRecordId,
                        timestamp = command.timestamp,
                        endTimestamp = command.endTimestamp,
                        note = command.note,
                        payloadJson = command.payloadJson,
                        schemaVersion = command.schemaVersion,
                        photoLocalPaths = draft.photos,
                    )
                    ComposerWriteDecision.UpdateRecord -> careLog.updateRecord(
                        id = requireNotNull(command.existingRecordId),
                        timestamp = command.timestamp,
                        endTimestamp = command.endTimestamp,
                        note = command.note,
                        payloadJson = command.payloadJson,
                        schemaVersion = command.schemaVersion,
                        photoLocalPaths = draft.photos,
                    )
                    ComposerWriteDecision.CreateCarePlan -> careLog.createCarePlan(
                        babyId = babyId,
                        type = command.type,
                        scheduledAt = command.timestamp,
                        note = command.note,
                        payloadJson = command.payloadJson,
                        schemaVersion = command.schemaVersion,
                        customItemId = draft.customItemId,
                        photoLocalPaths = draft.photos,
                        projectToSystemCalendar = draft.projectToSystemCalendar,
                    )
                    ComposerWriteDecision.AddRecord -> careLog.addRecord(
                        babyId = babyId,
                        type = command.type,
                        timestamp = command.timestamp,
                        endTimestamp = command.endTimestamp,
                        note = command.note,
                        payloadJson = command.payloadJson,
                        schemaVersion = command.schemaVersion,
                        photoLocalPaths = draft.photos,
                    )
                }
                // Physical cleanup only after the domain transaction committed.
                // Never delete plan-owned paths on fulfill (sourcePhotos empty);
                // edit-plan, convert, and edit-record only drop discarded paths.
                photoStore.delete(draft.sourcePhotos - draft.photos.toSet())
                val message = when (writeDecision) {
                    ComposerWriteDecision.UpdateCarePlan -> "已保存护理计划"
                    ComposerWriteDecision.FulfillCarePlan -> "已完成护理计划"
                    ComposerWriteDecision.ConvertRecordToCarePlan -> {
                        val label = if (command.type == RecordType.CUSTOM) {
                            draft.customTitle.trim().ifBlank {
                                command.type.presentation.label
                            }
                        } else {
                            command.type.presentation.label
                        }
                        "已转为护理计划 · $label"
                    }
                    ComposerWriteDecision.CreateCarePlan -> {
                        val label = if (command.type == RecordType.CUSTOM) {
                            draft.customTitle.trim().ifBlank {
                                command.type.presentation.label
                            }
                        } else {
                            command.type.presentation.label
                        }
                        "已安排$label"
                    }
                    ComposerWriteDecision.ConfirmSleep -> when {
                        draft.sleepAction == SleepDraftAction.SleepDown &&
                            draft.endTimestamp == null -> "已开始睡眠"
                        draft.sleepAction == SleepDraftAction.SleepDown -> "已记录睡眠"
                        else -> "已记录醒来"
                    }
                    ComposerWriteDecision.UpdateRecord -> "已保存修改"
                    ComposerWriteDecision.AddRecord -> {
                        val label = if (command.type == RecordType.CUSTOM) {
                            draft.customTitle.trim().ifBlank {
                                command.type.presentation.label
                            }
                        } else {
                            command.type.presentation.label
                        }
                        "已记录$label"
                    }
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
                savedState.clear()
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
        val editPlanId = draft.carePlanId?.takeIf { draft.isEditingCarePlan }
        val recordId = draft.existingRecordId
        if (editPlanId == null && recordId == null) return
        if (snapshot.saving || snapshot.deleting) return
        val session = sessionGate.current() ?: return
        _state.update { it.copy(deleting = true, error = null) }
        actionJob = viewModelScope.launch {
            try {
                if (editPlanId != null) {
                    // Soft-delete plan + media tombstones in domain; physical GC deferred.
                    careLog.deleteCarePlan(editPlanId)
                } else {
                    careLog.deleteRecord(recordId!!)
                    // Tombstones are domain-owned; drop local bytes only after commit.
                    photoStore.delete(draft.photos)
                }
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
                savedState.clear()
                onDeleted(if (editPlanId != null) "已删除护理计划" else "已删除记录")
            }
        }
    }

    private fun cleanupUnpersistedPhotos(draft: QuickRecordDraft?) {
        val paths = draft?.photos.orEmpty() - draft?.sourcePhotos.orEmpty().toSet()
        if (paths.isNotEmpty()) {
            viewModelScope.launch(NonCancellable) { photoStore.delete(paths) }
        }
    }

    private fun persistCurrentDraft() {
        val state = _state.value
        val request = state.activeRequest ?: return
        val draft = state.draft ?: return
        savedState.save(request, draft)
    }

    private companion object {
        val FEED_TYPES = setOf(
            RecordType.NURSING,
            RecordType.FORMULA,
            RecordType.PUMPED_FEED,
        )
    }
}

internal const val RECORD_COMPOSER_SKIP_PARTIALLY_EXPANDED = true

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordComposerHost(
    request: RecordComposerRequest?,
    onDismiss: () -> Unit,
    /** Consumes the restorable root request as soon as a database write succeeds. */
    onPersisted: () -> Unit,
    onSaved: (String) -> Unit,
    onStartNursingTimer: (note: String, amountMl: String, carePlanId: Long?, babyId: Long?) -> Unit,
    /** Optional: open device-local system calendar setup (ticket 21). Cancel still saves plan. */
    onConfigureSystemCalendar: (() -> Unit)? = null,
    vm: RecordComposerViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var confirmDelete by remember(request) { mutableStateOf(false) }
    var deleteAttempted by remember(request) { mutableStateOf(false) }
    /** Explicit convert confirm; cancel keeps the draft and original record untouched. */
    var confirmConvert by remember(request) { mutableStateOf(false) }
    // These outlive request=null so a saved record can finish its optional reminder flow after
    // the restorable root request has already been consumed. rememberSaveable also preserves the
    // prompt across process recreation without ever reopening the persisted New request.
    var pendingSavedMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var adjustReminder by rememberSaveable { mutableStateOf(false) }
    var pendingReminderAt by rememberSaveable { mutableStateOf<Long?>(null) }
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
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = RECORD_COMPOSER_SKIP_PARTIALLY_EXPANDED,
    )

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
                    saving = state.saving,
                    deleting = state.deleting,
                    saveError = state.error,
                    canStartNursingTimer =
                        state.canStartNursingTimer,
                    systemCalendarConfigured = state.systemCalendarConfigured,
                    onConfigureSystemCalendar = onConfigureSystemCalendar,
                    onDraftChange = vm::updateDraft,
                    onDismiss = {
                        vm.close()
                        onDismiss()
                    },
                    onDelete = if (draft.isEditing || draft.isEditingCarePlan) {
                        {
                            deleteAttempted = false
                            confirmDelete = true
                        }
                    } else {
                        null
                    },
                    onConfirm = {
                        // Convert is not ExplainedDisabled alone — require an explicit dialog.
                        if (draft.needsConvertToCarePlan()) {
                            confirmConvert = true
                        } else {
                            vm.save { message, offerReminder ->
                                val hasNotificationPermission =
                                    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                                        ContextCompat.checkSelfPermission(
                                            context,
                                            Manifest.permission.POST_NOTIFICATIONS,
                                        ) == PackageManager.PERMISSION_GRANTED
                                val finishedMessage = carePlanSaveMessageWithPermission(
                                    baseMessage = message,
                                    notificationPermissionGranted = hasNotificationPermission,
                                    isCarePlanWrite = isCarePlanSaveMessage(message),
                                )
                                dispatchRecordSaveCompletion(
                                    message = finishedMessage,
                                    offerReminder = offerReminder,
                                    onOfferReminder = { pendingSavedMessage = it },
                                    onPersisted = onPersisted,
                                    onFinished = onSaved,
                                )
                            }
                        }
                    },
                    onStartNursingTimer = {
                        onStartNursingTimer(
                            draft.note,
                            draft.nursingAmountMl,
                            draft.carePlanId?.takeUnless { draft.editCarePlan },
                            state.babyId,
                        )
                    },
                    onImportPhotos = vm::importPhotos,
                    onRemovePhoto = vm::removePhoto,
                )
            }
        }
    }

    if (confirmDelete) {
        val planConfirmation = state.draft
            ?.takeIf { it.isEditingCarePlan }
            ?.let(::carePlanDeleteConfirmation)
        AlertDialog(
            onDismissRequest = {
                if (!state.deleting) {
                    deleteAttempted = false
                    confirmDelete = false
                }
            },
            title = {
                Text(planConfirmation?.title ?: "删除这条记录？")
            },
            text = {
                Text(
                    deleteConfirmationMessage(
                        impact = planConfirmation?.message
                            ?: "删除后会从时间轴和汇总中移除，无法撤销。",
                        error = state.error.takeIf { deleteAttempted },
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !state.deleting,
                    onClick = {
                        deleteAttempted = true
                        vm.delete { message ->
                            deleteAttempted = false
                            confirmDelete = false
                            onPersisted()
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
                    onClick = {
                        deleteAttempted = false
                        confirmDelete = false
                    },
                ) {
                    Text("取消")
                }
            },
        )
    }

    if (confirmConvert) {
        AlertDialog(
            onDismissRequest = {
                if (!state.saving) confirmConvert = false
            },
            title = { Text("转为护理计划？") },
            text = {
                Text(
                    "原记录会从时间轴和汇总中移除，字段、备注和照片会保存为待履行的护理计划。",
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !state.saving,
                    onClick = {
                        confirmConvert = false
                        vm.save { message, offerReminder ->
                            val hasNotificationPermission =
                                Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                                    ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.POST_NOTIFICATIONS,
                                    ) == PackageManager.PERMISSION_GRANTED
                            val finishedMessage = carePlanSaveMessageWithPermission(
                                baseMessage = message,
                                notificationPermissionGranted = hasNotificationPermission,
                                isCarePlanWrite = isCarePlanSaveMessage(message),
                            )
                            dispatchRecordSaveCompletion(
                                message = finishedMessage,
                                offerReminder = offerReminder,
                                onOfferReminder = { pendingSavedMessage = it },
                                onPersisted = onPersisted,
                                onFinished = onSaved,
                            )
                        }
                    },
                ) {
                    Text(if (state.saving) "保存中…" else "转为护理计划")
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !state.saving,
                    onClick = { confirmConvert = false },
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

/**
 * Enforces the post-write ordering: prepare any reminder UI, then consume the restorable root
 * request before reporting final completion. This prevents process recreation from replaying a
 * successfully persisted New request.
 */
internal fun dispatchRecordSaveCompletion(
    message: String,
    offerReminder: Boolean,
    onOfferReminder: (String) -> Unit,
    onPersisted: () -> Unit,
    onFinished: (String) -> Unit,
) {
    if (offerReminder) onOfferReminder(message)
    onPersisted()
    if (!offerReminder) onFinished(message)
}

/** Care-plan create/edit/convert success snackbars (not feed-fact or fulfill). */
internal fun isCarePlanSaveMessage(message: String): Boolean =
    message.startsWith("已安排") ||
        message.startsWith("已转为护理计划") ||
        message == "已保存护理计划"

/**
 * Permission denial never blocks plan persistence; surface a clear local-reminder
 * degradation so users know why they may not get a notification.
 * Keeps parity with [com.lezi.babylog.feature.settings.carePlanReminderPermissionDeniedStatus].
 */
internal fun carePlanSaveMessageWithPermission(
    baseMessage: String,
    notificationPermissionGranted: Boolean,
    isCarePlanWrite: Boolean,
): String {
    if (!isCarePlanWrite || notificationPermissionGranted) return baseMessage
    return if (baseMessage == "已保存护理计划") {
        "护理计划已保存；通知权限未开启，本机提醒已降级"
    } else {
        "$baseMessage；通知权限未开启，本机提醒已降级"
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

/**
 * Ticket 16 / Composer S2: nursing timer is a stateful action — only for live
 * New (non-historical) or Fulfill (not edit-plan). ScheduleCare / EditPlan never
 * enable start-timer even if the draft type is nursing.
 */
internal fun computeCanStartNursingTimer(
    request: RecordComposerRequest,
    draft: QuickRecordDraft,
    timerEnabled: Boolean,
    nowMillis: Long = RecordTime.currentTimeMillis(),
): Boolean {
    if (!timerEnabled || draft.type != RecordType.NURSING) return false
    // Convert / schedule / edit-plan are intent-only — never expose start-timer.
    if (draft.needsConvertToCarePlan(nowMillis)) return false
    val mode = draft.workMode(nowMillis)
    if (mode == ComposerWorkMode.ScheduleCare || mode == ComposerWorkMode.EditPlan) {
        return false
    }
    return when (request) {
        is RecordComposerRequest.New -> !request.historical
        is RecordComposerRequest.Fulfill -> !draft.editCarePlan
        else -> false
    }
}
