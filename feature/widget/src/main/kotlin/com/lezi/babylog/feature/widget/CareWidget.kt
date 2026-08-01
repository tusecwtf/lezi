package com.lezi.babylog.feature.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.glance.LocalSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.lezi.babylog.core.common.LocalDataGate
import dagger.Lazy
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class CareWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val entryPoint = widgetEntryPoint(context)
        val localDataReady = entryPoint.localDataGate().ensureReady()
        val widgetId = runCatching {
            GlanceAppWidgetManager(context).getAppWidgetId(id)
        }.getOrDefault(0)
        val model = if (localDataReady && widgetId > 0) {
            val store = SharedPreferencesWidgetStateStore(context.applicationContext)
            WidgetRefreshEngine(
                store = store,
                summarySource = WidgetSummarySource {
                    error("Widget rendering only reads the persisted snapshot")
                },
            ).cachedDisplay(widgetId)
        } else {
            unconfiguredWidgetDisplayModel(widgetId)
        }
        provideContent {
            GlanceTheme {
                WidgetContent(context, model)
            }
        }
    }
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
            .padding(horizontal = 10.dp, vertical = 8.dp)
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
            style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 14.sp),
            maxLines = 1,
        )
        Text(
            text = model.primarySummary,
            style = TextStyle(
                fontSize = 10.sp,
                color = GlanceTheme.colors.onSurfaceVariant,
            ),
            maxLines = 1,
        )
        Text(
            text = model.secondarySummary,
            style = TextStyle(
                fontSize = 10.sp,
                color = GlanceTheme.colors.onSurfaceVariant,
            ),
            maxLines = 1,
        )
        if (
            widgetHeight >= 128.dp &&
            model.babyId != null &&
            model.quickActions.isNotEmpty()
        ) {
            Spacer(GlanceModifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                model.quickActions.take(visibleQuickActionCount).forEachIndexed { index, action ->
                    if (index > 0) Spacer(GlanceModifier.width(4.dp))
                    Text(
                        text = "+${action.label}",
                        modifier = GlanceModifier
                            .width(48.dp)
                            .background(GlanceTheme.colors.primaryContainer)
                            .padding(horizontal = 7.dp, vertical = 18.dp)
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
                            fontSize = 10.sp,
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
            try {
                if (entryPoint.localDataGate().ensureReady()) {
                    val controller = entryPoint.controller().get()
                    appWidgetIds.forEach { widgetId -> controller.refreshWidget(widgetId) }
                }
            } finally {
                pending.finish()
            }
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        val entryPoint = widgetEntryPoint(context)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (entryPoint.localDataGate().ensureReady()) {
                    val controller = entryPoint.controller().get()
                    appWidgetIds.forEach { widgetId -> controller.remove(widgetId) }
                }
            } finally {
                pending.finish()
            }
        }
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
    fun localDataGate(): LocalDataGate
    fun controller(): Lazy<CareWidgetRefreshController>
}
