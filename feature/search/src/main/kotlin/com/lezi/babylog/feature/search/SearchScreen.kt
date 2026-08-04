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
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import com.lezi.babylog.domain.carelog.formatClock
import com.lezi.babylog.domain.carelog.relativeTimeLabel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SearchUi(
    val query: String = "",
    val results: List<SearchResult> = emptyList(),
    val searching: Boolean = false,
    val errorMessage: String? = null,
    /** Transient product Chinese when a non-manager taps a row (same copy as domain). */
    val noticeMessage: String? = null,
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: SearchRepository,
) : ViewModel() {
    private val query = MutableStateFlow("")
    private val results = MutableStateFlow<List<SearchResult>>(emptyList())
    private val searching = MutableStateFlow(false)
    private val errorMessage = MutableStateFlow<String?>(null)
    private val noticeMessage = MutableStateFlow<String?>(null)
    private var job: Job? = null
    private var queryGeneration = 0L

    val ui = combine(query, results, searching, errorMessage, noticeMessage) {
            q, r, s, error, notice ->
        SearchUi(q, r, s, error, notice)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUi())

    fun onQuery(q: String) {
        val limited = limitSearchQuery(q)
        val generation = ++queryGeneration
        query.value = limited
        job?.cancel()
        errorMessage.value = null
        noticeMessage.value = null
        job = viewModelScope.launch {
            searching.value = true
            try {
                delay(200)
                val found = repository.search(limited)
                if (generation == queryGeneration) {
                    results.value = found
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                if (generation == queryGeneration) {
                    errorMessage.value = SEARCH_ERROR_MESSAGE
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

    /**
     * Timeline-equivalent gate: only managers open the composer.
     * @return record id to open, or null when denied (notice already set).
     */
    fun requestOpenEdit(result: SearchResult): Long? {
        if (result.canEdit) {
            noticeMessage.value = null
            return result.record.id
        }
        noticeMessage.value = RECORD_MANAGE_DENIED_MESSAGE
        return null
    }

    fun clearNotice() {
        noticeMessage.value = null
    }
}

@Composable
fun SearchRoute(
    onBack: () -> Unit,
    onOpenEdit: (Long) -> Unit,
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
            LeziDetailTopBar(title = "搜索", onBack = onBack)
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(LeziSpacing.Page)
                .dismissKeyboardOnTap(),
        ) {
            OutlinedTextField(
                value = ui.query,
                onValueChange = vm::onQuery,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("类型 / 详情 / 备注") },
                placeholder = { Text("例如：睡眠、布洛芬、发烧") },
                leadingIcon = {
                    Icon(Icons.Outlined.Search, contentDescription = null)
                },
                trailingIcon = if (ui.query.isNotEmpty()) {
                    {
                        IconButton(onClick = { vm.onQuery("") }) {
                            Icon(Icons.Outlined.Close, contentDescription = "清除搜索")
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
                ui.errorMessage != null -> SearchPhase.Error
                ui.results.isEmpty() -> SearchPhase.Empty
                else -> SearchPhase.Results
            }
            Crossfade(targetState = phase, label = "searchPhase") { target ->
            when (target) {
                SearchPhase.Prompt -> {
                    StateContainer(
                        kind = StateKind.Empty,
                        title = "输入关键字",
                        message = "搜索当前宝宝的记录类型、详情与备注",
                        modifier = Modifier.padding(top = LeziSpacing.Md),
                    )
                }
                SearchPhase.Loading -> {
                    StateContainer(
                        kind = StateKind.Loading,
                        title = "搜索中",
                        message = "正在查找…",
                        modifier = Modifier.padding(top = LeziSpacing.Md),
                    )
                }
                SearchPhase.Error -> {
                    StateContainer(
                        kind = StateKind.Error,
                        title = "暂时无法搜索",
                        message = ui.errorMessage.orEmpty(),
                        modifier = Modifier.padding(top = LeziSpacing.Md),
                        actionLabel = "重试",
                        onAction = vm::retry,
                    )
                }
                SearchPhase.Empty -> {
                    StateContainer(
                        kind = StateKind.Empty,
                        title = "无结果",
                        message = "没有匹配「${searchQueryPreview(ui.query)}」的记录",
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
                            RecordRow(
                                time = formatClock(r.timestamp),
                                title = title,
                                summary = r.presentationSummary(),
                                relative = relativeTimeLabel(r.timestamp),
                                tone = r.type.presentationTone(),
                                leading = { RecordTypeIcon(r.type) },
                                onClick = {
                                    vm.requestOpenEdit(hit)?.let(onOpenEdit)
                                },
                                modifier = Modifier.animateItem().semantics {
                                    contentDescription = if (hit.canEdit) {
                                        "编辑$title"
                                    } else {
                                        "无权编辑$title"
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
private const val SEARCH_ERROR_MESSAGE = "搜索失败，请重试"
/** Same product Chinese as [com.lezi.babylog.domain.RecordPermissionException]. */
internal const val RECORD_MANAGE_DENIED_MESSAGE = "无权管理此护理记录"

internal fun limitSearchQuery(value: String, maxCodePoints: Int = MAX_SEARCH_QUERY_CODE_POINTS): String {
    if (value.codePointCount(0, value.length) <= maxCodePoints) return value
    return value.substring(0, value.offsetByCodePoints(0, maxCodePoints))
}

internal fun searchQueryPreview(value: String): String {
    val limited = limitSearchQuery(value, SEARCH_QUERY_PREVIEW_CODE_POINTS)
    return if (limited == value) limited else "$limited…"
}
