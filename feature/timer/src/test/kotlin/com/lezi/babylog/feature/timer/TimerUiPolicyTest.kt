package com.lezi.babylog.feature.timer

import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.shouldOfferNextFeedPlanForFact
import org.junit.Assert.assertEquals
import org.junit.Test

class TimerUiPolicyTest {
    @Test
    fun onlyOrdinaryTimerFactOffersAnotherNextFeedPlan() {
        assertEquals(
            true,
            shouldOfferNextFeedPlanForFact(RecordType.NURSING, true, sourceCarePlanId = null),
        )
        assertEquals(
            false,
            shouldOfferNextFeedPlanForFact(RecordType.NURSING, true, sourceCarePlanId = 42L),
        )
    }
}
