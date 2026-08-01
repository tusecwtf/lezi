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
import com.lezi.babylog.core.model.TimerHandoffAcceptResult
import com.lezi.babylog.core.model.TimerHandoffSeed
import com.lezi.babylog.core.model.decideTimerHandoffAccept
import com.lezi.babylog.core.model.RecordMediaFiles
import com.lezi.babylog.core.model.mergeTimerCompletionPhotos
import com.lezi.babylog.core.model.nextFeedSuggestedAt
import com.lezi.babylog.core.model.runNextFeedPlanReconciliation
import com.lezi.babylog.core.model.shouldOfferNextFeedPlanForFact
import com.lezi.babylog.core.model.timerDiscardReclaimPaths
import com.lezi.babylog.domain.CareLog
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
    private val completionSaved = TimerCompletionSavedState(savedStateHandle)
    private val _completionUi = MutableStateFlow(completionSaved.restore())
    /**
     * Single source of truth for completion sheet, Saving, errors, next-feed offer, and
     * consumable exit. Host compositions subscribe — no Compose-local dual master.
     */
    internal val completionUi: StateFlow<TimerCompletionUiState> = _completionUi.asStateFlow()
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
                val decision = try {
                    val raw = settings.nursingTimerJson.first()
                    decideTimerRestore(
                        raw = raw,
                        nowElapsed = SystemClock.elapsedRealtime(),
                        nowWall = System.currentTimeMillis(),
                        nowBootCount = currentBootCount(app),
                        activeServiceSession = NursingTimerServiceRuntime.activeSession(),
                        readFailed = false,
                    )
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    serviceController.stop()
                    throw cancelled
                } catch (_: Exception) {
                    // DataStore read / unexpected restore fault: fail-closed empty + stop
                    // (stronger than transition keep-session FAILED; intentional init policy).
                    decideTimerRestore(
                        raw = null,
                        nowElapsed = SystemClock.elapsedRealtime(),
                        nowWall = System.currentTimeMillis(),
                        nowBootCount = currentBootCount(app),
                        activeServiceSession = NursingTimerServiceRuntime.activeSession(),
                        readFailed = true,
                    )
                }
                // Only an in-process service witness with the same session may remain RUNNING.
                // Process restoration has no witness and therefore becomes RECOVERABLE.
                try {
                    persistLocal(decision.state)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    if (decision.shouldStopService) serviceController.stop()
                    throw cancelled
                } catch (_: Exception) {
                    _state.value = decision.state
                }
                if (decision.shouldStopService) {
                    serviceController.stop()
                }
            }
            // After timer restore: converge completion stage (process death).
            // Config change keeps the same VM job; only durable Saving without a live job needs this.
            val stage = rehydrateTimerCompletionUi(_completionUi.value, completionSaved)
            publishCompletion(stage)
            when (
                val resume = decideTimerCompletionResume(
                    stage = stage,
                    hasTimerData = _state.value.hasTimerData(),
                    timerSessionUuid = _state.value.completionClientUuid,
                )
            ) {
                TimerCompletionResumeDecision.None -> Unit
                TimerCompletionResumeDecision.FinishClearTimer -> {
                    // Domain already committed and success was published, but DataStore clear
                    // did not finish — finish the clear without rewriting the Record.
                    applyTransition(TimerState())
                }
                is TimerCompletionResumeDecision.ReplayInFlightSave -> {
                    // Mid-save: replay is idempotent on durable completionClientUuid
                    // (from completion SavedState and/or timer session). Prefer durable
                    // completion photo paths when handoffSeed was wiped fail-closed.
                    launchCompletionSave(
                        draft = resume.draft,
                        markBegin = false,
                        resumeCompletionClientUuid = resume.completionClientUuid,
                        resumeSessionBabyId = resume.sessionBabyId,
                        resumeCompletionPhotoPaths = resume.completionPhotoPaths,
                    )
                }
                is TimerCompletionResumeDecision.FailClosedRetryable -> {
                    // Session missing mid-save (e.g. fail-closed empty timer + durable Saving):
                    // never promote to pendingExit — keep retryable sheet.
                    publishCompletion(
                        timerCompletionSaveFailed(
                            stage.copy(
                                draft = resume.draft,
                                completionClientUuid = resume.completionClientUuid,
                                sessionBabyId = resume.sessionBabyId,
                            ),
                            resume.error,
                        ),
                    )
                }
            }
        }
    }

    private fun publishCompletion(next: TimerCompletionUiState) {
        completionSaved.persist(next)
        _completionUi.value = next
    }

    private suspend fun persistLocal(s: TimerState) {
        // Seed-only / plan-bound sessions must survive process death before L/R start
        // (Ticket 09 AC: handoffSeed DataStore-restored across rebuild).
        settings.setNursingTimerJson(
            if (s.shouldPersistTimerSession()) {
                s.toJson(savedBootCount = currentBootCount(app))
            } else {
                null
            },
        )
        _state.value = s
    }

    private fun publishMemoryOnly(s: TimerState) {
        _state.value = s
    }

    private suspend fun applyTransition(next: TimerState) {
        if (!next.leftRunning && !next.rightRunning) {
            settleNonRunningTimerTransition(
                next = next,
                publish = ::persistLocal,
                stopService = { serviceController.stop() },
                publishMemoryOnly = ::publishMemoryOnly,
            )
            return
        }
        try {
            startTimerWithConfirmation(
                candidate = next,
                publish = ::persistLocal,
                startService = { serviceController.startAndConfirm(next) },
                stopService = { serviceController.stop() },
                publishMemoryOnly = ::publishMemoryOnly,
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            // startTimerWithConfirmation already stopped; belt-and-suspenders for outer cancel.
            serviceController.stop()
            throw cancelled
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
    ): NursingCompletionDraft {
        val seed = _state.value.handoffSeed
        return freezeNursingCompletion(
            state = _state.value,
            nowElapsed = SystemClock.elapsedRealtime(),
            clickedAt = System.currentTimeMillis(),
            initialNote = seed?.note?.takeIf { it.isNotBlank() } ?: initialNote,
            initialAmountMl = seed?.amountMl?.takeIf { it.isNotBlank() } ?: initialAmountMl,
        )
    }

    /** Freeze current timer and open the completion sheet (VM-owned draft). */
    internal fun openCompletion(
        initialNote: String = "",
        initialAmountMl: String = "",
    ) {
        val draft = freezeCompletion(
            initialNote = initialNote,
            initialAmountMl = initialAmountMl,
        )
        val session = _state.value
        // Freeze seed photo paths with the sheet so mid-Saving process death still
        // attaches handoff imports after fail-closed empty timer restore.
        val frozenPhotoPaths = session.handoffSeed?.orderedPaths
        publishCompletion(
            openTimerCompletionSheet(
                current = _completionUi.value,
                draft = draft,
                completionClientUuid = session.completionClientUuid,
                sessionBabyId = session.babyId,
                completionPhotoPaths = frozenPhotoPaths,
            ),
        )
    }

    internal fun updateCompletionDraft(draft: NursingCompletionDraft) {
        publishCompletion(updateTimerCompletionDraft(_completionUi.value, draft))
    }

    internal fun dismissCompletion() {
        publishCompletion(dismissTimerCompletionSheet(_completionUi.value))
    }

    /**
     * Confirm the completion sheet. Results are published on [completionUi] — not via
     * composition-captured callbacks — so any subscriber after config change consumes them.
     * When already Saving, this is a no-op (same busy state; no second coroutine/Record).
     */
    internal fun confirmCompletion(draft: NursingCompletionDraft) {
        if (!mayStartTimerCompletionSave(_completionUi.value)) return
        if (!completionInFlight.compareAndSet(false, true)) return
        draft.validationError(System.currentTimeMillis())?.let { error ->
            completionInFlight.set(false)
            publishCompletion(
                timerCompletionValidationFailed(_completionUi.value, draft, error),
            )
            return
        }
        publishCompletion(
            beginTimerCompletionSave(
                current = _completionUi.value,
                draft = draft,
                completionPhotoPaths = _state.value.handoffSeed?.orderedPaths,
            ),
        )
        launchCompletionSave(draft, markBegin = true)
    }

    /**
     * @param markBegin when false, caller already published Saving (process-death resume);
     *   still takes [completionInFlight] so a second confirm cannot start a parallel job.
     * @param resumeCompletionClientUuid / [resumeSessionBabyId] from durable completion
     *   SavedState when timer DataStore is empty after fail-closed restore.
     */
    private fun launchCompletionSave(
        draft: NursingCompletionDraft,
        markBegin: Boolean,
        resumeCompletionClientUuid: String? = null,
        resumeSessionBabyId: Long? = null,
        resumeCompletionPhotoPaths: List<String>? = null,
    ) {
        if (!markBegin && !completionInFlight.compareAndSet(false, true)) {
            return
        }
        viewModelScope.launch {
            try {
                toggleMutex.withLock {
                    val stableState = _state.value
                    val completionUiSnapshot = _completionUi.value
                    val babyId = stableState.babyId
                        ?: resumeSessionBabyId
                        ?: completionUiSnapshot.sessionBabyId
                        ?: careLog.getCurrentBaby()?.id
                    if (babyId == null) {
                        publishCompletion(
                            timerCompletionSaveFailed(_completionUi.value, "请先添加宝宝"),
                        )
                        return@launch
                    }
                    val completionClientUuid = stableState.completionClientUuid
                        ?: resumeCompletionClientUuid
                        ?: completionUiSnapshot.completionClientUuid
                    if (completionClientUuid.isNullOrBlank()) {
                        publishCompletion(
                            timerCompletionSaveFailed(
                                _completionUi.value,
                                TIMER_COMPLETION_SESSION_EXPIRED_MESSAGE,
                            ),
                        )
                        return@launch
                    }
                    val carePlanId = stableState.carePlanId ?: draft.carePlanId
                    val command = draft.toCommand()
                    val currentSettings = settings.settings.first()
                    val recordMode = currentSettings.recordAtStartOrEnd
                    val seed = stableState.handoffSeed
                    // Prefer durable frozen paths (survive fail-closed seed wipe) over live seed.
                    val seedPhotoPaths = completionUiSnapshot.completionPhotoPaths
                        ?: resumeCompletionPhotoPaths
                        ?: seed?.orderedPaths.orEmpty()
                    val livePlanPhotos = if (carePlanId != null) {
                        careLog.listCarePlanPhotoPaths(carePlanId)
                    } else {
                        emptyList()
                    }
                    val completionPhotos = mergeTimerCompletionPhotos(
                        seedPhotoPaths = seedPhotoPaths,
                        livePlanPhotoPaths = livePlanPhotos,
                    )
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
                        carePlanId = carePlanId,
                        photoLocalPaths = completionPhotos.takeIf { it.isNotEmpty() },
                    )
                    val offerNextFeedPlan = shouldOfferNextFeedPlanForFact(
                        type = RecordType.NURSING,
                        createdNewFact = true,
                        sourceCarePlanId = carePlanId,
                    )
                    val pendingNextFeed = if (offerNextFeedPlan) {
                        TimerPendingNextFeed(
                            babyId = babyId,
                            suggestedAt = nextFeedSuggestedAt(
                                nowMillis = RecordTime.currentTimeMillis(),
                                intervalMinutes = currentSettings.nursingIntervalMin,
                            ),
                        )
                    } else {
                        null
                    }
                    // Domain commit succeeded. Durable post-save stage first so process death
                    // rehydrates next-feed/exit (not Saving), then clear timer snapshot.
                    withContext(NonCancellable) {
                        publishCompletion(
                            timerCompletionSucceeded(
                                _completionUi.value,
                                pendingNextFeed = pendingNextFeed,
                            ),
                        )
                        // If the process dies before this commits, replay uses the same
                        // completionClientUuid and CareLog returns the existing record; init also
                        // finishes the clear when post-save stage is already published.
                        applyTransition(TimerState())
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                publishCompletion(
                    timerCompletionSaveFailed(
                        _completionUi.value,
                        productUiError(throwable, "保存失败"),
                    ),
                )
            } finally {
                completionInFlight.set(false)
            }
        }
    }

    /** Host acknowledged one-shot exit after success (no-offer or post next-feed). */
    internal fun acknowledgeCompletionExit() {
        publishCompletion(consumeTimerPendingExit(_completionUi.value))
    }

    internal fun scheduleNextFeedPlan(atMillis: Long, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val success = try {
                val babyId = requireNotNull(
                    completionSaved.pendingNextFeed()?.babyId
                        ?: _completionUi.value.pendingNextFeed?.babyId,
                ) {
                    "待安排的喂养记录已失效"
                }
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
                        completionSaved.pendingNextFeed()?.babyId
                            ?: _completionUi.value.pendingNextFeed?.babyId,
                    ) {
                        "待核对的喂养记录已失效"
                    }
                    careLog.reconcileNextFeedPlan(babyId)
                },
            )
        }
    }

    /**
     * Next-feed flow finished (scheduled or skipped). Publishes durable pendingExit so
     * [TimerRoute] LaunchedEffect owns navigation — same consumable exit as no-offer success.
     */
    internal fun dismissNextFeedPlan() {
        publishCompletion(finishTimerNextFeedToExit(_completionUi.value))
    }

    fun clear(onCleared: () -> Unit = {}) {
        viewModelScope.launch {
            val reclaim = toggleMutex.withLock {
                val seed = _state.value.handoffSeed
                applyTransition(TimerState())
                timerDiscardReclaimPaths(seed)
            }
            reclaimComposerOwnedHandoffFiles(reclaim)
            onCleared()
        }
    }

    /**
     * Accept a Composer→Timer ownership handoff. Idempotent for the same
     * [TimerHandoffSeed.handoffId]; rejects when another session already owns the timer.
     */
    suspend fun acceptHandoffSeed(seed: TimerHandoffSeed): TimerHandoffAcceptResult =
        toggleMutex.withLock {
            val cur = _state.value
            val decision = decideTimerHandoffAccept(
                seed = seed,
                existingHandoffId = cur.handoffSeed?.handoffId,
                boundCarePlanId = cur.carePlanId,
                boundBabyId = cur.babyId,
                hasSessionData = cur.hasTimerData(),
            )
            when (decision) {
                TimerHandoffAcceptResult.AlreadyAccepted -> decision
                TimerHandoffAcceptResult.RejectedConflict -> decision
                is TimerHandoffAcceptResult.Accepted -> {
                    applyTransition(
                        cur.copy(
                            babyId = decision.stateBabyId,
                            carePlanId = decision.stateCarePlanId,
                            handoffSeed = seed,
                            completionClientUuid = cur.completionClientUuid ?: newClientUuid(),
                        ),
                    )
                    decision
                }
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
                    cur.hasTimerData() || cur.carePlanId != null || cur.handoffSeed != null -> {
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

    private suspend fun reclaimComposerOwnedHandoffFiles(paths: Collection<String>) {
        if (paths.isEmpty()) return
        withContext(Dispatchers.IO) {
            RecordMediaFiles.deleteUnderAllowedRoot(
                filesDir = app.filesDir,
                paths = paths,
            )
        }
    }
}
