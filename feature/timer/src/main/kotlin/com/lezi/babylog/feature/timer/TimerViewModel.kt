package com.lezi.babylog.feature.timer

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lezi.babylog.core.common.newClientUuid
import com.lezi.babylog.core.common.productUiError
import com.lezi.babylog.core.datastore.SettingsStore
import com.lezi.babylog.core.model.RecordTime
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.NextFeedPlanReconciliation
import com.lezi.babylog.core.model.nextFeedSuggestedAt
import com.lezi.babylog.core.model.runNextFeedPlanReconciliation
import com.lezi.babylog.core.model.shouldOfferNextFeedPlanForFact
import com.lezi.babylog.domain.CareLog
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@HiltViewModel
class TimerViewModel @Inject constructor(
    private val careLog: CareLog,
    private val settings: SettingsStore,
    private val savedStateHandle: SavedStateHandle,
    private val serviceController: NursingTimerServiceController,
    @ApplicationContext private val app: Context,
) : ViewModel() {
    private val _state = MutableStateFlow(TimerState())
    val state: StateFlow<TimerState> = _state
    private val completionInFlight = AtomicBoolean(false)
    private val serviceStartInFlight = AtomicBoolean(false)
    /** Serializes L/R toggles so concurrent launches cannot clobber either side. */
    private val toggleMutex = Mutex()
    val timeStepMin = settings.settings
        .map { it.timeStepMin }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 1)
    val timePickerStyle = settings.settings
        .map { it.timePickerStyle }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "dropdown")
    val preferredHand = settings.settings
        .map { it.preferredHand }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "right")

    init {
        viewModelScope.launch {
            toggleMutex.withLock {
                val restored = TimerState.fromJson(
                    raw = settings.nursingTimerJson.first(),
                    nowBootCount = currentBootCount(app),
                    activeServiceSession = NursingTimerServiceRuntime.activeSession(),
                )
                // Only an in-process service witness with the same session may remain RUNNING.
                // Process restoration has no witness and therefore becomes RECOVERABLE.
                persistLocal(restored)
                if (restored.serviceState != TimerServiceState.RUNNING) {
                    serviceController.stop()
                }
            }
        }
    }

    private suspend fun persistLocal(s: TimerState) {
        settings.setNursingTimerJson(
            if (!s.hasTimerData()) {
                null
            } else {
                s.toJson(savedBootCount = currentBootCount(app))
            },
        )
        _state.value = s
    }

    private suspend fun applyTransition(next: TimerState) {
        if (!next.leftRunning && !next.rightRunning) {
            persistLocal(
                next.copy(
                    serviceState = TimerServiceState.PAUSED,
                    requestedSide = null,
                    serviceFailure = null,
                ),
            )
            serviceController.stop()
            return
        }
        try {
            startTimerWithConfirmation(
                candidate = next,
                publish = ::persistLocal,
                startService = { serviceController.startAndConfirm(next) },
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            serviceController.stop()
            throw cancelled
        } catch (_: RuntimeException) {
            serviceController.stop()
            val pending = _state.value
            val failed = if (pending.serviceState == TimerServiceState.STARTING) {
                pending.copy(
                    serviceState = TimerServiceState.FAILED,
                    serviceFailure = TimerServiceFailure.RUNTIME,
                )
            } else {
                pending
            }
            runCatching { persistLocal(failed) }
                .onFailure { _state.value = failed }
        }
    }

    fun toggleLeft() {
        val snapshot = _state.value
        if (!snapshot.canRequestServiceStart()) return
        val starting = !snapshot.leftRunning
        if (starting && !serviceStartInFlight.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                toggleMutex.withLock {
                    val now = SystemClock.elapsedRealtime()
                    val wall = System.currentTimeMillis()
                    val cur = _state.value
                    val babyIdForStart = if (!cur.leftRunning && cur.babyId == null) {
                        careLog.getCurrentBaby()?.id ?: return@withLock
                    } else {
                        null
                    }
                    val next = cur.withToggleLeft(
                        nowElapsed = now,
                        nowWall = wall,
                        babyIdForStart = babyIdForStart,
                        completionClientUuidForStart = cur.completionClientUuid ?: newClientUuid(),
                    ) ?: return@withLock
                    applyTransition(next)
                }
            } finally {
                if (starting) serviceStartInFlight.set(false)
            }
        }
    }

    fun toggleRight() {
        val snapshot = _state.value
        if (!snapshot.canRequestServiceStart()) return
        val starting = !snapshot.rightRunning
        if (starting && !serviceStartInFlight.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                toggleMutex.withLock {
                    val now = SystemClock.elapsedRealtime()
                    val wall = System.currentTimeMillis()
                    val cur = _state.value
                    val babyIdForStart = if (!cur.rightRunning && cur.babyId == null) {
                        careLog.getCurrentBaby()?.id ?: return@withLock
                    } else {
                        null
                    }
                    val next = cur.withToggleRight(
                        nowElapsed = now,
                        nowWall = wall,
                        babyIdForStart = babyIdForStart,
                        completionClientUuidForStart = cur.completionClientUuid ?: newClientUuid(),
                    ) ?: return@withLock
                    applyTransition(next)
                }
            } finally {
                if (starting) serviceStartInFlight.set(false)
            }
        }
    }

    fun retryServiceStart() {
        if (!_state.value.canRequestServiceStart()) return
        if (!serviceStartInFlight.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                toggleMutex.withLock {
                    val candidate = _state.value.retryServiceStartCandidate(
                        nowElapsed = SystemClock.elapsedRealtime(),
                        nowWall = System.currentTimeMillis(),
                    ) ?: return@withLock
                    applyTransition(candidate)
                }
            } finally {
                serviceStartInFlight.set(false)
            }
        }
    }

    internal fun freezeCompletion(
        initialNote: String = "",
        initialAmountMl: String = "",
    ): NursingCompletionDraft = freezeNursingCompletion(
        state = _state.value,
        nowElapsed = SystemClock.elapsedRealtime(),
        clickedAt = System.currentTimeMillis(),
        initialNote = initialNote,
        initialAmountMl = initialAmountMl,
    )

    internal fun complete(
        draft: NursingCompletionDraft,
        onDone: (suggestedNextFeedAt: Long?) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (!completionInFlight.compareAndSet(false, true)) return
        viewModelScope.launch {
            var suggestedNextFeedAt: Long? = null
            try {
                draft.validationError(System.currentTimeMillis())?.let {
                    onError(it)
                    return@launch
                }
                toggleMutex.withLock {
                    val stableState = _state.value
                    val babyId = stableState.babyId ?: careLog.getCurrentBaby()?.id
                    if (babyId == null) {
                        onError("请先添加宝宝")
                        return@launch
                    }
                    val completionClientUuid = requireNotNull(stableState.completionClientUuid) {
                        "计时会话尚未准备好，请重试"
                    }
                    val command = draft.toCommand()
                    val currentSettings = settings.settings.first()
                    val recordMode = currentSettings.recordAtStartOrEnd
                    careLog.completeNursing(
                        babyId = babyId,
                        leftMin = command.leftMin,
                        rightMin = command.rightMin,
                        order = command.order,
                        amountMl = command.amountMl,
                        note = command.note,
                        startedAt = command.startedAt,
                        endedAt = command.endedAt,
                        recordMode = recordMode,
                        completionClientUuid = completionClientUuid,
                        carePlanId = stableState.carePlanId,
                    )
                    val offerNextFeedPlan = shouldOfferNextFeedPlanForFact(
                        type = RecordType.NURSING,
                        createdNewFact = true,
                        sourceCarePlanId = stableState.carePlanId,
                    )
                    if (offerNextFeedPlan) {
                        suggestedNextFeedAt = nextFeedSuggestedAt(
                            nowMillis = RecordTime.currentTimeMillis(),
                            intervalMinutes = currentSettings.nursingIntervalMin,
                        )
                        savedStateHandle[PENDING_NEXT_FEED_BABY_KEY] = babyId
                        savedStateHandle[PENDING_NEXT_FEED_SUGGESTED_AT_KEY] =
                            suggestedNextFeedAt
                    } else {
                        savedStateHandle.remove<Long>(PENDING_NEXT_FEED_BABY_KEY)
                        savedStateHandle.remove<Long>(PENDING_NEXT_FEED_SUGGESTED_AT_KEY)
                    }
                    // Await the DataStore clear. If the process dies before it commits, replay uses
                    // the same completionClientUuid and CareLog returns the existing record.
                    applyTransition(TimerState())
                }
                onDone(suggestedNextFeedAt)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                onError(productUiError(throwable, "保存失败"))
            } finally {
                completionInFlight.set(false)
            }
        }
    }

    internal fun scheduleNextFeedPlan(atMillis: Long, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val success = try {
                val babyId = requireNotNull(
                    savedStateHandle.get<Long>(PENDING_NEXT_FEED_BABY_KEY),
                ) { "待安排的喂养记录已失效" }
                careLog.scheduleNextFeedCarePlan(
                    babyId = babyId,
                    feedType = RecordType.NURSING,
                    scheduledAt = atMillis,
                )
                true
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                false
            }
            onResult(success)
        }
    }

    internal fun reconcileNextFeedPlan(
        onResult: (NextFeedPlanReconciliation) -> Unit,
    ) {
        viewModelScope.launch {
            onResult(
                runNextFeedPlanReconciliation {
                    val babyId = requireNotNull(
                        savedStateHandle.get<Long>(PENDING_NEXT_FEED_BABY_KEY),
                    ) { "待核对的喂养记录已失效" }
                    careLog.reconcileNextFeedPlan(babyId)
                },
            )
        }
    }

    internal fun dismissNextFeedPlan() {
        savedStateHandle.remove<Long>(PENDING_NEXT_FEED_BABY_KEY)
        savedStateHandle.remove<Long>(PENDING_NEXT_FEED_SUGGESTED_AT_KEY)
    }

    fun clear(onCleared: () -> Unit = {}) {
        viewModelScope.launch {
            toggleMutex.withLock { applyTransition(TimerState()) }
            onCleared()
        }
    }

    /**
     * Bind an open nursing care plan to the next/current timer session.
     * No-op when an active unrelated timer already owns a different plan or
     * has timer data for another baby; re-open with the same plan is idempotent.
     */
    fun bindCarePlanIfIdle(carePlanId: Long?, babyId: Long?) {
        if (carePlanId == null || carePlanId <= 0L) return
        viewModelScope.launch {
            toggleMutex.withLock {
                val cur = _state.value
                when {
                    cur.carePlanId == carePlanId -> {
                        // Same plan re-open: keep association, do not double-start.
                        if (babyId != null && cur.babyId == null) {
                            applyTransition(cur.copy(babyId = babyId))
                        }
                    }
                    cur.hasTimerData() || cur.carePlanId != null -> {
                        // Active unrelated session — leave it alone (fail closed).
                    }
                    else -> {
                        applyTransition(
                            cur.copy(
                                carePlanId = carePlanId,
                                babyId = babyId ?: cur.babyId,
                                completionClientUuid = cur.completionClientUuid ?: newClientUuid(),
                            ),
                        )
                    }
                }
            }
        }
    }

    private companion object {
        const val PENDING_NEXT_FEED_BABY_KEY = "timer_pending_next_feed_baby"
        const val PENDING_NEXT_FEED_SUGGESTED_AT_KEY = "timer_pending_next_feed_suggested_at"
    }
}
