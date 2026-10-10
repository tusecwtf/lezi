package com.lezi.babylog.sync.session

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ProcessForegroundStateTest {
    @Test
    fun backgroundCancelsAllImmediateFinalizersAndAllowsANewResidency() = runTest {
        val foreground = ProcessForegroundState().apply { setForeground(true) }
        var finalized = 0
        val first = launch(Dispatchers.Unconfined) {
            foreground.whileForeground {
                try { awaitCancellation() } finally { finalized++ }
            }
        }
        val second = launch(Dispatchers.Unconfined) {
            foreground.whileForeground {
                try { awaitCancellation() } finally { finalized++ }
            }
        }
        foreground.setForeground(false)
        assertThat(first.isCancelled).isTrue()
        assertThat(second.isCancelled).isTrue()
        assertThat(finalized).isEqualTo(2)
        foreground.setForeground(true)
        assertThat(foreground.whileForeground { "fresh" }).isEqualTo("fresh")
    }
}
