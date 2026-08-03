package com.lezi.babylog.feature.settings.calendar

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.lezi.babylog.core.common.LocalDataGate
import com.lezi.babylog.core.datastore.SettingsStore
import com.lezi.babylog.core.model.CarePlan
import com.lezi.babylog.core.model.CarePlanStatus
import com.lezi.babylog.core.model.RecordTime
import com.lezi.babylog.core.model.displayLabel
import com.lezi.babylog.domain.CareLog
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.Lazy
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Pure eligibility for device-local care-plan reminders (testable without AlarmManager).
 * Permission denial still returns true for schedule attempt so save is not blocked;
 * the alarm may be dropped by the OS — UI surfaces degraded status separately.
 */
fun carePlanReminderEligible(
    plan: CarePlan,
    localRemindersEnabled: Boolean,
    nowMillis: Long,
): Boolean {
    if (!localRemindersEnabled) return false
    if (plan.deletedAt != null) return false
    if (plan.scheduledAt <= nowMillis) return false
    return plan.effectiveStatus(nowMillis) == CarePlanStatus.PENDING
}

/** User-facing copy when notification permission is denied after plan save. */
fun carePlanReminderPermissionDeniedStatus(): String =
    "护理计划已保存；通知权限未开启，本机提醒已降级"

internal fun carePlanNotificationPermissionWarning(
    remindersEnabled: Boolean,
    sdkInt: Int,
    permissionGranted: Boolean,
): String? = if (
    remindersEnabled &&
    sdkInt >= Build.VERSION_CODES.TIRAMISU &&
    !permissionGranted
) {
    "通知权限未开启，本机不会显示到点通知。"
} else {
    null
}

internal data class CarePlanReminderPlatformIdentity(
    val pendingIntentData: String,
    val notificationTag: String,
)

/** Extras are not part of PendingIntent identity, so retain the full Long id in URI/tag. */
internal fun carePlanReminderPlatformIdentity(carePlanId: Long) =
    CarePlanReminderPlatformIdentity(
        pendingIntentData = "lezi://local-reminder/care-plan/$carePlanId",
        notificationTag = "care-plan:$carePlanId",
    )

private const val CARE_PLAN_REQUEST_CODE_BASE = 0x4C5A_0000

/** Identity used before the full-id data URI shipped; retained only for upgrade cleanup. */
internal fun legacyCarePlanReminderRequestCode(carePlanId: Long): Int =
    CARE_PLAN_REQUEST_CODE_BASE + (carePlanId % 0x0000_FFFF).toInt()

/**
 * Device-local non-exact AlarmManager reminders for open care plans.
 * Request codes are namespaced away from calendar projection identities.
 */
@Singleton
class CarePlanReminderAlarm @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsStore,
) {
    suspend fun schedule(plan: CarePlan): Boolean {
        val enabled = settings.settings.first().carePlanLocalRemindersEnabled
        val now = RecordTime.currentTimeMillis()
        if (!carePlanReminderEligible(plan, enabled, now)) return false
        CarePlanReminderScheduler.ensureChannel(context)
        val alarm = context.getSystemService(AlarmManager::class.java)
        cancelLegacyIdentity(alarm, plan.id)
        // Non-exact: never request SCHEDULE_EXACT_ALARM / USE_EXACT_ALARM.
        alarm.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            plan.scheduledAt,
            pendingIntent(context, plan),
        )
        return true
    }

    fun cancel(carePlanId: Long) {
        val alarm = context.getSystemService(AlarmManager::class.java)
        alarm.cancel(pendingIntent(context, carePlanId, "", ""))
        cancelLegacyIdentity(alarm, carePlanId)
        val identity = carePlanReminderPlatformIdentity(carePlanId)
        NotificationManagerCompat.from(context).cancel(identity.notificationTag, 0)
    }

    private fun cancelLegacyIdentity(alarm: AlarmManager, carePlanId: Long) {
        legacyPendingIntent(context, carePlanId)?.let { pendingIntent ->
            alarm.cancel(pendingIntent)
            pendingIntent.cancel()
        }
        NotificationManagerCompat.from(context)
            .cancel(legacyCarePlanReminderRequestCode(carePlanId))
    }

    companion object {
        const val EXTRA_PLAN_ID = "care_plan_id"
        const val EXTRA_PLAN_UUID = "care_plan_client_uuid"
        const val EXTRA_TITLE = "care_plan_title"
        const val EXTRA_SCHEDULED_AT = "care_plan_scheduled_at"
        fun requestCode(carePlanId: Long): Int {
            var mixed = carePlanId
            mixed = (mixed xor (mixed ushr 33)) * -49064778989728563L
            mixed = (mixed xor (mixed ushr 33)) * -4265267296055464877L
            mixed = mixed xor (mixed ushr 33)
            return CARE_PLAN_REQUEST_CODE_BASE xor mixed.toInt()
        }

        private fun legacyPendingIntent(context: Context, carePlanId: Long): PendingIntent? =
            PendingIntent.getBroadcast(
                context,
                legacyCarePlanReminderRequestCode(carePlanId),
                Intent(context, CarePlanReminderReceiver::class.java),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            )

        fun pendingIntent(context: Context, plan: CarePlan): PendingIntent =
            pendingIntent(
                context,
                plan.id,
                plan.clientUuid,
                plan.displayLabel(),
                plan.scheduledAt,
            )

        fun pendingIntent(
            context: Context,
            carePlanId: Long,
            clientUuid: String,
            title: String,
            scheduledAt: Long = 0L,
        ): PendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode(carePlanId),
            Intent(context, CarePlanReminderReceiver::class.java)
                .setData(Uri.parse(carePlanReminderPlatformIdentity(carePlanId).pendingIntentData))
                .putExtra(EXTRA_PLAN_ID, carePlanId)
                .putExtra(EXTRA_PLAN_UUID, clientUuid)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_SCHEDULED_AT, scheduledAt),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

