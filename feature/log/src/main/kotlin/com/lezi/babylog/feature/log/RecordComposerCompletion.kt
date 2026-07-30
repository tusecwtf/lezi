package com.lezi.babylog.feature.log

/**
 * Enforces the post-write ordering: prepare any reminder UI, then consume the restorable root
 * request before reporting final completion. This prevents process recreation from replaying a
 * successfully persisted New request.
 */
internal fun dispatchRecordSaveCompletion(
    message: String,
    suggestedNextFeedAt: Long?,
    onOfferReminder: (String, Long) -> Unit,
    onPersisted: () -> Unit,
    onFinished: (String) -> Unit,
) {
    if (suggestedNextFeedAt != null) onOfferReminder(message, suggestedNextFeedAt)
    onPersisted()
    if (suggestedNextFeedAt == null) onFinished(message)
}

/** Care-plan create/edit/convert success snackbars (not feed-fact or fulfill). */
internal fun isCarePlanSaveMessage(message: String): Boolean =
    message.startsWith("已安排") ||
        message.startsWith("已转为护理计划") ||
        message == "已保存护理计划"

/**
 * Permission denial never blocks plan persistence; surface a clear local-reminder
 * degradation so users know why they may not get a notification.
 * Keeps parity with [com.lezi.babylog.feature.settings.carePlanReminderPermissionDeniedStatus].
 */
internal fun carePlanSaveMessageWithPermission(
    baseMessage: String,
    notificationPermissionGranted: Boolean,
    isCarePlanWrite: Boolean,
): String {
    if (!isCarePlanWrite || notificationPermissionGranted) return baseMessage
    return if (baseMessage == "已保存护理计划") {
        "护理计划已保存；通知权限未开启，本机提醒已降级"
    } else {
        "$baseMessage；通知权限未开启，本机提醒已降级"
    }
}
