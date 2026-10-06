package com.lezi.babylog.core.database

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.causal.DISMISSED_ENTITY_KEY_PREFIX
import com.lezi.babylog.core.database.causal.DISMISSED_ENTITY_KEY_RANGE_END
import com.lezi.babylog.core.database.causal.FROZEN_MEDIA_SPOOL_KEY_PREFIX
import com.lezi.babylog.core.database.causal.FROZEN_MEDIA_SPOOL_KEY_RANGE_END
import com.lezi.babylog.core.database.causal.PULL_DIAGNOSTIC_KEY_PREFIX
import com.lezi.babylog.core.database.causal.PULL_DIAGNOSTIC_KEY_RANGE_END
import com.lezi.babylog.core.database.causal.TERMINAL_RECEIPT_KEY_PREFIX
import com.lezi.babylog.core.database.causal.TERMINAL_RECEIPT_KEY_RANGE_END
import org.junit.Test

class TimelineWindowInvalidationSqlTest {
    @Test
    fun narrowRecordProbeOmitsMediaWhileTimelineProbeKeepsIt() {
        assertThat(RECORD_WAKE_INVALIDATION_SQL).doesNotContain("media_assets")
        assertThat(RECORD_WAKE_INVALIDATION_SQL).doesNotContain("care_plans")
        assertThat(RECORD_WAKE_INVALIDATION_SQL).contains("FROM records")
        assertThat(RECORD_WAKE_INVALIDATION_SQL).contains("FROM wake_observations")
        assertThat(RECORD_WAKE_INVALIDATION_SQL).contains("FROM fulfillment_candidates")
        assertThat(RECORD_WAKE_INVALIDATION_SQL).contains("FROM conflict_summaries")
        assertThat(TIMELINE_WINDOW_INVALIDATION_SQL).contains("media_assets")
        assertThat(TIMELINE_WINDOW_INVALIDATION_SQL).contains("care_plans")
        assertThat(TIMELINE_WINDOW_INVALIDATION_SQL).contains("wake_observations")
    }

    @Test
    fun journalPrefixRangeIsTheNextCodePointAfterColon() {
        assertThat(prefixRangeEnd(TERMINAL_RECEIPT_KEY_PREFIX)).isEqualTo(TERMINAL_RECEIPT_KEY_RANGE_END)
        assertThat(prefixRangeEnd(PULL_DIAGNOSTIC_KEY_PREFIX)).isEqualTo(PULL_DIAGNOSTIC_KEY_RANGE_END)
        assertThat(prefixRangeEnd(FROZEN_MEDIA_SPOOL_KEY_PREFIX)).isEqualTo(FROZEN_MEDIA_SPOOL_KEY_RANGE_END)
        assertThat(prefixRangeEnd(DISMISSED_ENTITY_KEY_PREFIX)).isEqualTo(DISMISSED_ENTITY_KEY_RANGE_END)
        val receipt = "terminal-receipt:record:abc"
        assertThat(receipt >= TERMINAL_RECEIPT_KEY_PREFIX).isTrue()
        assertThat(receipt < TERMINAL_RECEIPT_KEY_RANGE_END).isTrue()
        // '!' sorts before ':' so this bound would match nothing.
        assertThat(receipt < "terminal-receipt!").isFalse()
        assertThat("terminal-receipts" < TERMINAL_RECEIPT_KEY_RANGE_END).isFalse()
    }

    private fun prefixRangeEnd(prefix: String): String =
        prefix.dropLast(1) + (prefix.last() + 1)
}
