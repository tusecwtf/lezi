package com.lezi.babylog.feature.family.conflict

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import com.lezi.babylog.designsystem.LeziAlertDialog
import com.lezi.babylog.designsystem.LeziDetailTopBar
import com.lezi.babylog.designsystem.LeziTextButtonTone
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.AnnotatedString

import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.lezi.babylog.core.common.newClientUuid
import com.lezi.babylog.core.common.productUiError
import com.lezi.babylog.core.common.LocalOpFailureCopy
import com.lezi.babylog.designsystem.LeziPrimaryButton
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziSurfacePanel
import com.lezi.babylog.designsystem.LeziTextButton
import com.lezi.babylog.designsystem.LeziTypography
import com.lezi.babylog.domain.CareLog
import com.lezi.babylog.domain.carelog.ConflictResolveOutcome
import com.lezi.babylog.domain.carelog.ConflictResolverLoad
import com.lezi.babylog.domain.carelog.ConflictResolverAudience
import com.lezi.babylog.domain.carelog.ConflictResolverAvailability
import com.lezi.babylog.domain.carelog.ConflictResolverChoiceResult
import com.lezi.babylog.domain.carelog.ConflictResolverDraft
import com.lezi.babylog.domain.carelog.ConflictResolverPath
import com.lezi.babylog.domain.carelog.ConflictResolverTerminalDisposition
import com.lezi.babylog.sync.SyncPort
import com.lezi.babylog.sync.backend.ConflictResolveRequest
import com.lezi.babylog.sync.backend.ConflictWithdrawRequest
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.sync.session.PolicyClock
import com.lezi.babylog.sync.session.SyncSessionPresentation
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import com.lezi.babylog.domain.carelog.ConflictResolverVersion
import com.lezi.babylog.domain.carelog.ConflictResolverVersionKind
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface ConflictEditTarget {
    data class Record(val recordId: Long) : ConflictEditTarget
    data class CarePlan(val carePlanId: Long) : ConflictEditTarget
    data class Baby(val babyId: Long) : ConflictEditTarget
}

data class ConflictResolverUiState(
    val conflictId: String? = null,
    val draft: ConflictResolverDraft? = null,
    val submitting: Boolean = false,
    val resolvedConflictId: String? = null,
    val phase: ConflictResolverPhase = ConflictResolverPhase.Idle,
    val requiresFreshSnapshot: Boolean = false,
    val notice: String? = null,
    val confirmingDelete: Boolean = false,
    val pendingEdit: ConflictEditTarget? = null,
    val continueEditAfterResolve: Boolean = false,
) {
    val canChoose: Boolean
        get() = phase == ConflictResolverPhase.Complete &&
            draft?.canChoose == true && !submitting

    val canSubmit: Boolean
        get() = when (val currentPhase = phase) {
            ConflictResolverPhase.Complete -> true
            is ConflictResolverPhase.Error -> currentPhase.retry == ConflictResolverRetry.Submit
            else -> false
        } && draft?.canSubmit == true && !submitting

    val canWithdraw: Boolean
        get() = phase == ConflictResolverPhase.Complete &&
            draft?.canWithdraw == true && !submitting
}

sealed interface ConflictResolverPhase {
    data object Idle : ConflictResolverPhase

    data object Loading : ConflictResolverPhase

    data object Complete : ConflictResolverPhase

    data class Offline(val message: String) : ConflictResolverPhase

    data class Stale(val message: String) : ConflictResolverPhase

    data class Refreshing(val message: String) : ConflictResolverPhase

    data class Applying(val message: String) : ConflictResolverPhase

    data class Error(
        val message: String,
        val retry: ConflictResolverRetry,
    ) : ConflictResolverPhase
}

enum class ConflictResolverRetry { None, Refresh, Submit }

internal data class ConflictResolverAttempt(
    val generation: Long,
    val conflictId: String,
)

/** Rejects completions from a dismissed or superseded resolver attempt. */
internal class ConflictResolverAttemptGate {
    private var generation = 0L
    private var conflictId: String? = null

    fun begin(conflictId: String): ConflictResolverAttempt {
        generation += 1
        this.conflictId = conflictId
        return ConflictResolverAttempt(generation, conflictId)
    }

    fun current(conflictId: String): ConflictResolverAttempt? =
        conflictId.takeIf { it == this.conflictId }?.let { ConflictResolverAttempt(generation, it) }

    fun accepts(attempt: ConflictResolverAttempt): Boolean =
        attempt.generation == generation && attempt.conflictId == conflictId

