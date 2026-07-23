package com.lezi.babylog.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.lezi.babylog.core.common.newClientUuid
import com.lezi.babylog.core.database.CalendarEventDao
import com.lezi.babylog.core.database.CalendarEventEntity
import com.lezi.babylog.designsystem.LeziCard
import com.lezi.babylog.designsystem.LeziPrimaryButton
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziTypography
import com.lezi.babylog.domain.CareLog
import com.lezi.babylog.domain.formatClock
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class CalendarViewModel @Inject constructor(
    private val careLog: CareLog,
    private val calendarEventDao: CalendarEventDao,
) : ViewModel() {
    private val zone = ZoneId.systemDefault()

    @OptIn(ExperimentalCoroutinesApi::class)
    val events = careLog.observeCurrentBaby().flatMapLatest { baby ->
        if (baby == null) flowOf(emptyList())
        else {
            val start = LocalDate.now(zone).withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val end = LocalDate.now(zone).withDayOfMonth(1).plusMonths(2).atStartOfDay(zone).toInstant().toEpochMilli()
            calendarEventDao.observeRange(baby.id, start, end)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun add(title: String, onDone: () -> Unit) {
        viewModelScope.launch {
            val baby = careLog.getCurrentBaby() ?: return@launch
            val now = System.currentTimeMillis()
            calendarEventDao.upsert(
                CalendarEventEntity(
                    clientUuid = newClientUuid(),
                    babyId = baby.id,
                    title = title,
                    eventAt = now + 24 * 3600_000L,
                    remindAt = now + 23 * 3600_000L,
                    updatedAt = now,
                ),
            )
            onDone()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarRoute(
    onBack: () -> Unit,
    vm: CalendarViewModel = hiltViewModel(),
) {
    val events by vm.events.collectAsStateWithLifecycle()
    var showAdd by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("日程") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(LeziSpacing.Page),
        ) {
            LeziPrimaryButton("添加日程", onClick = { showAdd = true }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(LeziSpacing.Sm))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(LeziSpacing.Xs)) {
                items(events, key = { it.id }) { e ->
                    LeziCard(Modifier.fillMaxWidth()) {
                        Text(e.title, style = LeziTypography.BodyStrong)
                        Text(formatClock(e.eventAt), style = LeziTypography.Meta)
                        e.remindAt?.let {
                            Text("提醒 ${formatClock(it)}", style = LeziTypography.Meta)
                        }
                    }
                }
            }
        }
    }
    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("新日程") },
            text = {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("标题") }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    if (title.isNotBlank()) vm.add(title.trim()) {
                        title = ""
                        showAdd = false
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("取消") } },
        )
    }
}
