package com.lezi.babylog.feature.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.ui.presentation
import com.lezi.babylog.domain.CareLog
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

@AndroidEntryPoint
class WidgetConfigurationActivity : ComponentActivity() {
    @Inject lateinit var careLog: CareLog
    @Inject lateinit var controller: CareWidgetRefreshController

    private var screenState by mutableStateOf<WidgetConfigurationScreenState?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(Activity.RESULT_CANCELED)
        val widgetId = intent?.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    val state = screenState
                    if (state == null) {
                        Column(
                            Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            CircularProgressIndicator()
                        }
                    } else {
                        WidgetConfigurationScreen(
                            state = state,
                            onStateChange = { screenState = it },
                            onSave = { save(it) },
                        )
                    }
                }
            }
        }
        lifecycleScope.launch {
            val babies = careLog.listBabies()
                .map { WidgetBabyOption(it.id, it.nickname) }
            if (babies.isEmpty()) {
                finish()
                return@launch
            }
            val existing = controller.configuration(widgetId)
            val selectedBabyId = existing?.babyId
                ?.takeIf { selected -> babies.any { it.id == selected } }
                ?: careLog.getCurrentBaby()?.id
                ?: babies.first().id
            screenState = WidgetConfigurationScreenState(
                widgetId = widgetId,
                babies = babies,
                selectedBabyId = selectedBabyId,
                selectedTypes = existing?.quickTypes ?: DEFAULT_WIDGET_QUICK_TYPES,
            )
        }
    }

    private fun save(state: WidgetConfigurationScreenState) {
        if (!state.canSave) return
        lifecycleScope.launch {
            controller.configure(
                WidgetConfiguration(
                    widgetId = state.widgetId,
                    babyId = state.selectedBabyId,
                    quickTypes = state.selectedTypes,
                ),
            )
            val result = Intent().putExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID,
                state.widgetId,
            )
            setResult(Activity.RESULT_OK, result)
            finish()
        }
    }
}

internal data class WidgetBabyOption(
    val id: Long,
    val nickname: String,
)

internal data class WidgetConfigurationScreenState(
    val widgetId: Int,
    val babies: List<WidgetBabyOption>,
    val selectedBabyId: Long,
    val selectedTypes: List<RecordType>,
) {
    val canSave: Boolean
        get() = selectedBabyId > 0 &&
            selectedTypes.isNotEmpty() &&
            selectedTypes.size <= MAX_WIDGET_QUICK_TYPES

    fun selectBaby(babyId: Long): WidgetConfigurationScreenState =
        copy(selectedBabyId = babyId)

    fun toggle(type: RecordType): WidgetConfigurationScreenState {
        val next = when {
            type in selectedTypes -> selectedTypes - type
            selectedTypes.size >= MAX_WIDGET_QUICK_TYPES -> selectedTypes
            else -> selectedTypes + type
        }
        return copy(selectedTypes = next)
    }
}

@Composable
private fun WidgetConfigurationScreen(
    state: WidgetConfigurationScreenState,
    onStateChange: (WidgetConfigurationScreenState) -> Unit,
    onSave: (WidgetConfigurationScreenState) -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("设置乐记小组件", style = MaterialTheme.typography.headlineSmall)
        Text("选择宝宝", style = MaterialTheme.typography.titleMedium)
        state.babies.forEach { baby ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = state.selectedBabyId == baby.id,
                    onClick = { onStateChange(state.selectBaby(baby.id)) },
                )
                Text(baby.nickname)
            }
        }
        Text(
            "快捷记录（最多 $MAX_WIDGET_QUICK_TYPES 个）",
            style = MaterialTheme.typography.titleMedium,
        )
        CONFIGURABLE_WIDGET_QUICK_TYPES.forEach { type ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = type in state.selectedTypes,
                    onCheckedChange = { onStateChange(state.toggle(type)) },
                )
                Text(type.presentation.label)
            }
        }
        Button(
            onClick = { onSave(state) },
            enabled = state.canSave,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("保存小组件")
        }
    }
}
