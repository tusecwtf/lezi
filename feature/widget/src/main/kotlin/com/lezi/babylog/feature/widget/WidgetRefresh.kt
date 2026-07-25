package com.lezi.babylog.feature.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

internal class WidgetRefreshEngine(
    private val store: WidgetStateStore,
    private val summarySource: WidgetSummarySource,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun cachedDisplay(widgetId: Int): WidgetDisplayModel {
        val configuration = store.configuration(widgetId)
            ?: return unconfiguredWidgetDisplayModel(widgetId)
        return configuredWidgetDisplayModel(
            configuration = configuration,
            snapshot = store.snapshot(widgetId),
        )
    }

    suspend fun refresh(widgetId: Int): WidgetDisplayModel {
        val configuration = store.configuration(widgetId)
            ?: return unconfiguredWidgetDisplayModel(widgetId)
        return try {
            val data = summarySource.load(configuration.babyId)
            val snapshot = WidgetSummarySnapshot(
                widgetId = configuration.widgetId,
                babyId = configuration.babyId,
                babyName = data.babyName,
                feedMl = data.feedMl,
                sleepMinutes = data.sleepMinutes,
                peeCount = data.peeCount,
                poopCount = data.poopCount,
                lastLabel = data.lastLabel,
                updatedAtEpochMillis = now(),
            )
            store.saveSnapshot(snapshot)
            configuredWidgetDisplayModel(configuration, snapshot)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            configuredWidgetDisplayModel(
                configuration = configuration,
                snapshot = store.snapshot(widgetId),
                isStale = true,
            )
        }
    }
}

@Singleton
class CareWidgetRefreshController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: WidgetStateStore,
    summarySource: WidgetSummarySource,
) {
    private val engine = WidgetRefreshEngine(store, summarySource)

    fun configuration(widgetId: Int): WidgetConfiguration? = store.configuration(widgetId)

    fun configurations(): List<WidgetConfiguration> = store.configurations()

    suspend fun configure(configuration: WidgetConfiguration): WidgetDisplayModel {
        store.saveConfiguration(configuration)
        val display = engine.refresh(configuration.widgetId)
        update(configuration.widgetId)
        return display
    }

    suspend fun refreshWidget(widgetId: Int): WidgetDisplayModel {
        val display = engine.refresh(widgetId)
        update(widgetId)
        return display
    }

    suspend fun refreshAll(): List<WidgetDisplayModel> =
        store.configurations().map { configuration ->
            refreshWidget(configuration.widgetId)
        }

    suspend fun refreshBaby(babyId: Long): List<WidgetDisplayModel> =
        store.configurations()
            .filter { it.babyId == babyId }
            .map { configuration -> refreshWidget(configuration.widgetId) }

    suspend fun remove(widgetId: Int) {
        store.remove(widgetId)
    }

    private suspend fun update(widgetId: Int) {
        try {
            val glanceId = GlanceAppWidgetManager(context).getGlanceIdBy(widgetId)
            CareWidget().update(context, glanceId)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // A launcher can remove an instance before its deletion broadcast arrives.
            // Configuration and record writes must remain successful if redraw fails.
        }
    }
}

/**
 * Redraws the last persisted snapshot. Call [CareWidgetRefreshController] when
 * the underlying records or bound baby changed and fresh domain data is needed.
 */
object CareWidgetUpdater {
    suspend fun requestUpdate(context: Context) {
        val manager = GlanceAppWidgetManager(context)
        val widget = CareWidget()
        manager.getGlanceIds(CareWidget::class.java).forEach { id ->
            widget.update(context, id)
        }
    }
}