@Singleton
class CarePlanReminderScheduler @Inject constructor(
    private val careLog: CareLog,
) {
    suspend fun rescheduleAll() {
        careLog.rescheduleCarePlanReminders()
    }

    companion object {
        const val CHANNEL = "care_plan_reminders"

        fun ensureChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(
                    NotificationChannel(
                        CHANNEL,
                        "护理计划提醒",
                        NotificationManager.IMPORTANCE_DEFAULT,
                    ),
                )
        }
    }
}

@AndroidEntryPoint
class CarePlanReminderReceiver : BroadcastReceiver() {
    @Inject lateinit var localDataGate: LocalDataGate
    @Inject lateinit var careLog: Lazy<CareLog>

    override fun onReceive(context: Context, intent: Intent?) {
        val title = intent?.getStringExtra(CarePlanReminderAlarm.EXTRA_TITLE)
            .orEmpty()
            .ifBlank { "护理计划" }
        val planId = intent?.getLongExtra(CarePlanReminderAlarm.EXTRA_PLAN_ID, 0L) ?: 0L
        val planUuid = intent?.getStringExtra(CarePlanReminderAlarm.EXTRA_PLAN_UUID).orEmpty()
        if (intent?.hasExtra(CarePlanReminderAlarm.EXTRA_SCHEDULED_AT) != true) return
        val expectedScheduledAt = intent?.getLongExtra(
            CarePlanReminderAlarm.EXTRA_SCHEDULED_AT,
            0L,
        ) ?: 0L
        if (planId <= 0L || planUuid.isBlank()) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            runBroadcastWork(
                finish = pending::finish,
                reportFailure = { failure ->
                    Log.e(TAG, "Care-plan reminder delivery failed", failure)
                },
            ) {
                if (!localDataGate.ensureReady()) return@runBroadcastWork
                if (
                    careLog.get().shouldDeliverCarePlanReminder(
                        carePlanId = planId,
                        clientUuid = planUuid,
                        expectedScheduledAt = expectedScheduledAt,
                    )
                ) {
                    notifyDuePlan(context, title, planId, planUuid)
                }
            }
        }
    }

    private fun notifyDuePlan(
        context: Context,
        title: String,
        planId: Long,
        planUuid: String,
    ) {
        CarePlanReminderScheduler.ensureChannel(context)
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                data = Uri.parse(
                    "${carePlanReminderPlatformIdentity(planId).pendingIntentData}/fulfill",
                )
                putExtra(EXTRA_FULFILL_PLAN_ID, planId)
                putExtra(EXTRA_FULFILL_PLAN_UUID, planUuid)
            }
        val contentIntent = PendingIntent.getActivity(
            context,
            CarePlanReminderAlarm.requestCode(planId),
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CarePlanReminderScheduler.CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("乐记 · $title")
            .setContentText("护理计划到点，点此完成")
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            notifyGranted(context, planId, notification)
        }
    }

    @SuppressLint("MissingPermission")
    private fun notifyGranted(context: Context, planId: Long, notification: Notification) {
        runCatching {
            val identity = carePlanReminderPlatformIdentity(planId)
            NotificationManagerCompat.from(context)
                .notify(identity.notificationTag, 0, notification)
        }
    }

    companion object {
        private const val TAG = "CarePlanReminder"
        const val EXTRA_FULFILL_PLAN_ID = "lezi_fulfill_care_plan_id"
        const val EXTRA_FULFILL_PLAN_UUID = "lezi_fulfill_care_plan_uuid"
    }
}
