package com.lezi.babylog.feature.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
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

class CareWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val widgetId = runCatching {
            GlanceAppWidgetManager(context).getAppWidgetId(id)
        }.getOrDefault(0)
        val model = if (widgetId > 0) {
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
        if (model.babyId != null && model.quickActions.isNotEmpty()) {
            Spacer(GlanceModifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                model.quickActions.forEachIndexed { index, action ->
                    if (index > 0) Spacer(GlanceModifier.width(4.dp))
                    Text(
                        text = "+${action.label}",
                        modifier = GlanceModifier
                            .background(GlanceTheme.colors.primaryContainer)
                            .padding(horizontal = 7.dp, vertical = 5.dp)
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

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        val store = SharedPreferencesWidgetStateStore(context.applicationContext)
        appWidgetIds.forEach(store::remove)
    }
}
