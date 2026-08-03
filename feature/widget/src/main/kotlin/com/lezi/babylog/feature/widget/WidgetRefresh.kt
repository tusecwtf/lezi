package com.lezi.babylog.feature.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import com.lezi.babylog.domain.localdata.LocalDataClearInProgressException
import com.lezi.babylog.domain.localdata.LocalDataEpochInvalidatedException
import com.lezi.babylog.domain.localdata.LocalDataMutationEpoch
import com.lezi.babylog.domain.localdata.WidgetCleanupPort
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

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

    suspend fun refresh(
        widgetId: Int,
        localDataMutationEpoch: LocalDataMutationEpoch,
    ): WidgetDisplayModel {
        val expectedGeneration = localDataMutationEpoch.currentGeneration()
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
                lastLabelIsCanonical = true,
            )
            localDataMutationEpoch.withMutationInGeneration(expectedGeneration) {
                store.saveSnapshot(snapshot)
            }
            configuredWidgetDisplayModel(configuration, snapshot)
        } catch (_: WidgetTargetBabyUnavailableException) {
            localDataMutationEpoch.withMutationInGeneration(expectedGeneration) {
                store.remove(widgetId)
            }
            unconfiguredWidgetDisplayModel(widgetId)
        } catch (clear: LocalDataClearInProgressException) {
            throw clear
        } catch (invalidated: LocalDataEpochInvalidatedException) {
            throw invalidated
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

internal class WidgetStateCleaner(
    private val store: WidgetStateStore,
) {
    fun clearAll(): List<Int> = store.configurations()
        .map(WidgetConfiguration::widgetId)
        .sorted()
        .also { store.clearAll() }
}

@Singleton
class CareWidgetRefreshController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: WidgetStateStore,
    summarySource: WidgetSummarySource,
    private val localDataMutationEpoch: LocalDataMutationEpoch,
) {
    private val engine = WidgetRefreshEngine(store, summarySource)
    private val configuredBabyIds = MutableStateFlow(readConfiguredBabyIds())

    internal fun observeConfiguredBabyIds() = configuredBabyIds.asStateFlow()

    fun configuration(widgetId: Int): WidgetConfiguration? = store.configuration(widgetId)

    fun configurations(): List<WidgetConfiguration> = store.configurations()

    suspend fun configure(configuration: WidgetConfiguration): WidgetDisplayModel {
        val expectedGeneration = localDataMutationEpoch.currentGeneration()
        localDataMutationEpoch.withMutationInGeneration(expectedGeneration) {
            store.saveConfiguration(configuration)
        }
        publishConfiguredBabyIds()
        val display = engine.refresh(configuration.widgetId, localDataMutationEpoch)
        update(configuration.widgetId)
        return display
    }

    suspend fun refreshWidget(widgetId: Int): WidgetDisplayModel {
        val display = engine.refresh(widgetId, localDataMutationEpoch)
        if (!display.isConfigured) publishConfiguredBabyIds()
        update(widgetId)
        return display
    }

    suspend fun refreshAll(): List<WidgetDisplayModel> =
        store.configurations().map { configuration ->
            refreshWidget(configuration.widgetId)
        }

    /** Rebuild cached summaries, then force every launcher instance to redraw. */
    suspend fun rehydrateAllWidgetInstances() {
        refreshAll()
        CareWidgetUpdater.requestUpdate(context)
    }

    suspend fun refreshBaby(babyId: Long): List<WidgetDisplayModel> =
        store.configurations()
            .filter { it.babyId == babyId }
            .map { configuration -> refreshWidget(configuration.widgetId) }

    suspend fun remove(widgetId: Int) {
        val expectedGeneration = localDataMutationEpoch.currentGeneration()
        localDataMutationEpoch.withMutationInGeneration(expectedGeneration) {
            store.remove(widgetId)
        }
        publishConfiguredBabyIds()
    }

    /** Clear persisted family content before redrawing every launcher instance unconfigured. */
    suspend fun clearAllWidgetState() {
        WidgetStateCleaner(store).clearAll()
        publishConfiguredBabyIds()
        CareWidgetUpdater.requestUpdate(context)
    }

    private fun publishConfiguredBabyIds() {
        configuredBabyIds.value = readConfiguredBabyIds()
    }

    private fun readConfiguredBabyIds(): List<Long> =
        store.configurations().map(WidgetConfiguration::babyId).distinct().sorted()

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

@Singleton
class AndroidWidgetCleanupPort @Inject constructor(
    private val controller: CareWidgetRefreshController,
) : WidgetCleanupPort {
    override suspend fun clearAllWidgetState() = controller.clearAllWidgetState()
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class WidgetCleanupModule {
    @Binds
    abstract fun bindWidgetCleanupPort(
        implementation: AndroidWidgetCleanupPort,
    ): WidgetCleanupPort
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
