package com.lezi.babylog.feature.timer

import org.junit.Assert.assertEquals
import org.junit.Test

class TimerUiPolicyTest {
    @Test
    fun onlyOrdinaryTimerFactOffersAnotherNextFeedPlan() {
        assertEquals(true, timerShouldOfferNextFeedPlan(carePlanId = null))
        assertEquals(false, timerShouldOfferNextFeedPlan(carePlanId = 42L))
    }

    @Test
    fun nextFeedSuccessCopyExplainsNotificationDegradation() {
        assertEquals("护理计划已加入乐记日程", nextFeedPlanSuccessMessage(true))
        assertEquals(
            "护理计划已加入乐记日程；通知权限未开启，本机提醒已降级",
            nextFeedPlanSuccessMessage(false),
        )
    }
}
