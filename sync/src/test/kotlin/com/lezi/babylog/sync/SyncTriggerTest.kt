package com.lezi.babylog.sync

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SyncTriggerTest {
    @Test
    fun foregroundAndRefreshPushThenPullWhileLocalWriteOnlyPushes() {
        assertThat(SyncPlan.forTrigger(SyncTrigger.Foreground))
            .isEqualTo(SyncPlan(push = true, pull = true))
        assertThat(SyncPlan.forTrigger(SyncTrigger.PullToRefresh))
            .isEqualTo(SyncPlan(push = true, pull = true))
        assertThat(SyncPlan.forTrigger(SyncTrigger.LocalWrite))
            .isEqualTo(SyncPlan(push = true, pull = false))
    }
}
