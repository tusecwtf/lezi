package com.lezi.babylog.feature.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.lezi.babylog.domain.CareLog
import com.lezi.babylog.domain.WidgetSummaryDto
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

class CareWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val summary = loadSummary(context)
        provideContent {
            GlanceTheme {
                WidgetContent(summary)
            }
        }
    }

    private suspend fun loadSummary(context: Context): WidgetSummaryDto {
        val entry = EntryPointAccessors.fromApplication(context, CareWidgetEntry::class.java)
        val careLog = entry.careLog()
        val baby = careLog.getCurrentBaby() ?: return WidgetSummaryDto("乐记", 0, 0, 0, 0, null)
        return careLog.recentCareSummary(baby.id)
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface CareWidgetEntry {
    fun careLog(): CareLog
}

@Composable
private fun WidgetContent(summary: WidgetSummaryDto) {
    // Launch main activity via package default launcher intent handled in clickable below.
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.background)
            .padding(12.dp),
        verticalAlignment = Alignment.Top,
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            summary.babyName,
            style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 16.sp),
        )
        Text("今日", style = TextStyle(fontSize = 12.sp, color = GlanceTheme.colors.onSurfaceVariant))
        Spacer(GlanceModifier.height(8.dp))
        Row(GlanceModifier.fillMaxWidth()) {
            Metric("奶", "${summary.feedMl}ml")
            Metric("睡", "${summary.sleepMin}m")
            Metric("尿", "${summary.pee}")
            Metric("便", "${summary.poop}")
        }
        summary.lastLabel?.let {
            Spacer(GlanceModifier.height(6.dp))
            Text(it, style = TextStyle(fontSize = 11.sp, color = GlanceTheme.colors.onSurfaceVariant))
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column(modifier = GlanceModifier.padding(end = 10.dp)) {
        Text(value, style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 14.sp))
        Text(label, style = TextStyle(fontSize = 11.sp))
    }
}

class CareWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CareWidget()
}

object CareWidgetUpdater {
    suspend fun requestUpdate(context: Context) {
        val manager = androidx.glance.appwidget.GlanceAppWidgetManager(context)
        val widget = CareWidget()
        manager.getGlanceIds(CareWidget::class.java).forEach { id ->
            widget.update(context, id)
        }
    }
}
