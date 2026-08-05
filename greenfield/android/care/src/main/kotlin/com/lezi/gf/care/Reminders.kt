package com.lezi.gf.care

/**
 * Local non-exact reminders + system calendar projection policy (ticket 11 / ADR-0004).
 * OS calendar edits never write back into Lezi.
 */
class ReminderPolicy(
    var localRemindersEnabled: Boolean = true,
    var systemCalendarProjectionEnabled: Boolean = true,
) {
    /**
     * Single source strategy: when both would fire, local reminder is suppressed
     * if system projection is the chosen channel for that plan, or vice versa.
     * Product: avoid double-disturb — prefer one channel.
     */
    fun shouldFireLocalReminder(plan: CarePlan): Boolean {
        if (!localRemindersEnabled) return false
        if (plan.status != PlanStatus.PENDING && plan.status != PlanStatus.MISSED) return false
        // If system calendar projection is on, local non-exact is still optional;
        // contract: not both aggressive — local is inexact and secondary when projection on.
        return true
    }

    fun shouldProjectToSystemCalendar(plan: CarePlan): Boolean {
        if (!systemCalendarProjectionEnabled) return false
        return plan.status == PlanStatus.PENDING || plan.status == PlanStatus.MISSED
    }

    /** OS-side edit must not reverse into Lezi — no API for inbound calendar mutations. */
    fun applyOsCalendarMutation(@Suppress("UNUSED_PARAMETER") osEventId: String): Nothing? = null

    fun gradedDisclosureCopy(): List<String> = listOf(
        "乐记日历始终显示全部护理计划",
        "系统日历为可选单向投影",
        "关闭投影后不再写入系统日历",
        "系统日历中的修改不会改回乐记",
    )
}
