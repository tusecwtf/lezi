package com.lezi.babylog.feature.family.conflict

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.lezi.babylog.core.common.newClientUuid
import com.lezi.babylog.core.common.productUiError
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
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.sync.session.PolicyClock
import com.lezi.babylog.sync.session.SyncSession
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ConflictResolverUiState(
    val conflictId: String? = null,
    val draft: ConflictResolverDraft? = null,
    val submitting: Boolean = false,
    val resolvedConflictId: String? = null,
    val phase: ConflictResolverPhase = ConflictResolverPhase.Idle,
    val requiresFreshSnapshot: Boolean = false,
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
}

sealed interface ConflictResolverPhase {
    data object Idle : ConflictResolverPhase

    data object Loading : ConflictResolverPhase

    data object Complete : ConflictResolverPhase

    data class Offline(val message: String) : ConflictResolverPhase

    data class Stale(val message: String) : ConflictResolverPhase

    data class Refreshing(val message: String) : ConflictResolverPhase

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
    private val sessions: Flow<SyncSession>,
    private val nowMillis: () -> Long,
    savedStateHandle: SavedStateHandle,
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
        sessions = syncPort.session(),
        nowMillis = clock::nowMillis,
        savedStateHandle = savedStateHandle,
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
                    clock = nowMillis,
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
                val message = productUiError(error, "加载失败，请重试")
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

    fun submit() {
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
                    phase = ConflictResolverPhase.Error(
                        error.message ?: "选择不完整",
                        ConflictResolverRetry.Refresh,
                    ),
                )
            }
            return
        }
        sessionState.persist(frozen.savedState())
        mutableState.update { it.copy(draft = frozen, submitting = true) }
        submitJob = viewModelScope.launch {
            val outcome = try {
                resolveConflict(frozen.model.conflictId, frozen.command())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                ConflictResolveOutcome.TransportFailure(productUiError(error, "提交失败，请重试"))
            }
            if (!attempts.accepts(attempt)) return@launch
            when (outcome) {
                is ConflictResolveOutcome.Accepted -> {
                    sessionState.clear()
                    mutableState.value = ConflictResolverUiState(
                        resolvedConflictId = frozen.model.conflictId,
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
                    clock = nowMillis,
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
            )
        }
    }

    fun refresh() {
        val current = mutableState.value
        val conflictId = current.conflictId ?: return
        if (current.submitting || current.phase is ConflictResolverPhase.Refreshing) return
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConflictResolverRoute(
    conflictId: String,
    onDismiss: () -> Unit,
    onResolved: () -> Unit,
    host: ConflictResolverHost = hiltViewModel(),
) {
    val state by host.state.collectAsStateWithLifecycle()
    LaunchedEffect(conflictId) { host.open(conflictId) }
    LaunchedEffect(state.resolvedConflictId) {
        if (state.resolvedConflictId == conflictId) {
            host.consumeResolved()
            onResolved()
        }
    }
    ModalBottomSheet(
        onDismissRequest = {
            host.dismiss()
            onDismiss()
        },
        modifier = Modifier.testTag("conflict_resolver_sheet"),
    ) {
        ConflictResolverContent(
            state = state,
            onChoose = host::choose,
            onSubmit = host::submit,
            onRefresh = host::refresh,
        )
    }
}

@Composable
fun ConflictResolverContent(
    state: ConflictResolverUiState,
    onChoose: (String, String) -> Unit,
    onSubmit: () -> Unit,
    onRefresh: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(LeziSpacing.Page),
        verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm),
    ) {
        Text("解决${state.draft?.model?.entityLabel ?: "事实"}冲突", style = LeziTypography.Title)
        Text("只列出真实冲突字段；已自动合并的内容保持不变。")
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
            is ConflictResolverPhase.Error -> FreshnessStatus(
                phase.message,
                "conflict_error",
                onRefresh.takeIf { phase.retry == ConflictResolverRetry.Refresh },
            )
        }
        state.draft?.let { current ->
            current.model.readOnlyReason
                ?.takeIf { state.phase == ConflictResolverPhase.Complete }
                ?.let { reason ->
                    Text(
                        reason,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("conflict_read_only"),
                    )
                }
            current.model.versions.forEachIndexed { index, version ->
                LeziSurfacePanel(
                    Modifier.fillMaxWidth().testTag("conflict_version_$index").semantics {
                        contentDescription = buildString {
                            append(version.label)
                            append(if (version.deleted) "，已删除" else "，保留")
                            append("，${version.media.size}张照片，")
                            append(version.provenance)
                        }
                    },
                ) {
                    Text(version.label, style = LeziTypography.TitleSm)
                    Text(if (version.deleted) "已删除" else "保留", style = LeziTypography.Meta)
                    Text("${version.media.size} 张照片 · ${version.provenance}", style = LeziTypography.Meta)
                }
            }
            if (current.model.autoMerged.isNotEmpty()) {
                Text("已自动合并", style = LeziTypography.TitleSm)
                current.model.autoMerged.forEach { outcome ->
                    Text(
                        "${outcome.label}：${outcome.value} · ${outcome.provenance}",
                        style = LeziTypography.Meta,
                        modifier = Modifier.testTag("conflict_auto_${outcome.path}"),
                    )
                }
            }
            current.model.paths.forEach { path ->
                ConflictPathChooser(
                    path,
                    current.selectedChoiceIds[path.path],
                    state.canChoose,
                    onChoose,
                )
            }
            LeziPrimaryButton(
                label = when {
                    state.submitting -> "正在提交…"
                    current.submitted -> "重试同一次提交"
                    else -> "确认解决"
                },
                enabled = state.canSubmit,
                busy = state.submitting,
                onClick = onSubmit,
                modifier = Modifier.fillMaxWidth().testTag("conflict_submit"),
            )
        }
        Spacer(Modifier.height(LeziSpacing.Lg))
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

@Composable
private fun ConflictPathChooser(
    path: ConflictResolverPath,
    selected: String?,
    enabled: Boolean,
    onChoose: (String, String) -> Unit,
) {
    LeziSurfacePanel(Modifier.fillMaxWidth().testTag("conflict_path_${path.path}")) {
        Text(path.label, style = LeziTypography.TitleSm)
        path.options.forEach { option ->
            Row(
                Modifier.fillMaxWidth().clickable(enabled = enabled) {
                    onChoose(path.path, option.choiceId)
                }.padding(vertical = LeziSpacing.Xxs)
                    .testTag("conflict_option_${path.path}_${option.choiceId}")
                    .semantics {
                        contentDescription = "${path.label}，${option.value}，${option.provenance}"
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected == option.choiceId, onClick = null)
                Column {
                    Text(option.value)
                    Text(option.provenance, style = LeziTypography.Meta)
                }
            }
        }
    }
}
