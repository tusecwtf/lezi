package com.lezi.babylog.feature.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.SettingsLocal
import com.lezi.babylog.core.ui.presentation
import com.lezi.babylog.designsystem.LeziAlphas
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziTheme
import com.lezi.babylog.designsystem.LeziThemeExt
import com.lezi.babylog.designsystem.LeziPrimaryButton
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class WidgetConfigurationActivity : ComponentActivity() {
    private val model: WidgetConfigurationViewModel by viewModels()

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
        model.load(widgetId)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.ui.collect { ui ->
                    if (ui.saved) {
                        setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
                        finish()
                    } else if (ui.close) {
                        finish()
                    } else if (ui.openApp) {
                        packageManager.getLaunchIntentForPackage(packageName)?.let(::startActivity)
                        finish()
                    }
                }
            }
        }
        setContent {
            // Do not render a default light/small-text frame before local preferences arrive.
            val ui by model.ui.collectAsStateWithLifecycle()
            if (ui.settings == null && ui.error == null) return@setContent
            val settings = ui.settings ?: SettingsLocal()
            val systemDark = isSystemInDarkTheme()
            val dark = when (settings.darkMode) {
                "dark" -> true
                "light" -> false
                else -> systemDark
            }
            LeziTheme(darkTheme = dark, visualStyle = settings.visualStyle, elderMode = settings.elderMode) {
                val background = MaterialTheme.colorScheme.surface.toArgb()
                SideEffect {
                    val style = if (dark) SystemBarStyle.dark(background)
                        else SystemBarStyle.light(background, background)
                    enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                }
                Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                    WidgetConfigurationContent(ui, model::change, model::save, { model.load(widgetId) })
                }
            }
        }
    }
}

@Composable
internal fun WidgetConfigurationContent(
    ui: WidgetConfigurationUi,
    onStateChange: (WidgetConfigurationScreenState) -> Unit,
    onSave: (WidgetConfigurationScreenState) -> Unit,
    onRetry: () -> Unit,
) {
    val state = ui.selection
    if (state != null) {
        WidgetConfigurationScreen(state, onStateChange, onSave, ui.saving, ui.error)
    } else {
        Column(
            Modifier.fillMaxSize().padding(LeziSpacing.Page),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (ui.error != null) {
                Text(ui.error, style = LeziThemeExt.typography.Body)
                LeziPrimaryButton(label = "重试", onClick = onRetry)
            } else {
                CircularProgressIndicator()
            }
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
internal fun WidgetConfigurationScreen(
    state: WidgetConfigurationScreenState,
    onStateChange: (WidgetConfigurationScreenState) -> Unit,
    onSave: (WidgetConfigurationScreenState) -> Unit,
    saving: Boolean = false,
    saveError: String? = null,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(LeziSpacing.Page),
        verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm),
    ) {
        Text("设置乐记小组件", style = LeziThemeExt.typography.Title)
        Text("选择宝宝", style = LeziThemeExt.typography.TitleSm)
        state.babies.forEach { baby ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = WidgetChrome.configRowMinHeight)
                    .selectable(
                        selected = state.selectedBabyId == baby.id,
                        enabled = !saving,
                        role = Role.RadioButton,
                        onClick = { onStateChange(state.selectBaby(baby.id)) },
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = state.selectedBabyId == baby.id,
                    enabled = !saving,
                    onClick = null,
                )
                Text(baby.nickname, style = LeziThemeExt.typography.Body)
            }
        }
        Text(
            "快捷记录（已选 ${state.selectedTypes.size}/$MAX_WIDGET_QUICK_TYPES）",
            style = LeziThemeExt.typography.TitleSm,
        )
        CONFIGURABLE_WIDGET_QUICK_TYPES.forEach { type ->
            val enabled = !saving && (type in state.selectedTypes ||
                state.selectedTypes.size < MAX_WIDGET_QUICK_TYPES)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = WidgetChrome.configRowMinHeight)
                    .toggleable(
                        value = type in state.selectedTypes,
                        enabled = enabled,
                        role = Role.Checkbox,
                        onValueChange = { onStateChange(state.toggle(type)) },
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = type in state.selectedTypes,
                    onCheckedChange = null,
                    enabled = enabled,
                )
                Text(
                    type.presentation.label,
                    style = LeziThemeExt.typography.Body,
                    color = if (enabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = LeziAlphas.Disabled)
                    },
                )
            }
        }
        saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        LeziPrimaryButton(
            label = if (saving) "保存中…" else "保存小组件",
            onClick = { onSave(state) },
            enabled = state.canSave && !saving,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
