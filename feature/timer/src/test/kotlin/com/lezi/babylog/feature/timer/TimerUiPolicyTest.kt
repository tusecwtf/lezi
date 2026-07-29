package com.lezi.babylog.feature.timer

import org.junit.Assert.assertEquals
import org.junit.Test

class TimerUiPolicyTest {
    @Test
    fun onlyOrdinaryTimerFactOffersAnotherNextFeedPlan() {
        assertEquals(true, timerShouldOfferNextFeedPlan(carePlanId = null))
        assertEquals(false, timerShouldOfferNextFeedPlan(carePlanId = 42L))
    }
}
