package com.lezi.gf.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.lezi.gf.app.LeziGfApp
import com.lezi.gf.app.MainActivity
import com.lezi.gf.app.R
import com.lezi.gf.care.CareAggregation
import com.lezi.gf.kernel.SystemClock

/**
 * Home-screen only widget (ticket 16).
 * Shortcuts open prefilled Composer via MainActivity — never write on tap.
 */
class LeziGfWidgetReceiver : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val app = context.applicationContext as? LeziGfApp
        val baby = app?.container?.family?.currentBaby()
        val summaryText = if (app != null && baby != null) {
            val day = CareAggregation.dayStartMs(SystemClock.nowEpochMs())
            val s = app.container.care.daySummary(baby.clientUuid, day)
            "${baby.nickname} 奶${s.milkMl}ml 尿${s.peeCount}"
        } else {
            "乐记摘要"
        }
        for (id in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_placeholder)
            views.setTextViewText(R.id.widget_title, baby?.nickname ?: "乐记")
            views.setTextViewText(R.id.widget_summary, summaryText)

            // Prefill Composer for formula — open only, no write
            val openComposer = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                action = MainActivity.ACTION_OPEN_COMPOSER
                putExtra(MainActivity.EXTRA_COMPOSER_TYPE, "formula")
                if (baby != null) {
                    putExtra(MainActivity.EXTRA_BABY_UUID, baby.clientUuid)
                }
            }
            val pi = PendingIntent.getActivity(
                context,
                id,
                openComposer,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_summary, pi)
            views.setOnClickPendingIntent(R.id.widget_title, pi)
            appWidgetManager.updateAppWidget(id, views)
        }
    }
}
