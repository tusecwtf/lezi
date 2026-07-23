package com.lezi.babylog.feature.settings

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.lezi.babylog.core.datastore.SettingsStore
import com.lezi.babylog.domain.FeedReminderPort
import dagger.Binds
import dagger.Module
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Singleton
class NextFeedScheduler @Inject constructor(
    private val settings: SettingsStore,
    @ApplicationContext private val context: Context,
) : FeedReminderPort {
    override suspend fun scheduleAfterFeed(atMillis: Long?) {
        val intervalMin = settings.settings.first().nursingIntervalMin
        val whenMs = atMillis ?: (System.currentTimeMillis() + intervalMin * 60_000L)
        settings.setNextFeedAt(whenMs)
        ensureChannel(context)
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = pending(context)
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMs, pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMs, pi)
            }
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMs, pi)
        }
    }

    suspend fun cancel() {
        settings.clearNextFeedAt()
        val am = context.getSystemService(AlarmManager::class.java)
        am.cancel(pending(context))
    }

    suspend fun rescheduleFromStore() {
        val at = settings.settings.first().nextFeedAt ?: return
        if (at <= System.currentTimeMillis()) return
        ensureChannel(context)
        val am = context.getSystemService(AlarmManager::class.java)
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(context))
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(context))
        }
    }

    private fun pending(context: Context): PendingIntent {
        val intent = Intent(context, NextFeedReceiver::class.java)
        return PendingIntent.getBroadcast(
            context,
            REQ,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val CHANNEL = "next_feed"
        const val NOTIF_ID = 77
        const val REQ = 77

        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "下次喂奶", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class FeedReminderModule {
    @Binds
    @Singleton
    abstract fun bindFeedReminderPort(scheduler: NextFeedScheduler): FeedReminderPort
}

@AndroidEntryPoint
class NextFeedReceiver : BroadcastReceiver() {
    @Inject lateinit var settings: SettingsStore

    override fun onReceive(context: Context, intent: Intent?) {
        NextFeedScheduler.ensureChannel(context)
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val pi = PendingIntent.getActivity(
            context,
            0,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notif = NotificationCompat.Builder(context, NextFeedScheduler.CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("乐记 · 下次喂奶")
            .setContentText("到点啦，记得记录喂养。")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(NextFeedScheduler.NOTIF_ID, notif)
        }
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                settings.clearNextFeedAt()
            } finally {
                pending.finish()
            }
        }
    }
}

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {
    @Inject lateinit var scheduler: NextFeedScheduler

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                scheduler.rescheduleFromStore()
            } finally {
                pending.finish()
            }
        }
    }
}
