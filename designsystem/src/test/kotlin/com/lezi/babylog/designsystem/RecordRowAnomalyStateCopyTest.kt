package com.lezi.babylog.designsystem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Tier B copy contract for the RecordRow anomaly「!」spoken state (0.5.4 ticket 11 / T4). */
class RecordRowAnomalyStateCopyTest {
    @Test
    fun anomalyRowsSpeakTheAnomalyState() {
        assertEquals("有异常", recordRowAnomalyStateDescription(anomaly = true))
        assertEquals("有异常", RECORD_ROW_ANOMALY_STATE_DESCRIPTION)
    }

    @Test
    fun normalRowsCarryNoStateDescription() {
        assertNull(recordRowAnomalyStateDescription(anomaly = false))
    }
}
