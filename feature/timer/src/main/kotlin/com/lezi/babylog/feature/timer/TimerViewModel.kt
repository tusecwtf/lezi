package com.lezi.babylog.feature.timer

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lezi.babylog.core.common.newClientUuid
import com.lezi.babylog.core.common.productUiError
import com.lezi.babylog.core.datastore.SettingsStore
import com.lezi.babylog.domain.CareLog
import com.lezi.babylog.core.model.RecordType
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
    @ApplicationContext private val app: Context,
) : ViewModel() {
    private val _state = MutableStateFlow(TimerState())
    val state: StateFlow<TimerState> = _state
    private val completionInFlight = AtomicBoolean(false)
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
                )
                _state.value = restored
                // A restored running snapshot is deliberately frozen by fromJson; reconcile any
                // surviving service notification with that authoritative paused state.
                updateService(restored)
            }
        }
    }

    private suspend fun persist(s: TimerState) {
        settings.setNursingTimerJson(
            if (!s.hasTimerData()) {
                null
            } else {
                s.toJson(savedBootCount = currentBootCount(app))
            },
        )
        _state.value = s
        updateService(s)
    }

    private fun updateService(s: TimerState) {
        val running = s.leftRunning || s.rightRunning
        val intent = Intent(app, NursingTimerService::class.java)
        if (running) {
            val snapshotElapsed = SystemClock.elapsedRealtime()
            intent.action = NursingTimerService.ACTION_UPDATE
            intent.putExtra(NursingTimerService.EXTRA_LEFT_MS, s.leftMs(snapshotElapsed))
            intent.putExtra(NursingTimerService.EXTRA_RIGHT_MS, s.rightMs(snapshotElapsed))
            intent.putExtra(NursingTimerService.EXTRA_LEFT_RUNNING, s.leftRunning)
            intent.putExtra(NursingTimerService.EXTRA_RIGHT_RUNNING, s.rightRunning)
            intent.putExtra(NursingTimerService.EXTRA_SNAPSHOT_ELAPSED, snapshotElapsed)
            ContextCompat.startForegroundService(app, intent)
        } else {
            app.stopService(Intent(app, NursingTimerService::class.java))
        }
    }

    fun toggleLeft() {
        viewModelScope.launch {
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
                persist(next)
            }
        }
    }

    fun toggleRight() {
        viewModelScope.launch {
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
                persist(next)
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
        onDone: (offerNextFeedPlan: Boolean) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (!completionInFlight.compareAndSet(false, true)) return
        viewModelScope.launch {
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
                    val recordMode = settings.settings.first().recordAtStartOrEnd
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
                    val offerNextFeedPlan = timerShouldOfferNextFeedPlan(stableState.carePlanId)
                    if (offerNextFeedPlan) {
                        savedStateHandle[PENDING_NEXT_FEED_BABY_KEY] = babyId
                    } else {
                        savedStateHandle.remove<Long>(PENDING_NEXT_FEED_BABY_KEY)
                    }
                    // Await the DataStore clear. If the process dies before it commits, replay uses
                    // the same completionClientUuid and CareLog returns the existing record.
                    persist(TimerState())
                }
                onDone(savedStateHandle.get<Long>(PENDING_NEXT_FEED_BABY_KEY) != null)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                onError(productUiError(throwable, "保存失败"))
            } finally {
                completionInFlight.set(false)
            }
        }
    }

    internal fun scheduleNextFeedPlan(atMillis: Long?, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val success = runCatching {
                val babyId = requireNotNull(
                    savedStateHandle.get<Long>(PENDING_NEXT_FEED_BABY_KEY),
                ) { "待安排的喂养记录已失效" }
                val scheduledAt = atMillis ?: run {
                    val intervalMin = settings.settings.first().nursingIntervalMin
                    System.currentTimeMillis() + intervalMin * 60_000L
                }
                careLog.scheduleNextFeedCarePlan(
                    babyId = babyId,
                    feedType = RecordType.NURSING,
                    scheduledAt = scheduledAt,
                )
                savedStateHandle.remove<Long>(PENDING_NEXT_FEED_BABY_KEY)
            }.isSuccess
            onResult(success)
        }
    }

    internal fun dismissNextFeedPlan() {
        savedStateHandle.remove<Long>(PENDING_NEXT_FEED_BABY_KEY)
    }

    fun clear(onCleared: () -> Unit = {}) {
        viewModelScope.launch {
            toggleMutex.withLock { persist(TimerState()) }
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
                            persist(cur.copy(babyId = babyId))
                        }
                    }
                    cur.hasTimerData() || cur.carePlanId != null -> {
                        // Active unrelated session — leave it alone (fail closed).
                    }
                    else -> {
                        persist(
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
    }
}

internal fun timerShouldOfferNextFeedPlan(carePlanId: Long?): Boolean = carePlanId == null
