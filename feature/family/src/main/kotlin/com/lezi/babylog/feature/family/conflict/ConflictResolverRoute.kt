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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.lezi.babylog.domain.carelog.ConflictResolverChoiceResult
import com.lezi.babylog.domain.carelog.ConflictResolverDraft
import com.lezi.babylog.domain.carelog.ConflictResolverPath
import com.lezi.babylog.sync.SyncPort
import com.lezi.babylog.sync.backend.ConflictResolveRequest
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.sync.session.PolicyClock
import com.lezi.babylog.sync.session.SyncSession
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ConflictResolverUiState(
    val conflictId: String? = null,
    val loading: Boolean = false,
    val draft: ConflictResolverDraft? = null,
    val error: String? = null,
    val submitting: Boolean = false,
    val resolvedConflictId: String? = null,
)

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
        mutableState.value = ConflictResolverUiState(conflictId = conflictId, loading = true)
        loadJob = viewModelScope.launch {
            try {
                val load = loadConflict(conflictId, forceRefresh)
                if (!attempts.accepts(attempt)) return@launch
                if (load == null) {
                    mutableState.value = ConflictResolverUiState(
                        conflictId = conflictId,
                        error = "暂时无法取得冲突详情，请联网后重试",
                    )
                    return@launch
                }
                val session = sessions.first()
                if (!attempts.accepts(attempt)) return@launch
                val draft = ConflictResolverDraft.open(
                    snapshot = load.snapshot,
                    audience = ConflictResolverAudience(
                        membershipId = session.membershipId,
                        isOwner = session.role == FamilyRole.Owner,
                    ),
                    fetchedOnline = load.fetchedOnline,
                    nowMillis = nowMillis(),
                    restored = sessionState.restore(conflictId),
                    resolutionMutationId = newClientUuid(),
                    clock = nowMillis,
                )
                if (!attempts.accepts(attempt)) return@launch
                sessionState.persist(draft.savedState())
                mutableState.value = ConflictResolverUiState(conflictId, draft = draft)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (!attempts.accepts(attempt)) return@launch
                mutableState.value = ConflictResolverUiState(
                    conflictId = conflictId,
                    error = productUiError(error, "加载失败，请重试"),
                )
            }
        }
    }

    fun rememberDraft(draft: ConflictResolverDraft) {
        if (draft.model.conflictId != mutableState.value.conflictId) return
        sessionState.persist(draft.savedState())
        mutableState.update { it.copy(draft = draft, error = null) }
    }

    fun submit() {
        val current = mutableState.value
        if (current.submitting) return
        val attempt = current.conflictId?.let(attempts::current) ?: return
        val draft = current.draft ?: return
        val frozen = runCatching { if (draft.submitted) draft else draft.freeze() }.getOrElse { error ->
            mutableState.update { it.copy(error = error.message ?: "选择不完整") }
            return
        }
        sessionState.persist(frozen.savedState())
        mutableState.update { it.copy(draft = frozen, submitting = true, error = null) }
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
                    it.copy(draft = frozen, submitting = false, error = outcome.message)
                }
                is ConflictResolveOutcome.RefreshRequired -> {
                    sessionState.clear()
                    mutableState.update { it.copy(draft = null, submitting = false, error = outcome.message) }
                }
                is ConflictResolveOutcome.Forbidden -> {
                    sessionState.clear()
                    mutableState.update { it.copy(draft = null, submitting = false, error = outcome.message) }
                }
                is ConflictResolveOutcome.Rejected -> {
                    sessionState.clear()
                    mutableState.update { it.copy(draft = null, submitting = false, error = outcome.message) }
                }
            }
        }
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
            onDraftChanged = host::rememberDraft,
            onSubmit = host::submit,
            onRetry = { host.open(conflictId) },
        )
    }
}

@Composable
fun ConflictResolverContent(
    state: ConflictResolverUiState,
    onDraftChanged: (ConflictResolverDraft) -> Unit,
    onSubmit: () -> Unit,
    onRetry: () -> Unit,
) {
    var interactionReadOnlyReason by remember(
        state.draft?.model?.conflictId,
        state.draft?.resolutionMutationId,
    ) { mutableStateOf<String?>(null) }
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(LeziSpacing.Page),
        verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm),
    ) {
        Text("解决${state.draft?.model?.entityLabel ?: "事实"}冲突", style = LeziTypography.Title)
        Text("只列出真实冲突字段；已自动合并的内容保持不变。")
        if (state.loading) Text("正在取得最新差异…", modifier = Modifier.testTag("conflict_loading"))
        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("conflict_error"))
            if (state.draft == null && !state.loading) {
                LeziTextButton("重新加载", onClick = onRetry)
            }
        }
        state.draft?.let { current ->
            (interactionReadOnlyReason ?: current.model.readOnlyReason)?.let { reason ->
                Text(reason, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("conflict_read_only"))
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
                    current.canChoose,
                ) { selectedPath, choiceId ->
                    when (val result = current.select(selectedPath, choiceId)) {
                        is ConflictResolverChoiceResult.Selected -> {
                            interactionReadOnlyReason = null
                            onDraftChanged(result.draft)
                        }
                        is ConflictResolverChoiceResult.ReadOnly -> interactionReadOnlyReason = result.reason
                    }
                }
            }
            LeziPrimaryButton(
                label = when {
                    state.submitting -> "正在提交…"
                    current.submitted -> "重试同一次提交"
                    else -> "确认解决"
                },
                enabled = current.canSubmit && !state.submitting,
                busy = state.submitting,
                onClick = onSubmit,
                modifier = Modifier.fillMaxWidth().testTag("conflict_submit"),
            )
        }
        Spacer(Modifier.height(LeziSpacing.Lg))
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
