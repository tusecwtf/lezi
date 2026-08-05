package com.lezi.gf.app.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Fires for inexact plan reminders — does not auto-fulfill (confirm still required). */
class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != LocalReminderScheduler.ACTION_PLAN_REMINDER) return
        val uuid = intent.getStringExtra(LocalReminderScheduler.EXTRA_PLAN_UUID) ?: return
        Log.i("LeziReminder", "plan reminder: $uuid")
        // Notification would surface here; fulfillment remains user-confirmed in app.
    }
}
