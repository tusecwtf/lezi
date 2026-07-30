package com.lezi.babylog.feature.settings

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class CustomItemDeleteConfirmationTest {
    @Test
    fun deleteExecutionRethrowsCancellationAndMapsOrdinaryFailure() = runBlocking {
        val cancellation = CancellationException("dialog closed")
        try {
            executeCustomItemDelete(42L) { throw cancellation }
            fail("CancellationException must escape")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }

        assertEquals(
            "删除失败，请重试",
            executeCustomItemDelete(42L) {
                error("SQLite failure at /data/user/0/com.lezi.babylog")
            },
        )
    }

}
