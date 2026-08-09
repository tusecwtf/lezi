package com.lezi.babylog.feature.log.composer
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lezi.babylog.core.common.newClientUuid
import com.lezi.babylog.core.common.productUiError
import com.lezi.babylog.core.datastore.SettingsStore
import com.lezi.babylog.core.model.NextFeedPlanReconciliation
import com.lezi.babylog.core.model.RecordTime
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.TimerHandoffBuildResult
import com.lezi.babylog.core.model.TimerHandoffSeed
import com.lezi.babylog.core.model.UntransferableTimerField
import com.lezi.babylog.core.model.nextFeedSuggestedAt
import com.lezi.babylog.core.model.runNextFeedPlanReconciliation
import com.lezi.babylog.core.model.shouldOfferNextFeedPlanForFact
import com.lezi.babylog.core.ui.presentation
import com.lezi.babylog.domain.CareLog
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
import kotlinx.coroutines.withContext
import com.lezi.babylog.feature.log.*
import com.lezi.babylog.feature.log.timeline.*
import com.lezi.babylog.feature.log.dock.*
import com.lezi.babylog.feature.log.layout.*
import com.lezi.babylog.feature.log.photo.*

@HiltViewModel
class RecordComposerViewModel @Inject constructor(
    private val careLog: CareLog,
    private val settingsStore: SettingsStore,
    private val photoStore: RecordPhotoStore,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val sessionGate = RecordComposerSessionGate()
    private val savedState = RecordComposerSavedState(savedStateHandle)
    private val photoLifecycle = RecordComposerPhotoLifecycle(photoStore::delete)
    private val importSave = RecordComposerImportSaveSerialization()
    private val _state = MutableStateFlow(
        // Process recreation: restore durable post-save stage before any composition subscribes.
        run {
            val restored = rehydrateComposerPostSaveStage(
                pendingNextFeedOffer = null,
                pendingFinishMessage = null,
                savedState = savedState,
            )
            RecordComposerUiState(
                pendingNextFeedOffer = restored.pendingNextFeedOffer,
                pendingFinishMessage = restored.pendingFinishMessage,
            )
        },
    )
    internal val state = _state.asStateFlow()
    private var loadJob: Job? = null
    private var actionJob: Job? = null
    private var importJob: Job? = null
    private var settingsObserveJob: Job? = null

    internal fun open(request: RecordComposerRequest) {
        val current = _state.value
        // Refuse re-arming restorable write identity while a durable post-save stage is live.
        // Process death after fact commit may still present Activity root New; open must not
        // re-initialize a draft under a pending next-feed offer (AC: 不能重复写事实).
        val livePostSave = rehydrateComposerPostSaveStage(
            pendingNextFeedOffer = current.pendingNextFeedOffer,
            pendingFinishMessage = current.pendingFinishMessage,
            savedState = savedState,
        )
        if (livePostSave.isActive) {
            _state.value = recordComposerClosedUiState(
                pendingNextFeedOffer = livePostSave.pendingNextFeedOffer,
                pendingFinishMessage = livePostSave.pendingFinishMessage,
            )
            return
        }
        savedState.pendingWrite()?.let { pending ->
            if (current.activeRequest == pending.request && current.saving) return
            resumePendingWrite(pending)
            return
        }
        if (current.activeRequest == request && (current.loading || current.draft != null)) return
        val restoredDraft = savedState.restore(request)
        val restoredInitialDraft = savedState.restoreInitial(request)
        val session = sessionGate.open()
        val (openOrphans, previousImport) = beginImportPreemption()
        importJob = null
        loadJob?.cancel()
        actionJob?.cancel()
        settingsObserveJob?.cancel()
        _state.value = RecordComposerUiState(
            activeRequest = request,
            loading = true,
            // Keep mid-handoff lock during load so process-death restore cannot
            // unlock dismiss before draft + pending seed rehydrate.
            timerHandoffInFlight = savedState.timerHandoffInFlight(),
        )
        loadJob = viewModelScope.launch {
            // Mirror save: join cancelled import before reset so late produce is reclaimed.
            // NonCancellable so a rapid re-open cannot drop orphan cleanup mid-join.
            withContext(NonCancellable) {
                joinAndReclaimCancelledImport(
                    importSave = importSave,
                    preemptedOrphans = openOrphans,
                    importJob = previousImport,
                    delete = photoStore::delete,
                )
                importSave.reset()
            }
            val settings = try {
                settingsStore.settings.first()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                currentCoroutineContext().ensureActive()
                sessionGate.deliver(session) {
                    val stage = currentPostSaveStage()
                    _state.value = RecordComposerUiState(
                        activeRequest = request,
                        error = productUiError(error, "记录设置加载失败"),
                        pendingNextFeedOffer = stage.pendingNextFeedOffer,
                        pendingFinishMessage = stage.pendingFinishMessage,
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
                        // Hydrate plan photos as ordered borrowed refs. They remain owned by the
                        // plan and are never physical-cleanup candidates for this draft.
                        val draft = photoLifecycle.fulfillmentDraft(
                            base = QuickRecordDraft.fromCarePlan(plan),
                            planPhotos = planPhotos,
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
                    val stage = currentPostSaveStage()
                    _state.value = RecordComposerUiState(
                        activeRequest = request,
                        amountStepMl = settings.amountStepMl,
                        timeStepMin = settings.timeStepMin,
                        timePickerStyle = settings.timePickerStyle,
                        preferredHand = settings.preferredHand,
                        infantFeverAdviceEnabled = settings.infantFeverAdviceEnabled,
                        error = productUiError(error, "记录加载失败"),
                        pendingNextFeedOffer = stage.pendingNextFeedOffer,
                        pendingFinishMessage = stage.pendingFinishMessage,
                    )
                }
                return@launch
            }
            currentCoroutineContext().ensureActive()
            val (babyId, birthdayEpochDay, draft) = loaded
            sessionGate.deliver(session) {
                val initialDraft = restoredInitialDraft ?: draft
                val activeDraft = restoredDraft ?: draft
                val systemCalConfigured = settings.systemCalendarEnabled &&
                    !settings.systemCalendarId.isNullOrBlank()
                // Refuse initialize if a post-save stage appeared mid-load (e.g. concurrent path).
                val stage = currentPostSaveStage()
                if (stage.isActive) {
                    _state.value = recordComposerClosedUiState(
                        pendingNextFeedOffer = stage.pendingNextFeedOffer,
                        pendingFinishMessage = stage.pendingFinishMessage,
                    )
                    return@deliver
                }
                _state.value = RecordComposerUiState(
                    activeRequest = request,
                    draft = activeDraft,
                    initialDraft = initialDraft,
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
                    // Restore mid-handoff lock across process death (Ticket 09).
                    timerHandoffInFlight = savedState.timerHandoffInFlight(),
                )
                savedState.initialize(request, initialDraft, activeDraft)
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
        closeInternal(transferredOwnedPaths = emptySet())
    }

    /**
     * Mark Composer→Timer handoff in flight before shell navigates. Durable in
     * SavedState so process death cannot unlock dismiss/cleanup while Timer still
     * owns (or is accepting) the seed.
     */
    internal fun beginTimerHandoff(seed: TimerHandoffSeed) {
        savedState.savePendingTimerHandoff(seed)
        _state.update { cur -> cur.copy(timerHandoffInFlight = true) }
    }

    /** Pending seed restored after process death; null when no handoff is open. */
    internal fun pendingTimerHandoffSeed(): TimerHandoffSeed? = savedState.pendingTimerHandoffSeed()

    /**
     * Timer rejected or user left timer before accept. Keep draft + owned files
     * editable; clear only the durable in-flight lock.
     */
    internal fun cancelTimerHandoff() {
        savedState.clearPendingTimerHandoff()
        _state.update { cur -> cur.copy(timerHandoffInFlight = false) }
    }

    /**
     * Close after Timer has accepted [seed]. Transferred Composer-owned photo
     * bytes are retained for Timer; only non-transferred owned orphans are reclaimed.
     * Idempotent when draft/pending already cleared (durable re-delivery safe).
     */
    internal fun closeAfterTimerHandoff(seed: TimerHandoffSeed) {
        val draftForCleanup = _state.value.draft ?: savedState.draftForCleanup()
        val pending = savedState.pendingTimerHandoffSeed()
        if (draftForCleanup == null && pending == null && !_state.value.timerHandoffInFlight) {
            return
        }
        closeInternal(transferredOwnedPaths = seed.composerOwnedPaths.toSet())
    }

    private fun closeInternal(transferredOwnedPaths: Set<String>) {
        val draftForCleanup = _state.value.draft ?: savedState.draftForCleanup()
        val (preemptedOrphans, previousImport) = beginImportPreemption()
        importJob = null
        // Preserve durable next-feed offer / finish copy across sheet close and config change.
        val stage = currentPostSaveStage()
        savedState.clear()
        sessionGate.close()
        loadJob?.cancel()
        actionJob?.cancel()
        settingsObserveJob?.cancel()
        loadJob = null
        actionJob = null
        settingsObserveJob = null
        _state.value = recordComposerClosedUiState(
            pendingNextFeedOffer = stage.pendingNextFeedOffer,
            pendingFinishMessage = stage.pendingFinishMessage,
        )
        // Join import before reset/drain so cancel-after-write paths are not dropped.
        viewModelScope.launch(NonCancellable) {
            joinAndReclaimCancelledImport(
                importSave = importSave,
                preemptedOrphans = preemptedOrphans,
                importJob = previousImport,
                delete = photoStore::delete,
            )
            importSave.reset()
            if (draftForCleanup != null) {
                // Empty keep set reclaims all owned candidates (same as cleanupAbandoned).
                photoLifecycle.releaseForTimerHandoff(
                    draft = draftForCleanup,
                    transferredOwnedPaths = transferredOwnedPaths,
                )
            }
        }
    }

    /**
     * Build a Timer handoff seed from the live draft. Queries current plan media
     * when fulfilling so overflow is detected against Ticket 08 live plan photos.
     */
    internal suspend fun buildTimerHandoffFromOpenDraft(): TimerHandoffBuildResult? {
        val cur = _state.value
        val draft = cur.draft ?: return null
        val babyId = cur.babyId ?: return null
        if (babyId <= 0L) return null
        val carePlanId = draft.carePlanId?.takeUnless { draft.editCarePlan }
        val livePlanPhotos = if (carePlanId != null) {
            runCatching { careLog.listCarePlanPhotoPaths(carePlanId) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        return prepareTimerHandoffSeed(
            handoffId = newClientUuid(),
            babyId = babyId,
            draft = draft,
            livePlanPhotoPaths = livePlanPhotos,
        )
    }

    internal fun untransferableFieldsForOpenDraft(): Set<UntransferableTimerField> {
        val cur = _state.value
        val draft = cur.draft ?: return emptySet()
        val baseline = cur.initialDraft ?: return emptySet()
        return untransferableFieldsForTimerHandoff(draft, baseline)
    }

    internal fun updateDraft(draft: QuickRecordDraft) {
        if (_state.value.let { it.saving || it.deleting || it.timerHandoffInFlight }) return
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
            savedState.update(request, draft)
        }
    }

    internal fun importPhotos(uris: List<Uri>) {
        val draft = _state.value.draft ?: return
        if (uris.isEmpty()) return
        if (!importSave.allowImport()) return
        val session = sessionGate.current() ?: return
        val begin = importSave.beginImport() ?: return
        // Last-wins: cancel any prior in-flight import before starting this one.
        val previousImport = importJob
        previousImport?.cancel()
        val slots = uris.take(RecordPhotoChrome.remainingSlots(draft.photos.size))
        importJob = viewModelScope.launch {
            previousImport?.join()
            // beginImport already handed prior unattached via supersededUnattached; after
            // join, also drain late produce that landed under a non-current epoch (e.g.
            // markProduced after reclaimEpoch emptied the superseded slot).
            val lateUnattached = importSave.drainNonCurrentUnattached()
            val supersededOrphans = (begin.supersededUnattached + lateUnattached).distinct()
            if (supersededOrphans.isNotEmpty()) {
                photoStore.delete(supersededOrphans)
            }
            try {
                runComposerPhotoImport(
                    importSave = importSave,
                    epoch = begin.epoch,
                    import = { onPathCommitted ->
                        photoStore.import(slots, onPathCommitted = onPathCommitted)
                    },
                    delete = photoStore::delete,
                    attach = { imported ->
                        var attached = false
                        sessionGate.deliver(session) {
                            if (!importSave.isCurrent(begin.epoch)) return@deliver
                            val current = _state.value.draft ?: return@deliver
                            _state.update {
                                it.copy(
                                    draft = photoLifecycle.imported(current, imported),
                                    error = null,
                                )
                            }
                            if (importSave.markAttached(begin.epoch)) {
                                persistCurrentDraft()
                                attached = true
                            }
                        }
                        attached
                    },
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                currentCoroutineContext().ensureActive()
                sessionGate.deliver(session) {
                    _state.update { it.copy(error = productUiError(error, "图片导入失败")) }
                }
            }
        }
    }

    internal fun removePhoto(path: String) {
        val draft = _state.value.draft ?: return
        val nextDraft = photoLifecycle.removed(draft, path)
        _state.update { it.copy(draft = nextDraft) }
        persistCurrentDraft()
        viewModelScope.launch { photoLifecycle.cleanupRemoved(draft, nextDraft) }
    }

    /**
     * Persist the open draft. On domain success, publishes [RecordComposerUiState.pendingNextFeedOffer]
     * or [RecordComposerUiState.pendingFinishMessage] as the single observable post-save stage and
     * immediately consumes the restorable request so process death cannot rewrite the fact.
     * Host compositions subscribe to state — late callbacks must not mutate dead Compose locals.
     */
    internal fun save() {
        val snapshot = _state.value
        val draftForValidation = snapshot.draft ?: return
        val babyId = snapshot.babyId ?: return
        val request = snapshot.activeRequest ?: return
        if (snapshot.saving || snapshot.deleting) return
        val nowMillis = RecordTime.currentTimeMillis()
        val validation = draftForValidation.validationError(nowMillis)
        if (validation != null) {
            _state.update { it.copy(error = validation) }
            return
        }
        val session = sessionGate.current() ?: return
        val pending = freezeComposerWrite(
            request = request,
            babyId = babyId,
            draft = draftForValidation,
            confirmedAtMillis = nowMillis,
            clientUuid = newClientUuid(),
        )
        // Persist identity + exact command before any cancellable import join or domain IO.
        savedState.savePendingWrite(pending)
        // Save-priority: lock imports, cancel in-flight import, then join+reclaim in the job.
        val (preemptedOrphans, cancelledImport) = beginExclusiveCommit()
        _state.update { it.copy(saving = true, error = null) }
        launchPendingWrite(pending, session, preemptedOrphans, cancelledImport)
    }

    private fun resumePendingWrite(pending: ComposerPendingWrite) {
        val session = sessionGate.open()
        val (preemptedOrphans, cancelledImport) = beginExclusiveCommit()
        importJob = null
        loadJob?.cancel()
        actionJob?.cancel()
        settingsObserveJob?.cancel()
        loadJob = null
        settingsObserveJob = null
        _state.value = recordComposerPendingWriteUiState(pending)
        launchPendingWrite(pending, session, preemptedOrphans, cancelledImport)
    }

    private fun launchPendingWrite(
        pending: ComposerPendingWrite,
        session: RecordComposerSessionToken,
        preemptedOrphans: List<String>,
        cancelledImport: Job?,
    ) {
        actionJob = viewModelScope.launch {
            joinAndReclaimCancelledImport(
                importSave = importSave,
                preemptedOrphans = preemptedOrphans,
                importJob = cancelledImport,
                delete = photoStore::delete,
            )
            importJob = null
            try {
                executePendingWrite(pending)
            } catch (cancelled: CancellationException) {
                importSave.endCommit()
                throw cancelled
            } catch (error: Throwable) {
                currentCoroutineContext().ensureActive()
                importSave.endCommit()
                savedState.clearPendingWrite()
                savedState.initialize(pending.request, pending.draft)
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
            // Domain write succeeded. Post-commit cancel must publish durable stage rather than
            // surface save failure / leave restorable request intact (AC3 / dual-master fix).
            withContext(NonCancellable) {
                try {
                    // Only discarded draft-owned imports are cleaned here. Persisted source paths
                    // are reclaimed by reference-aware domain cleanup after the Room commit.
                    runCatching { photoLifecycle.cleanupAfterCommit(pending.draft) }
                    val message = composerSaveSuccessMessage(
                        writeDecision = pending.writeDecision,
                        draft = pending.draft,
                        commandType = pending.command.type,
                    )
                    val suggestedNextFeedAt = if (
                        shouldOfferNextFeedPlanForFact(
                            type = pending.command.type,
                            createdNewFact =
                                pending.writeDecision == ComposerWriteDecision.AddRecord,
                            sourceCarePlanId = pending.draft.carePlanId,
                        )
                    ) {
                        // Non-cancelling default: CancellationException must not drop the offer.
                        val intervalMinutes = try {
                            settingsStore.settings.first().nursingIntervalMin
                        } catch (_: Throwable) {
                            180
                        }
                        nextFeedSuggestedAt(RecordTime.currentTimeMillis(), intervalMinutes)
                    } else {
                        null
                    }
                    val postSaveOutcome = composerPostSaveOutcome(
                        message = message,
                        suggestedNextFeedAt = suggestedNextFeedAt,
                        babyId = pending.babyId,
                        type = pending.command.type,
                    )
                    // Persist durable stage + clear pending command, then publish observable state.
                    val appliedState = applyComposerPostSaveOutcome(
                        current = _state.value,
                        savedState = savedState,
                        outcome = postSaveOutcome,
                        committedPhotos = pending.draft.photos,
                    )
                    // Publish while this session is still active. Pending already lives in
                    // SavedState when deliver is skipped; recreation rehydrates it.
                    if (!sessionGate.deliver(session) { _state.value = appliedState }) {
                        val durable = rehydrateComposerPostSaveStage(
                            pendingNextFeedOffer = appliedState.pendingNextFeedOffer,
                            pendingFinishMessage = appliedState.pendingFinishMessage,
                            savedState = savedState,
                        )
                        _state.update {
                            it.copy(
                                saving = false,
                                activeRequest = null,
                                pendingNextFeedOffer = durable.pendingNextFeedOffer,
                                pendingFinishMessage = durable.pendingFinishMessage,
                            )
                        }
                    }
                } finally {
                    // Always clear commit lock — sheet close also resets, but a missed deliver
                    // must not leave import blocked.
                    importSave.endCommit()
                }
            }
        }
    }

    private suspend fun executePendingWrite(pending: ComposerPendingWrite) {
        val draft = pending.draft
        val command = pending.command
        val confirmedAt = pending.confirmedAtMillis
        when (pending.writeDecision) {
            ComposerWriteDecision.UpdateCarePlan -> careLog.updateCarePlan(
                carePlanId = requireNotNull(draft.carePlanId),
                scheduledAt = command.timestamp,
                note = command.note,
                payloadJson = command.payloadJson,
                schemaVersion = command.schemaVersion,
                photoLocalPaths = draft.photos,
                nowMillis = confirmedAt,
                projectToSystemCalendar = draft.projectToSystemCalendar,
            )
            ComposerWriteDecision.FulfillCarePlan -> careLog.fulfillCarePlan(
                carePlanId = requireNotNull(draft.carePlanId),
                actualTimestamp = command.timestamp,
                endTimestamp = command.endTimestamp.takeIf { command.type == RecordType.SLEEP },
                note = command.note,
                payloadJson = command.payloadJson,
                schemaVersion = command.schemaVersion,
                photoLocalPaths = draft.photos,
                nowMillis = confirmedAt,
                clientUuid = pending.clientUuid,
            )
            ComposerWriteDecision.ConvertRecordToCarePlan -> careLog.convertRecordToCarePlan(
                recordId = requireNotNull(command.existingRecordId),
                scheduledAt = command.timestamp,
                note = command.note,
                payloadJson = command.payloadJson,
                schemaVersion = command.schemaVersion,
                photoLocalPaths = draft.photos,
                nowMillis = confirmedAt,
                projectToSystemCalendar = draft.projectToSystemCalendar,
                clientUuid = pending.clientUuid,
            )
            ComposerWriteDecision.ConfirmSleep -> careLog.confirmSleep(
                babyId = pending.babyId,
                expectedOpenSleepId = command.existingRecordId,
                timestamp = command.timestamp,
                endTimestamp = command.endTimestamp,
                note = command.note,
                payloadJson = command.payloadJson,
                schemaVersion = command.schemaVersion,
                photoLocalPaths = draft.photos,
                nowMillis = confirmedAt,
                clientUuid = pending.clientUuid,
            )
            ComposerWriteDecision.UpdateRecord -> careLog.updateRecord(
                id = requireNotNull(command.existingRecordId),
                timestamp = command.timestamp,
                endTimestamp = command.endTimestamp,
                note = command.note,
                payloadJson = command.payloadJson,
                schemaVersion = command.schemaVersion,
                photoLocalPaths = draft.photos,
                nowMillis = confirmedAt,
            )
            ComposerWriteDecision.CreateCarePlan -> careLog.createCarePlan(
                babyId = pending.babyId,
                type = command.type,
                scheduledAt = command.timestamp,
                note = command.note,
                payloadJson = command.payloadJson,
                schemaVersion = command.schemaVersion,
                customItemId = draft.customItemId,
                photoLocalPaths = draft.photos,
                nowMillis = confirmedAt,
                projectToSystemCalendar = draft.projectToSystemCalendar,
                clientUuid = pending.clientUuid,
            )
            ComposerWriteDecision.AddRecord -> careLog.addRecord(
                babyId = pending.babyId,
                type = command.type,
                timestamp = command.timestamp,
                endTimestamp = command.endTimestamp,
                note = command.note,
                payloadJson = command.payloadJson,
                schemaVersion = command.schemaVersion,
                photoLocalPaths = draft.photos,
                nowMillis = confirmedAt,
                clientUuid = pending.clientUuid,
            )
        }
    }

    internal fun scheduleNextFeedPlan(atMillis: Long, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val success = try {
                val pending = requireNotNull(savedState.pendingNextFeed()) {
                    "待安排的喂养记录已失效"
                }
                careLog.scheduleNextFeedCarePlan(
                    babyId = pending.babyId,
                    feedType = pending.type,
                    scheduledAt = atMillis,
                )
                true
            } catch (cancelled: CancellationException) {
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
                    val pending = requireNotNull(savedState.pendingNextFeed()) {
                        "待核对的喂养记录已失效"
                    }
                    careLog.reconcileNextFeedPlan(pending.babyId)
                },
            )
        }
    }

    /**
     * User completed or skipped the next-feed flow. Clears durable pending identity and publishes
     * a durable one-shot finish message for the current composition to present (Snackbar / exit).
     */
    internal fun completeNextFeedOffer(finishMessage: String) {
        savedState.clearPendingNextFeed()
        savedState.savePendingFinishMessage(finishMessage)
        _state.update {
            it.copy(
                pendingNextFeedOffer = null,
                pendingFinishMessage = finishMessage,
            )
        }
    }

    /** Host presented [RecordComposerUiState.pendingFinishMessage] once; clear to avoid re-fire. */
    internal fun acknowledgeFinishMessage() {
        savedState.clearPendingFinishMessage()
        _state.update { it.copy(pendingFinishMessage = null) }
    }

    internal fun delete(onDeleted: (String) -> Unit) {
        val snapshot = _state.value
        val draft = snapshot.draft ?: return
        val editPlanId = draft.carePlanId?.takeIf { draft.isEditingCarePlan }
        val recordId = draft.existingRecordId
        if (editPlanId == null && recordId == null) return
        if (snapshot.saving || snapshot.deleting) return
        val session = sessionGate.current() ?: return
        // Same save-priority rule: delete preempts in-flight import and reclaims orphans.
        val (preemptedOrphans, cancelledImport) = beginExclusiveCommit()
        _state.update { it.copy(deleting = true, error = null) }
        actionJob = viewModelScope.launch {
            joinAndReclaimCancelledImport(
                importSave = importSave,
                preemptedOrphans = preemptedOrphans,
                importJob = cancelledImport,
                delete = photoStore::delete,
            )
            importJob = null
            try {
                val deleted = if (editPlanId != null) {
                    careLog.deleteCarePlan(editPlanId)
                } else {
                    careLog.deleteRecord(recordId!!)
                }
                check(deleted) {
                    if (editPlanId != null) "护理计划不存在或已删除" else "记录不存在或已删除"
                }
            } catch (cancelled: CancellationException) {
                importSave.endCommit()
                throw cancelled
            } catch (error: Throwable) {
                currentCoroutineContext().ensureActive()
                importSave.endCommit()
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
            publishComposerDeleteCommit(
                savedState = savedState,
                sessionGate = sessionGate,
                session = session,
                message = if (editPlanId != null) "已删除护理计划" else "已删除记录",
                onDeleted = onDeleted,
                onCommitLockCleared = importSave::endCommit,
            )
        }
    }

    /**
     * Cancel the active import job and return (orphans known so far, cancelled job).
     * Does not join — callers must [joinAndReclaimCancelledImport] before reset/commit body.
     */
    private fun beginImportPreemption(): Pair<List<String>, Job?> {
        val orphans = importSave.preemptImport()
        val job = importJob
        job?.cancel()
        return orphans to job
    }

    /** Save/delete: lock imports + preempt import job. */
    private fun beginExclusiveCommit(): Pair<List<String>, Job?> {
        importSave.beginCommit()
        return beginImportPreemption()
    }

    private fun persistCurrentDraft() {
        val state = _state.value
        val request = state.activeRequest ?: return
        val draft = state.draft ?: return
        savedState.update(request, draft)
    }

    private fun currentPostSaveStage(): ComposerPostSaveStage =
        rehydrateComposerPostSaveStage(
            pendingNextFeedOffer = _state.value.pendingNextFeedOffer,
            pendingFinishMessage = _state.value.pendingFinishMessage,
            savedState = savedState,
        )
}

/** Pure success copy for a completed Composer write (no suspend / settings I/O). */
internal fun composerSaveSuccessMessage(
    writeDecision: ComposerWriteDecision,
    draft: QuickRecordDraft,
    commandType: RecordType,
): String = when (writeDecision) {
    ComposerWriteDecision.UpdateCarePlan -> "已保存护理计划"
    ComposerWriteDecision.FulfillCarePlan -> "已完成护理计划"
    ComposerWriteDecision.ConvertRecordToCarePlan -> {
        val label = if (commandType == RecordType.CUSTOM) {
            draft.customTitle.trim().ifBlank { commandType.presentation.label }
        } else {
            commandType.presentation.label
        }
        "已转为护理计划 · $label"
    }
    ComposerWriteDecision.CreateCarePlan -> {
        val label = if (commandType == RecordType.CUSTOM) {
            draft.customTitle.trim().ifBlank { commandType.presentation.label }
        } else {
            commandType.presentation.label
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
        val label = if (commandType == RecordType.CUSTOM) {
            draft.customTitle.trim().ifBlank { commandType.presentation.label }
        } else {
            commandType.presentation.label
        }
        "已记录$label"
    }
}
