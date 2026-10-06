package com.lezi.babylog.feature.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.glance.LocalSize
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.currentState
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.lezi.babylog.core.common.DefaultLocalDataGate
import com.lezi.babylog.core.common.cancellation.cancellationCauseOrNull
import kotlinx.coroutines.CancellationException
import dagger.Lazy
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Glance RemoteViews run in the launcher process; LeziTheme/fontScale never reach
 * here. Elder mode is out of scope. The palette comes from [leziGlanceColors]
 * (LeziColors light/dark, resolved by the system dark mode), not system dynamic
 * colors — 0.5.4 ticket 17.
 */
class CareWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    /**
     * Live sessions apply [update] by replacing this state and recomposing.
     * [provideGlance] itself does not run again, so the snapshot must be read
     * from [currentState] inside the content.
     */
    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val entryPoint = widgetEntryPoint(context)
        val localDataReady = entryPoint.localDataGate().ensureReady()
        val widgetId = runCatching {
            GlanceAppWidgetManager(context).getAppWidgetId(id)
        }.getOrDefault(0)
        provideContent {
            val encoded = currentState<Preferences>()[WidgetGlancePayloadKey]
            val model = if (!localDataReady || widgetId <= 0) {
                unconfiguredWidgetDisplayModel(widgetId)
            } else {
                WidgetGlancePayload.resolve(encoded, widgetId) {
                    persistedWidgetDisplay(context, widgetId)
                }
            }
            GlanceTheme(colors = leziGlanceColors()) {
                WidgetContent(context, model)
            }
        }
    }
}

internal val WidgetGlancePayloadKey = stringPreferencesKey(WidgetGlancePayload.KEY_NAME)

internal suspend fun publishWidgetGlanceModel(
    context: Context,
    glanceId: GlanceId,
    model: WidgetDisplayModel,
) {
    updateAppWidgetState(context, glanceId) { prefs ->
        prefs[WidgetGlancePayloadKey] = WidgetGlancePayload.encode(model)
    }
}

/** Last persisted snapshot. Does not open the database. */
internal fun persistedWidgetDisplay(context: Context, widgetId: Int): WidgetDisplayModel {
    val store = SharedPreferencesWidgetStateStore(context.applicationContext)
    return WidgetRefreshEngine(
        store = store,
        summarySource = WidgetSummarySource {
            error("Widget rendering only reads the persisted snapshot")
        },
    ).cachedDisplay(widgetId)
}

@Composable
private fun WidgetContent(
    context: Context,
    model: WidgetDisplayModel,
) {
    val widgetWidth = LocalSize.current.width
    val widgetHeight = LocalSize.current.height
    val visibleQuickActionCount = when {
        widgetWidth < 220.dp -> 2
        widgetWidth < 280.dp -> 3
        else -> MAX_WIDGET_QUICK_TYPES
    }
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.background)
            .padding(
                horizontal = WidgetChrome.padHorizontal,
                vertical = WidgetChrome.padVertical,
            )
            .clickable(
                actionStartActivity(
                    WidgetComposerContract.createOpenAppIntent(context, model.widgetId),
                ),
            ),
        verticalAlignment = Alignment.Top,
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            text = if (model.isStale) "${model.title} · 待刷新" else model.title,
            style = TextStyle(
                fontWeight = FontWeight.Bold,
                fontSize = WidgetChrome.titleFontSize,
            ),
            maxLines = 1,
        )
        Text(
            text = model.primarySummary,
            style = TextStyle(
                fontSize = WidgetChrome.bodyFontSize,
                color = GlanceTheme.colors.onSurfaceVariant,
            ),
            maxLines = 1,
        )
        Text(
            text = model.secondarySummary,
            style = TextStyle(
                fontSize = WidgetChrome.bodyFontSize,
                color = GlanceTheme.colors.onSurfaceVariant,
            ),
            maxLines = 1,
        )
        if (
            widgetHeight >= 128.dp &&
            model.babyId != null &&
            model.quickActions.isNotEmpty()
        ) {
            Spacer(GlanceModifier.height(WidgetChrome.stackGap))
            Row(verticalAlignment = Alignment.CenterVertically) {
                model.quickActions.take(visibleQuickActionCount).forEachIndexed { index, action ->
                    if (index > 0) Spacer(GlanceModifier.width(WidgetChrome.actionGap))
                    Text(
                        text = "+${action.label}",
                        modifier = GlanceModifier
                            .defaultWeight()
                            .background(GlanceTheme.colors.primaryContainer)
                            .padding(
                                horizontal = WidgetChrome.actionPadHorizontal,
                                vertical = WidgetChrome.actionPadVertical,
                            )
                            .clickable(
                                actionStartActivity(
                                    WidgetComposerContract.createIntent(
                                        context = context,
                                        babyId = model.babyId,
                                        type = action.type,
                                    ),
                                ),
                            ),
                        style = TextStyle(
                            fontSize = WidgetChrome.bodyFontSize,
                            fontWeight = FontWeight.Medium,
                            color = GlanceTheme.colors.onPrimaryContainer,
                        ),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

class CareWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CareWidget()

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        val entryPoint = widgetEntryPoint(context)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            runWidgetBroadcastWork(finish = pending::finish) {
                if (entryPoint.localDataGate().ensureReady()) {
                    val controller = entryPoint.controller().get()
                    appWidgetIds.forEach { widgetId -> controller.refreshWidget(widgetId) }
                }
            }
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        val entryPoint = widgetEntryPoint(context)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            runWidgetBroadcastWork(finish = pending::finish) {
                if (entryPoint.localDataGate().ensureReady()) {
                    val controller = entryPoint.controller().get()
                    appWidgetIds.forEach { widgetId -> controller.remove(widgetId) }
                }
            }
        }
    }
}

/**
 * Process boundary for the widget receiver's goAsync work, mirroring
 * runBroadcastWork: a refresh racing a destructive local clear rethrows
 * LocalDataClearInProgress/EpochInvalidated from the engine deliberately —
 * report those (and any other operational failure) instead of reaching the
 * process default handler and crashing. The next successful refresh redraws.
 */
private suspend fun runWidgetBroadcastWork(
    finish: () -> Unit,
    work: suspend () -> Unit,
) {
    try {
        work()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Exception) {
        failure.cancellationCauseOrNull()?.let { throw it }
        Log.e("CareWidgetReceiver", "widget broadcast work failed", failure)
    } finally {
        finish()
    }
}

private fun widgetEntryPoint(context: Context): WidgetReceiverEntryPoint =
    EntryPointAccessors.fromApplication(
        context.applicationContext,
        WidgetReceiverEntryPoint::class.java,
    )

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface WidgetReceiverEntryPoint {
    fun localDataGate(): DefaultLocalDataGate
    fun controller(): Lazy<CareWidgetRefreshController>
}
