package com.lezi.babylog.feature.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle

class CareWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            GlanceTheme {
                WidgetContent(privateWidgetCopy())
            }
        }
    }
}

internal data class PrivateWidgetCopy(
    val title: String,
    val message: String,
)

internal fun privateWidgetCopy(): PrivateWidgetCopy = PrivateWidgetCopy(
    title = "乐记",
    message = "为保护隐私，请打开应用查看记录",
)

@Composable
private fun WidgetContent(copy: PrivateWidgetCopy) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.background)
            .padding(12.dp),
        verticalAlignment = Alignment.Top,
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            copy.title,
            style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 16.sp),
        )
        Spacer(GlanceModifier.height(8.dp))
        Text(
            copy.message,
            style = TextStyle(
                fontSize = 12.sp,
                color = GlanceTheme.colors.onSurfaceVariant,
            ),
        )
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
