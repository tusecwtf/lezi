package com.lezi.babylog.feature.widget

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lezi.babylog.core.common.DefaultLocalDataGate
import com.lezi.babylog.core.datastore.SettingsStore
import com.lezi.babylog.core.model.SettingsLocal
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.domain.CareLog
import dagger.Lazy
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal data class WidgetConfigurationUi(
    val settings: SettingsLocal? = null,
    val selection: WidgetConfigurationScreenState? = null,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
    val saved: Boolean = false,
    val close: Boolean = false,
    val openApp: Boolean = false,
)

@HiltViewModel
internal class WidgetConfigurationViewModel @Inject constructor(
    private val localDataGate: DefaultLocalDataGate,
    private val careLog: Lazy<CareLog>,
    private val controller: Lazy<CareWidgetRefreshController>,
    private val savedState: SavedStateHandle,
    private val settingsStore: SettingsStore,
) : ViewModel() {
    private val mutableUi = MutableStateFlow(WidgetConfigurationUi())
    val ui = mutableUi.asStateFlow()

    fun load(widgetId: Int) {
        if (ui.value.loading || ui.value.selection != null || ui.value.saved) return
        mutableUi.value = ui.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                mutableUi.value = ui.value.copy(settings = settingsStore.settings.first())
                if (!localDataGate.ensureReady()) {
                    mutableUi.value = ui.value.copy(loading = false, openApp = true)
                    return@launch
                }
                val log = careLog.get()
                val babies = log.listBabies().map { WidgetBabyOption(it.id, it.nickname) }
                if (babies.isEmpty()) {
                    mutableUi.value = ui.value.copy(loading = false, close = true)
                    return@launch
                }
                val existing = controller.get().configuration(widgetId)
                val restoredBaby = savedState.get<Long>("babyId")
                val selectedBaby = listOf(restoredBaby, existing?.babyId, log.getCurrentBaby()?.id)
                    .firstOrNull { id -> babies.any { it.id == id } } ?: babies.first().id
                val restoredTypes = savedState.get<ArrayList<String>>("quickTypes")?.mapNotNull { name ->
                    RecordType.entries.firstOrNull { it.name == name }
                }
                mutableUi.value = ui.value.copy(loading = false, selection = WidgetConfigurationScreenState(
                    widgetId, babies, selectedBaby,
                    restoredTypes ?: existing?.quickTypes ?: DEFAULT_WIDGET_QUICK_TYPES,
                ))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableUi.value = ui.value.copy(loading = false, error = "加载失败，请重试")
            }
        }
    }

    fun change(selection: WidgetConfigurationScreenState) {
        if (ui.value.saving || ui.value.saved) return
        savedState["babyId"] = selection.selectedBabyId
        savedState["quickTypes"] = ArrayList(selection.selectedTypes.map { it.name })
        mutableUi.value = ui.value.copy(selection = selection, error = null)
    }

    fun save(selection: WidgetConfigurationScreenState) {
        if (ui.value.saving || ui.value.saved || selection != ui.value.selection || !selection.canSave) return
        // Latch before launching: rapid clicks cannot enqueue a second write.
        mutableUi.value = ui.value.copy(saving = true, error = null)
        viewModelScope.launch {
            try {
                check(localDataGate.ensureReady())
                val display = controller.get().configure(WidgetConfiguration(
                    widgetId = selection.widgetId,
                    babyId = selection.selectedBabyId,
                    quickTypes = selection.selectedTypes,
                ))
                check(display.isConfigured)
                mutableUi.value = ui.value.copy(saving = false, saved = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableUi.value = ui.value.copy(saving = false, error = "保存失败，请重试")
            }
        }
    }
}