    fun invalidate() {
        generation += 1
        conflictId = null
    }
}

@HiltViewModel
class ConflictResolverHost internal constructor(
    private val loadConflict: suspend (String, Boolean) -> ConflictResolverLoad?,
    private val resolveConflict: suspend (String, ConflictResolveRequest) -> ConflictResolveOutcome,
    private val withdrawConflict: suspend (String, ConflictWithdrawRequest) -> ConflictResolveOutcome =
        { _, _ -> ConflictResolveOutcome.Rejected("not_implemented", "撤回未实现") },
    private val sessions: Flow<SyncSessionPresentation>,
    private val nowMillis: () -> Long,
    savedStateHandle: SavedStateHandle,
    private val actorNames: Flow<Map<String, String>> = kotlinx.coroutines.flow.flowOf(emptyMap()),
    private val observeStillOpen: (String) -> Flow<Boolean> = { kotlinx.coroutines.flow.flowOf(false) },
    private val lookupEdit: suspend (com.lezi.babylog.sync.conflict.ConflictRootType, String) -> ConflictEditTarget? =
        { _, _ -> null },
) : ViewModel() {
    @Inject
    constructor(
        careLog: CareLog,
        syncPort: SyncPort,
        clock: PolicyClock,
        savedStateHandle: SavedStateHandle,
    ) : this(
        loadConflict = careLog::loadConflictDetail,
        resolveConflict = careLog::resolveConflict,
        withdrawConflict = careLog::withdrawConflictBranches,
        sessions = syncPort.sessionPresentation(),
        nowMillis = clock::nowMillis,
        savedStateHandle = savedStateHandle,
        actorNames = syncPort.familyMemberDirectory().map { members ->
            members.asSequence()
                .map { it.membershipId.trim() to it.displayName.trim() }
                .filter { (id, name) -> id.isNotEmpty() && name.isNotEmpty() }
                .toMap()
        },
        observeStillOpen = careLog::observeConflictStillOpen,
        lookupEdit = { type, clientUuid ->
            when (type) {
                com.lezi.babylog.sync.conflict.ConflictRootType.Record ->
                    careLog.getRecordByClientUuid(clientUuid)?.id?.let(ConflictEditTarget::Record)
                com.lezi.babylog.sync.conflict.ConflictRootType.CarePlan ->
                    careLog.getCarePlanByClientUuid(clientUuid)?.id?.let(ConflictEditTarget::CarePlan)
                com.lezi.babylog.sync.conflict.ConflictRootType.Baby ->
                    careLog.getBabyByClientUuid(clientUuid)?.id?.let(ConflictEditTarget::Baby)
                else -> null
            }
        },
    )
    private val sessionState = ConflictResolverSessionState(savedStateHandle)
    private val attempts = ConflictResolverAttemptGate()
    private val mutableState = MutableStateFlow(ConflictResolverUiState())
    private var loadJob: Job? = null
    private var submitJob: Job? = null
    val state: StateFlow<ConflictResolverUiState> = mutableState.asStateFlow()

    fun open(conflictId: String, forceRefresh: Boolean = true) {
        loadJob?.cancel()
        submitJob?.cancel()
        val attempt = attempts.begin(conflictId)
        mutableState.value = ConflictResolverUiState(
            conflictId = conflictId,
            phase = ConflictResolverPhase.Loading,
        )
        loadJob = viewModelScope.launch {
            try {
                val load = loadConflict(conflictId, forceRefresh)
                if (!attempts.accepts(attempt)) return@launch
                if (load == null) {
                    mutableState.value = ConflictResolverUiState(
                        conflictId = conflictId,
                        phase = ConflictResolverPhase.Error(
                            "暂时无法取得冲突详情，请联网后重试",
                            ConflictResolverRetry.Refresh,
                        ),
                    )
                    return@launch
                }
                val session = sessions.first()
                if (!attempts.accepts(attempt)) return@launch
                val restored = sessionState.restore(conflictId)
                val draft = ConflictResolverDraft.open(
                    snapshot = load.snapshot,
                    audience = ConflictResolverAudience(
                        membershipId = session.membershipId,
                        isOwner = session.role == FamilyRole.Owner,
                    ),
                    fetchedOnline = load.fetchedOnline,
                    nowMillis = nowMillis(),
                    restored = restored,
                    resolutionMutationId = newClientUuid(),
                    withdrawalMutationId = newClientUuid(),
                    clock = nowMillis,
                    actorNames = actorNames.first(),
                )
                if (!attempts.accepts(attempt)) return@launch
                val restoredApplies = restored?.snapshotToken == draft.model.snapshotToken
                val stillRequiresRefresh = restoredApplies && restored?.requiresRefresh == true
                val terminalDisposition = restored?.terminalDisposition.takeIf { restoredApplies }
                sessionState.persist(
                    draft.savedState(
                        requiresRefresh = stillRequiresRefresh,
                        terminalDisposition = terminalDisposition,
                    ),
                )
                mutableState.value = ConflictResolverUiState(
                    conflictId = conflictId,
                    draft = draft,
                    phase = terminalDisposition?.toTerminalPhase()
                        ?: phaseFor(load, draft, stillRequiresRefresh),
                    requiresFreshSnapshot = stillRequiresRefresh,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (!attempts.accepts(attempt)) return@launch
                val message = productUiError(error, LocalOpFailureCopy.LOAD)
                mutableState.value = ConflictResolverUiState(
                    conflictId = conflictId,
                    phase = ConflictResolverPhase.Error(
                        message,
                        ConflictResolverRetry.Refresh,
                    ),
                )
            }
        }
    }

    fun choose(path: String, choiceId: String) {
        val current = mutableState.value
        if (current.phase != ConflictResolverPhase.Complete) return
        val draft = current.draft ?: return
        when (val result = draft.select(path, choiceId)) {
            is ConflictResolverChoiceResult.Selected -> {
                sessionState.persist(result.draft.savedState())
                mutableState.update { it.copy(draft = result.draft) }
            }
            is ConflictResolverChoiceResult.ReadOnly -> {
                if (nowMillis() >= draft.model.expiresAt) {
                    val attempt = current.conflictId?.let(attempts::current) ?: return
                    mutableState.update {
                        it.copy(
                            draft = draft,
                            phase = ConflictResolverPhase.Stale(result.reason),
                            requiresFreshSnapshot = true,
                        )
                    }
                    refreshEvidence(
                        attempt = attempt,
                        evidence = draft,
                        message = result.reason,
                        requireNewSnapshot = true,
                    )
                } else {
                    mutableState.update {
                        it.copy(
                            phase = ConflictResolverPhase.Error(
                                result.reason,
                                ConflictResolverRetry.Refresh,
                            ),
                        )
                    }
                }
            }
        }
    }

    fun selectVersion(versionId: String) {
        val current = mutableState.value
        if (current.phase != ConflictResolverPhase.Complete) return
        val draft = current.draft ?: return
        when (val result = draft.selectVersion(versionId)) {
            is ConflictResolverChoiceResult.Selected -> {
                sessionState.persist(result.draft.savedState())
                mutableState.update {
                    it.copy(
                        draft = result.draft,
                        notice = null,
                        confirmingDelete = false,
                    )
                }
            }
            is ConflictResolverChoiceResult.ReadOnly -> {
                if (nowMillis() >= draft.model.expiresAt) {
                    val attempt = current.conflictId?.let(attempts::current) ?: return
                    mutableState.update {
                        it.copy(
                            draft = draft,
                            phase = ConflictResolverPhase.Stale(result.reason),
                            requiresFreshSnapshot = true,
                        )
                    }
                    refreshEvidence(
                        attempt = attempt,
                        evidence = draft,
                        message = result.reason,
                        requireNewSnapshot = true,
                    )
                } else {
                    mutableState.update {
                        it.copy(notice = result.reason, confirmingDelete = false)
                    }
                }
            }
        }
    }

    fun requestDelete() {
        val draft = mutableState.value.draft ?: return
        if (!mutableState.value.canSubmit) return
        if (draft.selectedVersion?.kind != ConflictResolverVersionKind.Deleted) return
        mutableState.update { it.copy(confirmingDelete = true, notice = null) }
    }

    fun cancelDelete() {
        mutableState.update { it.copy(confirmingDelete = false) }
    }

    fun consumePendingEdit() {
        mutableState.update { it.copy(pendingEdit = null) }
    }

    fun submit(continueEdit: Boolean = false) {
        val current = mutableState.value
        if (current.submitting) return
        if ((current.phase as? ConflictResolverPhase.Error)?.retry == ConflictResolverRetry.None) return
        val attempt = current.conflictId?.let(attempts::current) ?: return
        val draft = current.draft ?: return
        if (!current.canSubmit) {
            if (nowMillis() >= draft.model.expiresAt) {
                val message = "冲突快照已过期，请联网刷新"
                mutableState.update {
                    it.copy(
                        phase = ConflictResolverPhase.Stale(message),
                        requiresFreshSnapshot = true,
                    )
                }
                refreshEvidence(
                    attempt = attempt,
                    evidence = draft,
                    message = message,
                    requireNewSnapshot = true,
                )
            }
            return
        }
        val frozen = runCatching { if (draft.submitted) draft else draft.freeze() }.getOrElse { error ->
            mutableState.update {
                it.copy(
                    notice = error.message ?: "请先点选要采用的一版",
                    confirmingDelete = false,
                )
            }
            return
        }
        sessionState.persist(frozen.savedState())
        mutableState.update {
            it.copy(
                draft = frozen,
                submitting = true,
                continueEditAfterResolve = continueEdit,
                confirmingDelete = false,
                notice = null,
            )
        }
        submitJob = viewModelScope.launch {
            val outcome = try {
                resolveConflict(frozen.model.conflictId, frozen.command())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                ConflictResolveOutcome.TransportFailure(productUiError(error, "没有提交成功，两边设备上的记录都保持原样，可重试"))
            }
            if (!attempts.accepts(attempt)) return@launch
            when (outcome) {
                is ConflictResolveOutcome.Accepted -> {
                    sessionState.clear()
                    if (continueEdit) {
                        finishWithEdit(attempt, frozen)
                    } else {
                        mutableState.value = ConflictResolverUiState(
                            resolvedConflictId = frozen.model.conflictId,
                        )
                    }
                }
                is ConflictResolveOutcome.Withdrawn -> mutableState.update {
                    it.copy(
                        draft = frozen,
                        submitting = false,
                        phase = ConflictResolverPhase.Error(
                            "本次解决请求已被拒绝，请关闭后重新打开冲突",
                            ConflictResolverRetry.None,
                        ),
                    )
                }
                is ConflictResolveOutcome.TransportFailure -> mutableState.update {
                    it.copy(
                        draft = frozen,
                        submitting = false,
                        phase = ConflictResolverPhase.Error(
                            outcome.message,
                            ConflictResolverRetry.Submit,
                        ),
                    )
                }
                is ConflictResolveOutcome.RefreshRequired -> {
                    mutableState.update {
                        it.copy(
                            draft = frozen,
                            submitting = false,
                            phase = ConflictResolverPhase.Stale(outcome.message),
                            requiresFreshSnapshot = true,
                        )
                    }
                    refreshEvidence(
                        attempt = attempt,
                        evidence = frozen,
                        message = outcome.message,
                        requireNewSnapshot = true,
                    )
                }
                is ConflictResolveOutcome.Forbidden -> {
                    sessionState.persist(
                        frozen.savedState(
                            terminalDisposition = ConflictResolverTerminalDisposition.Forbidden,
                        ),
                    )
                    mutableState.update {
                        it.copy(
                            draft = frozen,
                            submitting = false,
                            phase = ConflictResolverPhase.Error(
                                outcome.message,
                                ConflictResolverRetry.None,
                            ),
                        )
                    }
                }
                is ConflictResolveOutcome.Rejected -> {
                    if (outcome.code == "incomplete_choices") {
                        sessionState.persist(frozen.savedState())
                        mutableState.update {
                            it.copy(
                                draft = frozen,
                                submitting = false,
                                phase = ConflictResolverPhase.Complete,
                                notice = outcome.message,
                            )
                        }
                    } else {
                        sessionState.persist(
                            frozen.savedState(
                                terminalDisposition = ConflictResolverTerminalDisposition.Rejected,
                            ),
                        )
                        mutableState.update {
                            it.copy(
                                draft = frozen,
                                submitting = false,
                                phase = ConflictResolverPhase.Error(
                                    outcome.message,
                                    ConflictResolverRetry.None,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    fun submitWithdraw() {
        val current = mutableState.value
        if (current.submitting) return
        if ((current.phase as? ConflictResolverPhase.Error)?.retry == ConflictResolverRetry.None) return
        val attempt = current.conflictId?.let(attempts::current) ?: return
        val draft = current.draft ?: return
        if (!current.canWithdraw) return
        val request = runCatching { draft.withdrawCommand() }.getOrElse { error ->
            mutableState.update {
                it.copy(notice = error.message ?: "只能撤回自己提交的修改")
            }
            return
        }
        mutableState.update {
            it.copy(submitting = true, notice = null)
        }
        submitJob = viewModelScope.launch {
            val outcome = try {
                withdrawConflict(draft.model.conflictId, request)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                ConflictResolveOutcome.TransportFailure(productUiError(error, "没有提交成功，两边设备上的记录都保持原样，可重试"))
            }
            if (!attempts.accepts(attempt)) return@launch
            when (outcome) {
                is ConflictResolveOutcome.Accepted,
                is ConflictResolveOutcome.Withdrawn,
                -> {
                    val stillOpen = (outcome as? ConflictResolveOutcome.Withdrawn)?.stillOpen == true
                    if (stillOpen) {
                        mutableState.update {
                            it.copy(
                                submitting = false,
                                notice = "已撤回你的修改",
                            )
                        }
                        refreshEvidence(
                            attempt = attempt,
                            evidence = draft,
                            message = "正在刷新完整冲突快照…",
                            requireNewSnapshot = true,
                        )
                    } else {
                        sessionState.clear()
                        mutableState.value = ConflictResolverUiState(
                            resolvedConflictId = draft.model.conflictId,
                        )
                    }
                }
                is ConflictResolveOutcome.TransportFailure -> mutableState.update {
                    it.copy(
                        submitting = false,
                        phase = ConflictResolverPhase.Error(
                            outcome.message,
                            ConflictResolverRetry.Submit,
                        ),
                    )
                }
                is ConflictResolveOutcome.RefreshRequired -> {
                    mutableState.update {
                        it.copy(
                            submitting = false,
                            phase = ConflictResolverPhase.Stale(outcome.message),
                            requiresFreshSnapshot = true,
                        )
                    }
                    refreshEvidence(
                        attempt = attempt,
                        evidence = draft,
                        message = outcome.message,
                        requireNewSnapshot = true,
                    )
                }
                is ConflictResolveOutcome.Forbidden -> mutableState.update {
                    it.copy(
                        submitting = false,
                        phase = ConflictResolverPhase.Error(
                            outcome.message,
                            ConflictResolverRetry.None,
                        ),
                    )
                }
                is ConflictResolveOutcome.Rejected -> mutableState.update {
                    it.copy(
                        submitting = false,
                        phase = ConflictResolverPhase.Error(
                            outcome.message,
                            ConflictResolverRetry.None,
                        ),
                    )
                }
            }
        }
    }

    private suspend fun finishWithEdit(
        attempt: ConflictResolverAttempt,
        frozen: ConflictResolverDraft,
    ) {
        mutableState.update {
            it.copy(
                submitting = false,
                phase = ConflictResolverPhase.Applying("正在套用选定的版本…"),
            )
        }
        val closed = withTimeoutOrNull(15_000) {
            observeStillOpen(frozen.model.conflictId).first { open -> !open }
            true
        } == true
        if (!attempts.accepts(attempt)) return
        val edit = if (closed) {
            lookupEdit(frozen.model.entityType, frozen.model.clientUuid)
        } else {
            null
        }
        mutableState.value = ConflictResolverUiState(
            resolvedConflictId = frozen.model.conflictId,
            pendingEdit = edit,
            notice = if (edit == null) "已采用这一版，同步后再改" else null,
        )
    }

    private fun refreshEvidence(
        attempt: ConflictResolverAttempt,
        evidence: ConflictResolverDraft,
        message: String,
        requireNewSnapshot: Boolean,
    ) {
        loadJob?.cancel()
        sessionState.persist(evidence.savedState(requiresRefresh = requireNewSnapshot))
        loadJob = viewModelScope.launch {
            if (!attempts.accepts(attempt)) return@launch
            mutableState.update {
                it.copy(
                    draft = evidence,
                    phase = ConflictResolverPhase.Refreshing(message),
                    requiresFreshSnapshot = requireNewSnapshot,
                )
            }
            val load = try {
                loadConflict(attempt.conflictId, true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                null
            }
            if (!attempts.accepts(attempt)) return@launch
            if (load == null) {
                mutableState.update {
                    it.copy(
                        draft = evidence,
                        phase = ConflictResolverPhase.Error(
                            "刷新失败；旧快照仍保留为只读证据",
                            ConflictResolverRetry.Refresh,
                        ),
                        requiresFreshSnapshot = requireNewSnapshot,
                    )
                }
                return@launch
            }
            if (!load.fetchedOnline) {
                mutableState.update {
                    it.copy(
                        draft = evidence,
                        phase = ConflictResolverPhase.Offline(
                            "当前离线；旧快照仍保留为只读证据",
                        ),
                        requiresFreshSnapshot = requireNewSnapshot,
                    )
                }
                return@launch
            }
            val sameSnapshot = load.snapshot.snapshotToken == evidence.model.snapshotToken
            val preservedAttempt = evidence.savedState().takeIf { sameSnapshot }
            val refreshed = try {
                val session = sessions.first()
                if (!attempts.accepts(attempt)) return@launch
                ConflictResolverDraft.open(
                    snapshot = load.snapshot,
                    audience = ConflictResolverAudience(
                        membershipId = session.membershipId,
                        isOwner = session.role == FamilyRole.Owner,
                    ),
                    fetchedOnline = true,
                    nowMillis = nowMillis(),
                    restored = preservedAttempt,
                    resolutionMutationId = newClientUuid(),
                    withdrawalMutationId = newClientUuid(),
                    clock = nowMillis,
                    actorNames = actorNames.first(),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (!attempts.accepts(attempt)) return@launch
                mutableState.update {
                    it.copy(
                        draft = evidence,
                        phase = ConflictResolverPhase.Error(
                            productUiError(error, "刷新失败；旧快照仍保留为只读证据"),
                            ConflictResolverRetry.Refresh,
                        ),
                        requiresFreshSnapshot = requireNewSnapshot,
                    )
                }
                return@launch
            }
            if (!attempts.accepts(attempt)) return@launch
            val stillRequiresRefresh = requireNewSnapshot && sameSnapshot
            sessionState.persist(refreshed.savedState(stillRequiresRefresh))
            mutableState.value = ConflictResolverUiState(
                conflictId = attempt.conflictId,
                draft = refreshed,
                phase = phaseFor(load, refreshed, stillRequiresRefresh),
                requiresFreshSnapshot = stillRequiresRefresh,
                notice = mutableState.value.notice,
            )
        }
    }

    fun refresh() {
        val current = mutableState.value
        val conflictId = current.conflictId ?: return
        if (current.submitting ||
            current.phase is ConflictResolverPhase.Refreshing ||
            current.phase is ConflictResolverPhase.Applying
        ) return
        if ((current.phase as? ConflictResolverPhase.Error)?.retry == ConflictResolverRetry.None) return
        val evidence = current.draft
        val attempt = attempts.current(conflictId)
        if (evidence == null || attempt == null) {
            open(conflictId)
            return
        }
        refreshEvidence(
            attempt = attempt,
            evidence = evidence,
            message = "正在刷新完整冲突快照…",
            requireNewSnapshot = current.requiresFreshSnapshot ||
                current.phase is ConflictResolverPhase.Stale,
        )
    }

    fun dismiss() {
        loadJob?.cancel()
        submitJob?.cancel()
        attempts.invalidate()
        sessionState.clear()
        mutableState.value = ConflictResolverUiState()
    }

    fun consumeResolved() {
        mutableState.update { it.copy(resolvedConflictId = null) }
    }
}

@Composable
fun ConflictResolverRoute(
    conflictId: String,
    onDismiss: () -> Unit,
    onResolved: (ConflictEditTarget?) -> Unit,
    host: ConflictResolverHost = hiltViewModel(),
) {
    val state by host.state.collectAsStateWithLifecycle()
    LaunchedEffect(conflictId) { host.open(conflictId) }
    LaunchedEffect(state.resolvedConflictId, state.pendingEdit) {
        if (state.resolvedConflictId == conflictId) {
            val edit = state.pendingEdit
            host.consumePendingEdit()
            host.consumeResolved()
            onResolved(edit)
        }
    }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = {
            if (state.phase is ConflictResolverPhase.Applying) return@Dialog
            host.dismiss()
            onDismiss()
        },
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        androidx.compose.material3.Surface(
            modifier = Modifier
                .fillMaxSize()
                .testTag("conflict_resolver_sheet"),
            color = MaterialTheme.colorScheme.background,
        ) {
            ConflictResolverContent(
                state = state,
                onSelectVersion = host::selectVersion,
                onAdopt = { host.submit(continueEdit = false) },
                onAdoptAndEdit = { host.submit(continueEdit = true) },
                onWithdraw = host::submitWithdraw,
                onRequestDelete = host::requestDelete,
                onConfirmDelete = { host.submit(continueEdit = false) },
                onCancelDelete = host::cancelDelete,
                onRefresh = host::refresh,
                onDismiss = {
                    host.dismiss()
                    onDismiss()
                },
            )
        }
    }
}

@Composable
fun ConflictResolverContent(
    state: ConflictResolverUiState,
    onSelectVersion: (String) -> Unit,
    onAdopt: () -> Unit,
    onAdoptAndEdit: () -> Unit,
    onWithdraw: () -> Unit = {},
    onRequestDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
    onCancelDelete: () -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit = {},
) {
    val draft = state.draft
    val entityLabel = draft?.model?.entityLabel ?: "事实"
    val selected = draft?.selectedVersion
    val applying = state.phase is ConflictResolverPhase.Applying
    Column(Modifier.fillMaxSize()) {
        LeziDetailTopBar(
            title = "解决${entityLabel}冲突",
            onBack = onDismiss,
        )
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(LeziSpacing.Page),
            verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm),
        ) {
            Text("点选家里要留下的那一版。采用后所有设备都会按这一版显示。")
            when (val phase = state.phase) {
                ConflictResolverPhase.Idle, ConflictResolverPhase.Complete -> Unit
                ConflictResolverPhase.Loading -> Text(
                    "正在取得最新差异…",
                    modifier = Modifier.testTag("conflict_loading"),
                )
                is ConflictResolverPhase.Offline -> FreshnessStatus(
                    phase.message,
                    "conflict_offline",
                    onRefresh,
                )
                is ConflictResolverPhase.Stale -> FreshnessStatus(
                    phase.message,
                    "conflict_stale",
                    onRefresh,
                )
                is ConflictResolverPhase.Refreshing -> FreshnessStatus(
                    phase.message,
                    "conflict_refreshing",
                )
                is ConflictResolverPhase.Applying -> FreshnessStatus(
                    phase.message,
                    "conflict_applying",
                )
                is ConflictResolverPhase.Error -> FreshnessStatus(
                    phase.message,
                    "conflict_error",
                    onRefresh.takeIf { phase.retry == ConflictResolverRetry.Refresh },
                )
            }
            state.notice?.let { message ->
                Text(
                    message,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("conflict_notice"),
                )
            }
            draft?.model?.readOnlyReason
                ?.takeIf { state.phase == ConflictResolverPhase.Complete }
                ?.let { reason ->
                    Text(
                        reason,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("conflict_read_only"),
                    )
                }
            draft?.model?.versions?.forEachIndexed { index, version ->
                val selectedCard = version.versionId == draft.selectedVersionId
                LeziSurfacePanel(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("conflict_version_$index")
                        .semantics {
                            contentDescription = buildString {
                                append(version.label)
                                append("，")
                                append(version.title)
                                if (version.summary.isNotBlank()) {
                                    append("，")
                                    append(version.summary)
                                }
                                draft.model.differenceLine?.let { line ->
                                    append("，")
                                    append(line)
                                }
                                append("，")
                                append(version.actorLabel)
                                append("，")
                                append(if (version.deleted) "已删除" else "保留")
                                append("，${version.media.size}张照片")
                            }

                        },
                    onClick = {
                        if (state.canChoose) onSelectVersion(version.versionId)
                    }.takeIf { state.canChoose },
                ) {
                    Text(version.label, style = LeziTypography.TitleSm)
                    draft.model.differenceLine?.let { line ->
                        Text(
                            line,
                            style = LeziTypography.Meta,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.testTag("conflict_difference"),
                        )
                    }
                    if (selectedCard) {
                        Text("已选中", color = MaterialTheme.colorScheme.primary)
                    }
                    Text(version.title, style = LeziTypography.BodyStrong)
                    if (version.whenLabel.isNotBlank()) {
                        Text(version.whenLabel, style = LeziTypography.Meta)
                    }
                    if (version.summary.isNotBlank()) {
                        Text(
                            emphasizedSummary(
                                version.summary,
                                draft.model.differenceTokens(version.versionId),
                            ),
                            style = LeziTypography.Body,
                        )
                    }


                    Text(
                        listOfNotNull(
                            version.actorLabel.takeIf(String::isNotBlank),
                            if (version.media.isEmpty()) null else "${version.media.size} 张照片",
                        ).joinToString(" · "),
                        style = LeziTypography.Meta,
                    )
                    Text(version.consequence, style = LeziTypography.Meta)
                }
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .padding(LeziSpacing.Page),
            verticalArrangement = Arrangement.spacedBy(LeziSpacing.Xs),
        ) {
            selected?.let { version ->
                Text(version.consequence, style = LeziTypography.Meta)
            }
            val liveAction = selected?.kind != ConflictResolverVersionKind.Deleted
            if (liveAction) {
                LeziPrimaryButton(
                    label = when {
                        applying -> "正在套用…"
                        state.submitting -> "正在提交…"
                        draft?.submitted == true -> "重试同一次提交"
                        selected?.kind == ConflictResolverVersionKind.Restore -> "恢复这一版"
                        else -> "采用这一版"
                    },
                    enabled = state.canSubmit && !applying,
                    busy = state.submitting || applying,
                    onClick = onAdopt,
                    modifier = Modifier.fillMaxWidth().testTag("conflict_submit"),
                )
                if (draft?.model?.canContinueEdit == true) {
                    LeziTextButton(
                        label = if (selected?.kind == ConflictResolverVersionKind.Restore) {
                            "恢复并修改"
                        } else {
                            "采用并修改"
                        },
                        onClick = onAdoptAndEdit,
                        enabled = state.canSubmit && !applying,
                        tone = LeziTextButtonTone.Primary,
                        modifier = Modifier.fillMaxWidth().testTag("conflict_submit_edit"),
                    )
                }
            } else {

                LeziPrimaryButton(
                    label = if (state.submitting || applying) "正在提交…" else "删除这条",
                    enabled = state.canSubmit && !applying,
                    busy = state.submitting || applying,
                    onClick = onRequestDelete,
                    modifier = Modifier.fillMaxWidth().testTag("conflict_submit"),
                )
            }
            if (state.canWithdraw || draft?.model?.canWithdrawBranches == true) {
                LeziTextButton(
                    label = when {
                        state.submitting -> "正在撤回…"
                        draft?.model?.isFamilyAdmin == true -> "撤回这些修改"
                        else -> "撤回我的修改"
                    },
                    onClick = onWithdraw,
                    enabled = state.canWithdraw && !applying,
                    tone = LeziTextButtonTone.Primary,
                    modifier = Modifier.fillMaxWidth().testTag("conflict_withdraw"),
                )
            }
        }
    }
    if (state.confirmingDelete) {
        LeziAlertDialog(
            onDismissRequest = onCancelDelete,
            title = { Text("删除这条${entityLabel}？") },
            text = { Text("所有设备时间轴上都不再显示。误删以后只能再走恢复，不能当普通编辑复活。") },
            confirmButton = {
                LeziTextButton(
                    "确认删除",
                    onClick = onConfirmDelete,
                    tone = LeziTextButtonTone.Destructive,
                    modifier = Modifier.testTag("conflict_confirm_delete"),
                )
            },
            dismissButton = {
                LeziTextButton("取消", onClick = onCancelDelete)
            },
        )
    }
}

private fun phaseFor(
    load: ConflictResolverLoad,
    draft: ConflictResolverDraft,
    requiresRefresh: Boolean,
): ConflictResolverPhase = when {
    !load.fetchedOnline -> ConflictResolverPhase.Offline(
        "当前离线；以下为上次完整快照，只读",
    )
    requiresRefresh || draft.model.availability == ConflictResolverAvailability.Expired ->
        ConflictResolverPhase.Stale("冲突快照已过期，请联网刷新")
    else -> ConflictResolverPhase.Complete
}

private fun ConflictResolverTerminalDisposition.toTerminalPhase(): ConflictResolverPhase.Error =
    when (this) {
        ConflictResolverTerminalDisposition.Forbidden -> ConflictResolverPhase.Error(
            "当前账号无权解决此冲突",
            ConflictResolverRetry.None,
        )
        ConflictResolverTerminalDisposition.Rejected -> ConflictResolverPhase.Error(
            "本次解决请求已被拒绝，请关闭后重新打开冲突",
            ConflictResolverRetry.None,
        )
    }

@Composable
private fun FreshnessStatus(
    message: String,
    tag: String,
    onRefresh: (() -> Unit)? = null,
) {
    Text(
        message,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.testTag(tag).semantics { contentDescription = message },
    )
    onRefresh?.let { refresh ->
        LeziTextButton(
            "刷新完整快照",
            onClick = refresh,
            modifier = Modifier.testTag("conflict_refresh"),
        )
    }
}

private fun emphasizedSummary(
    summary: String,
    highlights: List<String>,
): AnnotatedString = buildAnnotatedString {
    if (highlights.isEmpty()) {
        append(summary)
        return@buildAnnotatedString
    }
    var index = 0
    while (index < summary.length) {
        val next = highlights.mapNotNull { label ->
            val at = summary.indexOf(label, startIndex = index)
            if (at >= 0) at to label else null
        }.minByOrNull { it.first }
        if (next == null) {
            append(summary.substring(index))
            break
        }
        val (at, label) = next
        if (at > index) append(summary.substring(index, at))
        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
            append(label)
        }
        index = at + label.length
    }
}

