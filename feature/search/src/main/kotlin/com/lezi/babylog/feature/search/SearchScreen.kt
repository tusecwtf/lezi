package com.lezi.babylog.feature.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.lezi.babylog.core.model.Record
import com.lezi.babylog.core.model.displayLabel
import com.lezi.babylog.core.ui.RecordTypeIcon
import com.lezi.babylog.core.ui.presentation
import com.lezi.babylog.core.ui.presentationSummary
import com.lezi.babylog.core.ui.presentationTone
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziDetailTopBar
import com.lezi.babylog.designsystem.LeziTypography
import com.lezi.babylog.designsystem.RecordRow
import com.lezi.babylog.designsystem.dismissKeyboardOnTap
import com.lezi.babylog.designsystem.StateContainer
import com.lezi.babylog.designsystem.StateKind
import com.lezi.babylog.domain.CareLog
import com.lezi.babylog.domain.formatClock
import com.lezi.babylog.domain.relativeTimeLabel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SearchUi(
    val query: String = "",
    val results: List<Record> = emptyList(),
    val searching: Boolean = false,
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val careLog: CareLog,
) : ViewModel() {
    private val query = MutableStateFlow("")
    private val results = MutableStateFlow<List<Record>>(emptyList())
    private val searching = MutableStateFlow(false)
    private var job: Job? = null

    val ui = combine(query, results, searching) { q, r, s ->
        SearchUi(q, r, s)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUi())

    fun onQuery(q: String) {
        val limited = limitSearchQuery(q)
        query.value = limited
        job?.cancel()
        job = viewModelScope.launch {
            searching.value = true
            delay(200)
            val baby = careLog.getCurrentBaby()
            results.value = if (baby == null) emptyList() else careLog.search(baby.id, limited)
            searching.value = false
        }
    }
}

@Composable
fun SearchRoute(
    onBack: () -> Unit,
    onOpenEdit: (Long) -> Unit,
    vm: SearchViewModel = hiltViewModel(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
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
            )
            when {
                ui.query.isBlank() -> {
                    StateContainer(
                        kind = StateKind.Empty,
                        title = "输入关键字",
                        message = "搜索当前宝宝的记录类型、详情与备注",
                        modifier = Modifier.padding(top = LeziSpacing.Md),
                    )
                }
                ui.searching -> {
                    StateContainer(
                        kind = StateKind.Loading,
                        title = "搜索中",
                        message = "正在查找…",
                        modifier = Modifier.padding(top = LeziSpacing.Md),
                    )
                }
                ui.results.isEmpty() -> {
                    StateContainer(
                        kind = StateKind.Empty,
                        title = "无结果",
                        message = "没有匹配「${searchQueryPreview(ui.query)}」的记录",
                        modifier = Modifier.padding(top = LeziSpacing.Md),
                    )
                }
                else -> {
                    LazyColumn(
                        contentPadding = PaddingValues(top = LeziSpacing.Md, bottom = LeziSpacing.Xxl),
                        verticalArrangement = Arrangement.spacedBy(LeziSpacing.Xs),
                    ) {
                        items(ui.results, key = { it.id }) { r ->
                            val title = r.displayLabel()
                            RecordRow(
                                time = formatClock(r.timestamp),
                                title = title,
                                summary = r.presentationSummary(),
                                relative = relativeTimeLabel(r.timestamp),
                                tone = r.type.presentationTone(),
                                leading = { RecordTypeIcon(r.type) },
                                onClick = { onOpenEdit(r.id) },
                                modifier = Modifier.semantics {
                                    contentDescription = "编辑$title"
                                },
                            )
                        }
                    }
                }
            }
        }
    }
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
