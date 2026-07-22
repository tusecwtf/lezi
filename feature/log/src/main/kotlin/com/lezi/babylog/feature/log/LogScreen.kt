package com.lezi.babylog.feature.log

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.lezi.babylog.core.database.BabyDao
import com.lezi.babylog.core.database.LocalUserDao
import com.lezi.babylog.core.database.RecordEntity
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.ui.UiTags
import com.lezi.babylog.domain.DailySummary
import com.lezi.babylog.domain.RecordInteractor
import com.lezi.babylog.domain.aggregateDaily
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class LogUiState(
    val babyName: String = "",
    val babyId: Long? = null,
    val records: List<RecordEntity> = emptyList(),
    val summary: DailySummary = DailySummary(),
)

@HiltViewModel
class LogViewModel @Inject constructor(
    private val babyDao: BabyDao,
    private val localUserDao: LocalUserDao,
    private val records: RecordInteractor,
) : ViewModel() {

    private val zone = ZoneId.systemDefault()

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState = babyDao.observeAll()
        .flatMapLatest { babies ->
            val baby = babies.firstOrNull()
            if (baby == null) {
                flowOf(LogUiState())
            } else {
                val day = LocalDate.now(zone)
                val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
                val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                records.observeDay(baby.id, start, end).map { list ->
                    LogUiState(
                        babyName = baby.nickname,
                        babyId = baby.id,
                        records = list,
                        summary = aggregateDaily(list),
                    )
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LogUiState())

    fun quickAdd(type: RecordType, payloadJson: String = "{}", endTimestamp: Long? = null) {
        viewModelScope.launch {
            val babyId = uiState.value.babyId ?: return@launch
            val userId = localUserDao.get()?.id ?: 1L
            val start = if (type == RecordType.SLEEP && endTimestamp != null) {
                endTimestamp - 60 * 60_000L
            } else {
                System.currentTimeMillis()
            }
            records.quickAdd(
                babyId = babyId,
                type = type,
                userId = userId,
                timestamp = start,
                payloadJson = payloadJson,
                endTimestamp = endTimestamp,
            )
        }
    }
}

private val quickActions = listOf(
    Triple(RecordType.FORMULA, "配方奶", """{"amount_ml":120}"""),
    Triple(RecordType.PEE, "尿尿", "{}"),
    Triple(RecordType.POOP, "便便", """{"stool_amount":3,"stool_consistency":3,"stool_color":2}"""),
    Triple(RecordType.BOTH_DIAPER, "尿+便", "{}"),
    Triple(RecordType.NURSING, "母乳", """{"left_min":10,"right_min":5,"order":"LR"}"""),
    Triple(RecordType.MEMO, "备注", "{}"),
    Triple(RecordType.TEMPERATURE, "体温", """{"celsius":36.5}"""),
    Triple(RecordType.BATH, "洗澡", "{}"),
)

private fun labelOf(type: String): String =
    quickActions.find { it.first.key == type }?.second
        ?: when (type) {
            RecordType.SLEEP.key -> "睡眠"
            else -> type
        }

private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LogRoute(vm: LogViewModel = hiltViewModel()) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp)
            .testTag(UiTags.LOG_HOME),
    ) {
        Text(
            text = state.babyName.ifBlank { "乐记" },
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = "今日 · 睡 ${state.summary.sleepMinutes} 分 · 尿 ${state.summary.peeCount} · 便 ${state.summary.poopCount} · 奶 ${state.summary.formulaMl + state.summary.pumpedFeedMl} ml · 母乳 ${state.summary.nursingMinutes} 分",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            quickActions.forEach { (type, label, payload) ->
                AssistChip(
                    onClick = { vm.quickAdd(type, payload) },
                    label = { Text(label) },
                )
            }
            AssistChip(
                onClick = {
                    val end = System.currentTimeMillis()
                    vm.quickAdd(RecordType.SLEEP, endTimestamp = end)
                },
                label = { Text("睡眠1h") },
            )
        }
        Spacer(Modifier.height(12.dp))
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.records, key = { it.id }) { r ->
                RecordRow(r)
            }
        }
    }
}

@Composable
private fun RecordRow(r: RecordEntity) {
    val time = Instant.ofEpochMilli(r.timestamp)
        .atZone(ZoneId.systemDefault())
        .toLocalTime()
        .format(timeFmt)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("${labelOf(r.type)} · $time", style = MaterialTheme.typography.titleMedium)
            val note = r.note
            if (!note.isNullOrBlank()) {
                Text(note, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (r.payloadJson != "{}") {
                Text(r.payloadJson, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
