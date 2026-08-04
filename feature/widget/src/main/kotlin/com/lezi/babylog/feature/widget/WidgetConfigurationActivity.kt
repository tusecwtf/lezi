package com.lezi.babylog.feature.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.lezi.babylog.core.common.LocalDataGate
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.ui.presentation
import com.lezi.babylog.domain.CareLog
import com.lezi.babylog.designsystem.LeziAlphas
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziTheme
import com.lezi.babylog.designsystem.LeziPrimaryButton
import dagger.Lazy
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

@AndroidEntryPoint
class WidgetConfigurationActivity : ComponentActivity() {
    @Inject lateinit var localDataGate: LocalDataGate
    @Inject lateinit var careLog: Lazy<CareLog>
    @Inject lateinit var controller: Lazy<CareWidgetRefreshController>

    private var screenState by mutableStateOf<WidgetConfigurationScreenState?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
            LeziTheme {
                Surface(
                    Modifier
                        .fillMaxSize()
                        .safeDrawingPadding(),
                ) {
                    Crossfade(targetState = screenState, label = "widgetConfig") { state ->
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
        }
        lifecycleScope.launch {
            if (!localDataGate.ensureReady()) {
                packageManager.getLaunchIntentForPackage(packageName)?.let(::startActivity)
                finish()
                return@launch
            }
            val readyCareLog = careLog.get()
            val readyController = controller.get()
            val babies = readyCareLog.listBabies()
                .map { WidgetBabyOption(it.id, it.nickname) }
            if (babies.isEmpty()) {
                finish()
                return@launch
            }
            val existing = readyController.configuration(widgetId)
            val selectedBabyId = existing?.babyId
                ?.takeIf { selected -> babies.any { it.id == selected } }
                ?: readyCareLog.getCurrentBaby()?.id
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
            if (!localDataGate.ensureReady()) return@launch
            controller.get().configure(
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
            .padding(LeziSpacing.Page),
        verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm),
    ) {
        Text("设置乐记小组件", style = MaterialTheme.typography.headlineSmall)
        Text("选择宝宝", style = MaterialTheme.typography.titleMedium)
        state.babies.forEach { baby ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable { onStateChange(state.selectBaby(baby.id)) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = state.selectedBabyId == baby.id,
                    onClick = null,
                )
                Text(baby.nickname)
            }
        }
        Text(
            "快捷记录（已选 ${state.selectedTypes.size}/$MAX_WIDGET_QUICK_TYPES）",
            style = MaterialTheme.typography.titleMedium,
        )
        CONFIGURABLE_WIDGET_QUICK_TYPES.forEach { type ->
            val enabled = type in state.selectedTypes ||
                state.selectedTypes.size < MAX_WIDGET_QUICK_TYPES
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(enabled = enabled) {
                        onStateChange(state.toggle(type))
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = type in state.selectedTypes,
                    onCheckedChange = null,
                    enabled = enabled,
                )
                Text(
                    type.presentation.label,
                    color = if (enabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = LeziAlphas.Disabled)
                    },
                )
            }
        }
        LeziPrimaryButton(
            label = "保存小组件",
            onClick = { onSave(state) },
            enabled = state.canSave,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
