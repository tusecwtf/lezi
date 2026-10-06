package com.lezi.babylog.feature.timer

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat

/**
 * Pure, JVM-testable model of the ongoing nursing-timer notification (0.5.4 ticket 12).
 *
 * The service maps its current snapshot to a [NursingTimerNotificationSpec] and the thin
 * Android glue below applies it to a `NotificationCompat.Builder`. Keeping the decision
 * surface (chronometer flag, status word, action set, icon) in pure code lets the ticket's
 * JVM tests lock the lock-screen behavior without an emulator.
 */

/** Status words pinned by the ticket: 「进行中 · 左/右」 while counting, 「已暂停」 when frozen. */
internal fun nursingTimerStatusWord(leftRunning: Boolean, rightRunning: Boolean): String =
    when {
        leftRunning -> "进行中 · 左"
        rightRunning -> "进行中 · 右"
        else -> "已暂停"
    }

internal const val NURSING_TIMER_NOTIF_LABEL_PAUSE = "暂停"
internal const val NURSING_TIMER_NOTIF_LABEL_RESUME = "继续"
internal const val NURSING_TIMER_NOTIF_LABEL_FINISH = "结束"

internal enum class NursingTimerNotifActionKind {
    /** 「暂停/继续」 — delivered straight to [NursingTimerService] as a service intent. */
    TOGGLE_PAUSE_RESUME,

    /** 「结束」 — opens the app's completion form; the timer is never silently dropped. */
    FINISH,
}

internal data class NursingTimerNotificationAction(
    val kind: NursingTimerNotifActionKind,
    val label: String,
)

internal data class NursingTimerNotificationSpec(
    val title: String,
    val text: String,
    val statusWord: String,
    /**
     * True only while a side is counting: the system ticks the seconds via the
     * chronometer so the service never rebuilds the notification every second.
     */
    val usesChronometer: Boolean,
    /** Wall-clock chronometer base for [androidx.core.app.NotificationCompat.Builder.setWhen]. */
    val chronometerBaseWallMs: Long?,
    /** Running notifications show the chronometer in the `when` slot; paused ones show nothing. */
    val showWhen: Boolean,
    /** Exactly two actions: 「暂停/继续」 then 「结束」. */
    val actions: List<NursingTimerNotificationAction>,
    val smallIconResId: Int,
)

internal fun nursingTimerNotificationSpec(
    leftMs: Long,
    rightMs: Long,
    leftRunning: Boolean,
    rightRunning: Boolean,
    nowWallMs: Long,
    smallIconResId: Int,
): NursingTimerNotificationSpec {
    val running = leftRunning || rightRunning
    val statusWord = nursingTimerStatusWord(leftRunning, rightRunning)
    return NursingTimerNotificationSpec(
        title = "乐记 · 喂奶计时（$statusWord）",
        text = "左 ${formatTimerMs(leftMs)} · 右 ${formatTimerMs(rightMs)}",
        statusWord = statusWord,
        usesChronometer = running,
        chronometerBaseWallMs = if (running) {
            nowWallMs - (leftMs + rightMs).coerceAtLeast(0L)
        } else {
            null
        },
        showWhen = running,
        actions = listOf(
            NursingTimerNotificationAction(
                kind = NursingTimerNotifActionKind.TOGGLE_PAUSE_RESUME,
                label = if (running) NURSING_TIMER_NOTIF_LABEL_PAUSE else NURSING_TIMER_NOTIF_LABEL_RESUME,
            ),
            NursingTimerNotificationAction(
                kind = NursingTimerNotifActionKind.FINISH,
                label = NURSING_TIMER_NOTIF_LABEL_FINISH,
            ),
        ),
        smallIconResId = smallIconResId,
    )
}

/** Thin Android glue: applies [spec] to a builder on the existing IMPORTANCE_LOW channel. */
internal fun buildNursingTimerNotification(
    context: Context,
    spec: NursingTimerNotificationSpec,
    contentIntent: PendingIntent?,
    toggleIntent: PendingIntent?,
    finishIntent: PendingIntent?,
): Notification {
    val builder = NotificationCompat.Builder(context, NursingTimerService.CHANNEL_ID)
        .setContentTitle(spec.title)
        .setContentText(spec.text)
        .setSmallIcon(spec.smallIconResId)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setShowWhen(spec.showWhen)
    if (spec.usesChronometer && spec.chronometerBaseWallMs != null) {
        builder.setUsesChronometer(true)
        builder.setWhen(spec.chronometerBaseWallMs)
    }
    contentIntent?.let(builder::setContentIntent)
    spec.actions.forEach { action ->
        val intent = when (action.kind) {
            NursingTimerNotifActionKind.TOGGLE_PAUSE_RESUME -> toggleIntent
            NursingTimerNotifActionKind.FINISH -> finishIntent
        } ?: return@forEach
        builder.addAction(spec.smallIconResId, action.label, intent)
    }
    return builder.build()
}
