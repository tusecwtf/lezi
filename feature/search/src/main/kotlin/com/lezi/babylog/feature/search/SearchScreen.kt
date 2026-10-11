package com.lezi.babylog.feature.search

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.lezi.babylog.core.model.displayLabel
import com.lezi.babylog.core.ui.RecordTypeIcon
import com.lezi.babylog.core.ui.presentationSummary
import com.lezi.babylog.core.ui.presentationTone
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziDetailTopBar
import com.lezi.babylog.designsystem.LeziTypography
import com.lezi.babylog.designsystem.RecordRow
import com.lezi.babylog.designsystem.dismissKeyboardOnTap
import com.lezi.babylog.designsystem.StateContainer
import com.lezi.babylog.designsystem.StateKind
import com.lezi.babylog.designsystem.LeziTextField
import com.lezi.babylog.designsystem.LeziIconButton
import com.lezi.babylog.designsystem.LeziTextButton
import com.lezi.babylog.domain.carelog.formatClock
import com.lezi.babylog.domain.carelog.relativeTimeLabel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SearchUi(
    val query: String = "",
    val results: List<SearchResult> = emptyList(),
    val searching: Boolean = false,
    /** Copy lives in stringResource; tests assert state, not text. */
    val failed: Boolean = false,
    val noticeMessage: String? = null,
    /** A successful same-scope snapshot survived a refresh failure. */
    val readOnly: Boolean = false,
)

