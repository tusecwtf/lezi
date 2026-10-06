package com.lezi.babylog.feature.log.layout
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.roundToInt
import com.lezi.babylog.feature.log.*
import com.lezi.babylog.feature.log.timeline.*
import com.lezi.babylog.feature.log.dock.*
import com.lezi.babylog.feature.log.composer.*
import com.lezi.babylog.feature.log.photo.*

internal data class LayoutEditSessionContext(
    val babyId: Long?,
    val day: LocalDate,
)

internal data class LayoutCatalogScrollPosition(
    val value: Int = 0,
    val maxValue: Int = 0,
) {
    fun valueFor(maxValue: Int): Int {
        val requestedMax = maxValue.coerceAtLeast(0)
        val currentValue = value.coerceAtLeast(0)
        val currentMax = this.maxValue.coerceAtLeast(0)
        if (requestedMax <= 0) return 0
        if (currentMax <= 0) return currentValue.coerceIn(0, requestedMax)
        val fraction = currentValue.coerceIn(0, currentMax).toFloat() / currentMax
        return (fraction * requestedMax).roundToInt().coerceIn(0, requestedMax)
    }
}

internal data class LayoutEditSession(
    val context: LayoutEditSessionContext,
    val prefs: DeviceLayoutPrefs,
    val catalogScroll: LayoutCatalogScrollPosition = LayoutCatalogScrollPosition(),
    val hasSubmittedIntent: Boolean = false,
    val dragGuidance: LayoutDragGuidanceState,
)

internal data class RetainedLayoutEditPresentation(
    val session: LayoutEditSession,
    val writeState: DeviceLayoutWriteState,
)

internal fun layoutEditPresentation(
    session: LayoutEditSession?,
    writeState: DeviceLayoutWriteState,
): RetainedLayoutEditPresentation? = session?.let {
    RetainedLayoutEditPresentation(it, writeState)
}

/**
 * ViewModel-owned UI session state. Deliberately has no SavedStateHandle adapter:
 * configuration recreation retains the store, while process restart starts idle.
 */
internal class LayoutEditSessionStore {
    private val mutableState = MutableStateFlow<LayoutEditSession?>(null)
    val state: StateFlow<LayoutEditSession?> = mutableState.asStateFlow()

    val current: LayoutEditSession?
        get() = mutableState.value

    fun open(
        context: LayoutEditSessionContext,
        prefs: DeviceLayoutPrefs,
        guidanceCompleted: Boolean = false,
    ) {
        mutableState.value = LayoutEditSession(
            context = context,
            prefs = prefs,
            dragGuidance = initialLayoutDragGuidanceState(guidanceCompleted),
        )
    }

    fun updateCatalogScroll(position: LayoutCatalogScrollPosition) {
        mutableState.update { current -> current?.copy(catalogScroll = position) }
    }

    fun updatePrefs(prefs: DeviceLayoutPrefs, hasSubmittedIntent: Boolean) {
        mutableState.update { current ->
            current?.copy(
                prefs = prefs,
                hasSubmittedIntent = current.hasSubmittedIntent || hasSubmittedIntent,
            )
        }
    }

    fun reduceDragGuidance(
        event: LayoutDragGuidanceEvent,
    ): LayoutDragGuidanceReduction? {
        var applied: LayoutDragGuidanceReduction? = null
        mutableState.update { current ->
            current?.let { session ->
                reduceLayoutDragGuidance(session.dragGuidance, event).let { reduction ->
                    applied = reduction
                    session.copy(dragGuidance = reduction.state)
                }
            }
        }
        return applied
    }

    fun close() {
        mutableState.value = null
    }
}
