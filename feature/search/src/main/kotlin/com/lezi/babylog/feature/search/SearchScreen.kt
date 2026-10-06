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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.res.stringResource
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziDetailTopBar
import com.lezi.babylog.designsystem.LeziTypography
import com.lezi.babylog.designsystem.RecordRow
import com.lezi.babylog.designsystem.dismissKeyboardOnTap
import com.lezi.babylog.designsystem.StateContainer
import com.lezi.babylog.designsystem.StateKind
import com.lezi.babylog.designsystem.LeziTextField
import com.lezi.babylog.designsystem.LeziIconButton
import com.lezi.babylog.domain.carelog.formatClock
import com.lezi.babylog.domain.carelog.relativeTimeLabel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SearchUi(
    val query: String = "",
    val results: List<SearchResult> = emptyList(),
    val searching: Boolean = false,
    /** Copy lives in stringResource; tests assert state, not text. */
    val failed: Boolean = false,
    val noticeMessage: String? = null,
)

data class SearchRecordOpen(
    val recordId: Long,
    val canEdit: Boolean,
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: SearchRepository,
) : ViewModel() {
    private val query = MutableStateFlow("")
    private val results = MutableStateFlow<List<SearchResult>>(emptyList())
    private val searching = MutableStateFlow(false)
    private val failed = MutableStateFlow(false)
    private val noticeMessage = MutableStateFlow<String?>(null)
    private var job: Job? = null
    private var queryGeneration = 0L

    val ui = combine(query, results, searching, failed, noticeMessage) {
        q, r, s, error, notice ->
        SearchUi(q, r, s, error, notice)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUi())

    fun onQuery(q: String) {
        val limited = limitSearchQuery(q)
        val generation = ++queryGeneration
        query.value = limited
        job?.cancel()
        failed.value = false
        noticeMessage.value = null
        job = viewModelScope.launch {
            searching.value = true
            try {
                delay(200)
                if (generation != queryGeneration) return@launch
                if (limited.isBlank()) {
                    results.value = emptyList()
                    return@launch
                }
                coroutineScope {
                    val mailbox = Channel<SearchRevision>(Channel.CONFLATED)
                    val firstEmission = CompletableDeferred<SearchRevision?>()
                    launch {
                        var first = true
                        try {
                            repository.revisions().collect { token ->
                                if (first) {
                                    first = false
                                    firstEmission.complete(token)
                                }
                                mailbox.trySend(token)
                            }
                        } finally {
                            if (first) firstEmission.complete(null)
                            mailbox.close()
                        }
                    }
                    val seen = firstEmission.await()
                    val found = repository.search(limited)
                    if (generation != queryGeneration) return@coroutineScope
                    results.value = found
                    searching.value = false
                    if (seen == null) return@coroutineScope
                    // The first mailbox value is the snapshot at collect time.
                    // Skip it only when search still observed that same snapshot.
                    // A change during search is already the latest conflated value.
                    var skippedBaseline = false
                    for (token in mailbox) {
                        if (!skippedBaseline) {
                            skippedBaseline = true
                            if (token == seen) continue
                        }
                        try {
                            val refreshed = repository.search(limited)
                            if (generation != queryGeneration) return@coroutineScope
                            results.value = refreshed
                            failed.value = false
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Throwable) {
                            if (generation == queryGeneration) {
                                failed.value = true
                            }
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                if (generation == queryGeneration) {
                    failed.value = true
                }
            } finally {
                if (generation == queryGeneration) {
                    searching.value = false
                }
            }
        }
    }

    fun retry() {
        val currentQuery = query.value
        if (currentQuery.isNotBlank()) onQuery(currentQuery)
    }

    /** Same edit-or-view mapping as the timeline after list intercepts. */
    fun requestOpenRecord(result: SearchResult): SearchRecordOpen {
        noticeMessage.value = null
        return SearchRecordOpen(recordId = result.record.id, canEdit = result.canEdit)
    }

    fun clearNotice() {
        noticeMessage.value = null
    }
}

@Composable
fun SearchRoute(
    onBack: () -> Unit,
    onOpenRecord: (SearchRecordOpen) -> Unit,
    vm: SearchViewModel = hiltViewModel(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    // Show the loading card only when a search actually takes long; fast
    // keystroke searches keep the previous results instead of flashing.
    var showLoading by remember { mutableStateOf(false) }
    LaunchedEffect(ui.searching) {
        if (ui.searching) {
            delay(LOADING_INDICATOR_DELAY_MS)
            showLoading = true
        } else {
            showLoading = false
        }
    }
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
            val phase = when {
                ui.query.isBlank() -> SearchPhase.Prompt
                ui.searching && (showLoading || ui.results.isEmpty()) -> SearchPhase.Loading
                ui.failed -> SearchPhase.Error
                ui.results.isEmpty() -> SearchPhase.Empty
                else -> SearchPhase.Results
            }
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
                    LazyColumn(
                        contentPadding = PaddingValues(top = LeziSpacing.Md, bottom = LeziSpacing.Xxl),
                        verticalArrangement = Arrangement.spacedBy(LeziSpacing.Xs),
                    ) {
                        items(ui.results, key = { it.record.id }) { hit ->
                            val r = hit.record
                            val title = r.displayLabel()
                            val editDescription = stringResource(
                                R.string.search_edit_action,
                                title,
                            )
                            val viewDescription = stringResource(
                                R.string.search_view_action,
                                title,
                            )
                            RecordRow(
                                time = formatClock(r.timestamp),
                                title = title,
                                summary = r.presentationSummary(),
                                relative = relativeTimeLabel(r.timestamp),
                                tone = r.type.presentationTone(),
                                leading = { RecordTypeIcon(r.type) },
                                onClick = {
                                    onOpenRecord(vm.requestOpenRecord(hit))
                                },
                                modifier = Modifier.animateItem().semantics {
                                    contentDescription = if (hit.canEdit) {
                                        editDescription
                                    } else {
                                        viewDescription
                                    }
                                },
                            )
                        }
                    }
                }
            }
            }
        }
    }
}

private enum class SearchPhase { Prompt, Loading, Error, Empty, Results }

private const val LOADING_INDICATOR_DELAY_MS = 300L

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