data class SearchRecordOpen(
    val recordId: Long,
    val canEdit: Boolean,
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: SearchRepository,
) : ViewModel() {
    private val state = MutableStateFlow(SearchUi())
    private var job: Job? = null
    private var queryGeneration = 0L
    private var observation = SearchObservation()
    private var observerJob: Job? = null
    private var latestRevision: SearchRevision? = null
    private var revisionVersion = 0L
    private var scopeVersion = 0L
    private var revisionFailed = false
    private var successfulSnapshot: SearchSnapshot? = null

    val ui = state.asStateFlow()

    init {
        observeRevisions()
    }

    private fun observeRevisions() {
        observerJob?.cancel()
        val observing = SearchObservation()
        observation = observing
        revisionFailed = false
        // This observer outlives query debounce and retries. Losing authority
        // must remove visible rows even while a search is suspended or failing.
        observerJob = viewModelScope.launch {
            var first = true
            try {
                repository.revisions().collect { revision ->
                    if (observing !== observation) return@collect
                    if (latestRevision != revision) {
                        val oldScope = latestRevision?.scope()
                        latestRevision = revision
                        revisionVersion += 1
                        if (oldScope != revision.scope()) {
                            scopeVersion += 1
                            discardSnapshot()
                        }
                    }
                    if (first) {
                        first = false
                        observing.firstEmission.complete(revision)
                    }
                    observing.mailbox.trySend(revision)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                if (observing !== observation) return@launch
                revisionFailed = true
                latestRevision = null
                scopeVersion += 1
                revisionVersion += 1
                discardSnapshot()
                state.value = state.value.copy(
                    failed = state.value.query.isNotBlank(),
                    searching = false,
                )
            } finally {
                if (first) observing.firstEmission.complete(null)
                observing.mailbox.close()
            }
        }
    }

    fun onQuery(q: String) {
        val limited = limitSearchQuery(q)
        val generation = ++queryGeneration
        job?.cancel()
        if (revisionFailed) observeRevisions()
        if (latestRevision == null || limited != state.value.query || !snapshotMatchesCurrentScope(limited)) {
            discardSnapshot()
        }
        state.value = state.value.copy(
            query = limited,
            searching = limited.isNotBlank(),
            failed = false,
            noticeMessage = null,
        )
        if (limited.isBlank()) return
        val observing = observation
        job = viewModelScope.launch {
            delay(200)
            if (generation != queryGeneration) return@launch
            val first = observing.firstEmission.await()
            if (revisionFailed) {
                state.value = state.value.copy(searching = false, failed = true)
                return@launch
            }
            var searchedVersion = revisionVersion
            refresh(limited, generation)
            if (first == null) return@launch
            for (token in observing.mailbox) {
                // Conflate intermediate invalidations, but never skip a change
                // that happened during the preceding asynchronous search.
                if (searchedVersion == revisionVersion) continue
                searchedVersion = revisionVersion
                refresh(limited, generation)
            }
        }
    }

    private suspend fun refresh(limited: String, generation: Long) {
        if (revisionFailed) {
            state.value = state.value.copy(searching = false, failed = true)
            return
        }
        val revision = latestRevision
        val observedVersion = revisionVersion
        val observedScopeVersion = scopeVersion
        state.value = state.value.copy(searching = true, failed = false)
        try {
            val before = checkedSearchScope { repository.currentScope() } ?: revision?.scope()
            if (before != revision?.scope()) throw SearchScopeChangedException()
            val found = repository.search(limited)
            val after = checkedSearchScope { repository.currentScope() } ?: latestRevision?.scope()
            if (before != after) throw SearchScopeChangedException()
            if (generation != queryGeneration || observedVersion != revisionVersion) return
            val scope = revision?.scope()
            val scopedResults = found.filter {
                scope == null || (it.record.babyId == scope.babyId && it.record.clientUuid !in scope.sourceRoleClientUuids)
            }
            successfulSnapshot = SearchSnapshot(limited, scope, observedScopeVersion)
            state.value = state.value.copy(
                results = scopedResults,
                searching = false,
                failed = false,
                readOnly = false,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: SearchScopeException) {
            if (generation == queryGeneration) failWithoutSnapshot()
        } catch (_: Throwable) {
            // A query failure cannot establish that delayed authority is still
            // current. Recheck the local scope before retaining any old rows.
            val currentScope = try {
                checkedSearchScope { repository.currentScope() } ?: latestRevision?.scope()
            } catch (error: CancellationException) {
                throw error
            } catch (_: SearchScopeException) {
                if (generation == queryGeneration) failWithoutSnapshot()
                return
            }
            if (currentScope != revision?.scope()) {
                if (generation == queryGeneration) failWithoutSnapshot()
                return
            }
            if (generation != queryGeneration || observedVersion != revisionVersion) return
            val canKeep = revision != null && snapshotMatchesCurrentScope(limited) &&
                state.value.results.isNotEmpty()
            if (!canKeep) discardSnapshot()
            state.value = state.value.copy(searching = false, failed = true, readOnly = canKeep)
        }
    }

    private fun snapshotMatchesCurrentScope(limited: String): Boolean =
        successfulSnapshot?.let {
            it.query == limited && it.scope == latestRevision?.scope() &&
                it.scopeVersion == scopeVersion
        } == true

    private fun discardSnapshot() {
        successfulSnapshot = null
        state.value = state.value.copy(results = emptyList(), readOnly = false, failed = false)
    }

    private fun failWithoutSnapshot() {
        discardSnapshot()
        state.value = state.value.copy(searching = false, failed = true)
    }

    fun retry() {
        val currentQuery = state.value.query
        if (currentQuery.isNotBlank()) onQuery(currentQuery)
    }

    /** Same edit-or-view mapping as the timeline after list intercepts. */
    suspend fun requestOpenRecord(result: SearchResult): SearchRecordOpen? {
        state.value = state.value.copy(noticeMessage = null)
        if (!snapshotMatchesCurrentScope(state.value.query)) return null
        val snapshot = successfulSnapshot ?: return null
        val generation = queryGeneration
        val current = state.value.results.firstOrNull {
            it.record.id == result.record.id && it.record.clientUuid == result.record.clientUuid
        } ?: return null
        val currentScope = try {
            checkedSearchScope { repository.currentScope() } ?: latestRevision?.scope()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SearchScopeException) {
            if (generation == queryGeneration && successfulSnapshot === snapshot) failWithoutSnapshot()
            return null
        }
        if (generation != queryGeneration || successfulSnapshot !== snapshot) return null
        if (currentScope != snapshot.scope || !snapshotMatchesCurrentScope(state.value.query)) {
            failWithoutSnapshot()
            return null
        }
        return SearchRecordOpen(
            recordId = current.record.id,
            canEdit = current.canEdit && !state.value.readOnly && !state.value.searching,
        )
    }

    fun clearNotice() {
        state.value = state.value.copy(noticeMessage = null)
    }
}

private data class SearchSnapshot(
    val query: String,
    val scope: SearchScope?,
    val scopeVersion: Long,
)

private class SearchObservation {
    val mailbox = Channel<SearchRevision>(Channel.CONFLATED)
    val firstEmission = CompletableDeferred<SearchRevision?>()
}

@Composable
fun SearchRoute(
    onBack: () -> Unit,
    onOpenRecord: (SearchRecordOpen) -> Unit,
    vm: SearchViewModel = hiltViewModel(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    Scaffold(
        topBar = {
            LeziDetailTopBar(title = stringResource(R.string.search_title), onBack = onBack)
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(LeziSpacing.Page)
                .dismissKeyboardOnTap(),
        ) {
            LeziTextField(
                value = ui.query,
                onValueChange = vm::onQuery,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(R.string.search_field_label)) },
                placeholder = { Text(stringResource(R.string.search_field_placeholder)) },
                leadingIcon = {
                    Icon(Icons.Outlined.Search, contentDescription = null)
                },
                trailingIcon = if (ui.query.isNotEmpty()) {
                    {
                        LeziIconButton(onClick = { vm.onQuery("") }, contentDescription = stringResource(R.string.search_clear_action)) {
                            Icon(Icons.Outlined.Close, contentDescription = null)
                        }
                    }
                } else {
                    null
                },
            )
            ui.noticeMessage?.let { notice ->
                Text(
                    text = notice,
                    style = LeziTypography.Body,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(top = LeziSpacing.Sm)
                        .semantics { contentDescription = notice },
                )
            }
            val phase = searchPhase(ui)
            Crossfade(targetState = phase, label = "searchPhase") { target ->
            when (target) {
                SearchPhase.Prompt -> {
                    StateContainer(
                        kind = StateKind.Empty,
                        title = stringResource(R.string.search_prompt_title),
                        message = stringResource(R.string.search_prompt_message),
                        modifier = Modifier.padding(top = LeziSpacing.Md),
                    )
                }
                SearchPhase.Loading -> {
                    StateContainer(
                        kind = StateKind.Loading,
                        title = stringResource(R.string.search_loading_title),
                        message = stringResource(R.string.search_loading_message),
                        modifier = Modifier.padding(top = LeziSpacing.Md),
                    )
                }
                SearchPhase.Error -> {
                    StateContainer(
                        kind = StateKind.Error,
                        title = stringResource(R.string.search_error_title),
                        message = stringResource(R.string.search_error_message),
                        modifier = Modifier.padding(top = LeziSpacing.Md),
                        actionLabel = stringResource(R.string.search_retry_action),
                        onAction = vm::retry,
                    )
                }
                SearchPhase.Empty -> {
                    StateContainer(
                        kind = StateKind.Empty,
                        title = stringResource(R.string.search_empty_title),
                        message = stringResource(
                            R.string.search_empty_message,
                            searchQueryPreview(ui.query),
                        ),
                        modifier = Modifier.padding(top = LeziSpacing.Md),
                    )
                }
                SearchPhase.Results -> {
                    SearchResultsList(
                        ui = ui,
                        onRetry = vm::retry,
                        onOpenRecord = { hit ->
                            scope.launch { vm.requestOpenRecord(hit)?.let(onOpenRecord) }
                        },
                    )
                }
            }
            }
        }
    }
}

@Composable
internal fun SearchResultsList(
    ui: SearchUi,
    onRetry: () -> Unit,
    onOpenRecord: (SearchResult) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        if (ui.readOnly) {
            Text(
                text = stringResource(R.string.search_stale_message),
                style = LeziTypography.Body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = LeziSpacing.Sm),
            )
            LeziTextButton(
                label = stringResource(
                    if (ui.searching) R.string.search_retrying_action else R.string.search_retry_action,
                ),
                onClick = onRetry,
                enabled = !ui.searching,
            )
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(top = LeziSpacing.Md, bottom = LeziSpacing.Xxl),
            verticalArrangement = Arrangement.spacedBy(LeziSpacing.Xs),
        ) {
            items(ui.results, key = { it.record.id }) { hit ->
                val r = hit.record
                val title = r.displayLabel()
                val actionDescription = stringResource(
                    if (hit.canEdit && !ui.readOnly && !ui.searching) {
                        R.string.search_edit_action
                    } else {
                        R.string.search_view_action
                    },
                    title,
                )
                RecordRow(
                    time = formatClock(r.timestamp),
                    title = title,
                    summary = r.presentationSummary(),
                    relative = relativeTimeLabel(r.timestamp),
                    tone = r.type.presentationTone(),
                    leading = { RecordTypeIcon(r.type) },
                    onClick = { onOpenRecord(hit) },
                    modifier = Modifier.animateItem().semantics { contentDescription = actionDescription },
                )
            }
        }
    }
}

internal enum class SearchPhase { Prompt, Loading, Error, Empty, Results }

internal fun searchPhase(ui: SearchUi): SearchPhase = when {
    ui.query.isBlank() -> SearchPhase.Prompt
    ui.results.isNotEmpty() -> SearchPhase.Results
    ui.searching -> SearchPhase.Loading
    ui.failed -> SearchPhase.Error
    else -> SearchPhase.Empty
}

private const val MAX_SEARCH_QUERY_CODE_POINTS = 100
private const val SEARCH_QUERY_PREVIEW_CODE_POINTS = 30

internal fun limitSearchQuery(value: String, maxCodePoints: Int = MAX_SEARCH_QUERY_CODE_POINTS): String {
    if (value.codePointCount(0, value.length) <= maxCodePoints) return value
    return value.substring(0, value.offsetByCodePoints(0, maxCodePoints))
}

internal fun searchQueryPreview(value: String): String {
    val limited = limitSearchQuery(value, SEARCH_QUERY_PREVIEW_CODE_POINTS)
    return if (limited == value) limited else "$limited…"
}
