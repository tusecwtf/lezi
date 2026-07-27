package com.lezi.babylog.feature.settings

import android.annotation.SuppressLint
import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.lezi.babylog.core.datastore.SettingsStore
import com.lezi.babylog.domain.CalendarReminderMutationGuard
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
    private val mutationGuard: CalendarReminderMutationGuard,
    @ApplicationContext private val context: Context,
) : FeedReminderPort {
    override suspend fun scheduleAfterFeed(atMillis: Long?) = mutationGuard.withLock {
        val intervalMin = settings.settings.first().nursingIntervalMin
        val whenMs = atMillis ?: (System.currentTimeMillis() + intervalMin * 60_000L)
        val epoch = settings.setNextFeedAt(whenMs)
        ensureChannel(context)
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = pending(context, epoch)
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMs, pi)
    }

    suspend fun cancel() = mutationGuard.withLock {
        settings.clearNextFeedAt()
        cancelAlarmLocked()
    }

    /**
     * Cancels the captured local-clear alarm without touching a newer settings epoch.
     * The local-clear coordinator already owns [mutationGuard]; reacquiring it after
     * switching to NonCancellable would deadlock on the same lease.
     */
    internal fun cancelCapturedAlarmUnderGuard() = cancelAlarmLocked()

    private fun cancelAlarmLocked() {
        val am = context.getSystemService(AlarmManager::class.java)
        am.cancel(pending(context))
    }

    suspend fun rescheduleFromStore() = mutationGuard.withLock {
        val stored = settings.settings.first()
        val at = stored.nextFeedAt ?: return@withLock
        if (at <= System.currentTimeMillis()) return@withLock
        val epoch = stored.nextFeedEpoch.ifBlank { settings.setNextFeedAt(at) }
        ensureChannel(context)
        val am = context.getSystemService(AlarmManager::class.java)
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(context, epoch))
    }

    /** Atomically rejects a stale delivery before it can notify or clear a newer epoch. */
    internal suspend fun consumeDeliveredAlarm(expectedEpoch: String?): Boolean {
        if (expectedEpoch.isNullOrBlank()) return false
        return mutationGuard.withLock { settings.clearNextFeedAtIfEpoch(expectedEpoch) }
    }

    private fun pending(context: Context, epoch: String? = null): PendingIntent {
        val intent = Intent(context, NextFeedReceiver::class.java).apply {
            epoch?.let { putExtra(EXTRA_EPOCH, it) }
        }
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
        internal const val EXTRA_EPOCH = "com.lezi.babylog.extra.NEXT_FEED_EPOCH"

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
    @Inject lateinit var scheduler: NextFeedScheduler

    override fun onReceive(context: Context, intent: Intent?) {
        val expectedEpoch = intent?.getStringExtra(NextFeedScheduler.EXTRA_EPOCH)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            runBroadcastWork(
                finish = pending::finish,
                reportFailure = { failure ->
                    Log.e(TAG, "Next-feed reminder delivery failed", failure)
                },
            ) {
                if (scheduler.consumeDeliveredAlarm(expectedEpoch)) {
                    notifyDueFeed(context)
                }
            }
        }
    }

    private fun notifyDueFeed(context: Context) {
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
        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            notifyWithGrantedPermission(context, notif)
        }
    }

    @SuppressLint("MissingPermission")
    private fun notifyWithGrantedPermission(context: Context, notification: android.app.Notification) {
        runCatching {
            NotificationManagerCompat.from(context)
                .notify(NextFeedScheduler.NOTIF_ID, notification)
        }
    }

    private companion object {
        const val TAG = "NextFeedReminder"
    }
}

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {
    @Inject lateinit var scheduler: NextFeedScheduler
    @Inject lateinit var carePlanScheduler: CarePlanReminderScheduler

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            runBroadcastWork(
                finish = pending::finish,
                reportFailure = { failure ->
                    Log.e(TAG, "Reminder reschedule after boot failed", failure)
                },
            ) {
                scheduler.rescheduleFromStore()
                carePlanScheduler.rescheduleAll()
            }
        }
    }

    private companion object {
        const val TAG = "ReminderBoot"
    }
}
