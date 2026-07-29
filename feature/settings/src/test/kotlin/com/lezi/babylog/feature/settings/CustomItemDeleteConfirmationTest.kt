package com.lezi.babylog.feature.settings

import com.lezi.babylog.core.ui.CustomItemManageRow
import com.lezi.babylog.domain.CustomRecordItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CustomItemDeleteConfirmationTest {
    @Test
    fun requestAndCancelDoNotEmitDeleteCommand() {
        val requested = reduceCustomItemDelete(
            CustomItemDeleteState(),
            CustomItemDeleteAction.Request(item()),
        )
        val cancelled = reduceCustomItemDelete(
            requested.state,
            CustomItemDeleteAction.Cancel,
        )

        assertEquals(itemRow(), requested.state.target)
        assertNull(requested.command)
        assertEquals(CustomItemDeleteState(), cancelled.state)
        assertNull(cancelled.command)
    }

    @Test
    fun confirmEmitsExactlyOneCommandAndCannotDismissWhileBusy() {
        val requested = requestedState()
        val first = reduceCustomItemDelete(requested, CustomItemDeleteAction.Confirm)
        val duplicate = reduceCustomItemDelete(first.state, CustomItemDeleteAction.Confirm)
        val cancelled = reduceCustomItemDelete(first.state, CustomItemDeleteAction.Cancel)

        assertEquals(42L, first.command?.itemId)
        assertTrue(first.state.deleting)
        assertNull(duplicate.command)
        assertEquals(first.state, duplicate.state)
        assertNull(cancelled.command)
        assertEquals(first.state, cancelled.state)
    }

    @Test
    fun failureStaysVisibleAndRetryEmitsASecondCommand() {
        val deleting = reduceCustomItemDelete(
            requestedState(),
            CustomItemDeleteAction.Confirm,
        ).state
        val failed = reduceCustomItemDelete(
            deleting,
            CustomItemDeleteAction.Finished("删除失败，请重试"),
        )
        val retried = reduceCustomItemDelete(failed.state, CustomItemDeleteAction.Confirm)

        assertEquals(itemRow(), failed.state.target)
        assertEquals("删除失败，请重试", failed.state.error)
        assertFalse(failed.state.deleting)
        assertEquals(42L, retried.command?.itemId)
        assertTrue(retried.state.deleting)
        assertNull(retried.state.error)
    }

    @Test
    fun successClosesConfirmation() {
        val deleting = reduceCustomItemDelete(
            requestedState(),
            CustomItemDeleteAction.Confirm,
        ).state

        val finished = reduceCustomItemDelete(
            deleting,
            CustomItemDeleteAction.Finished(null),
        )

        assertEquals(CustomItemDeleteState(), finished.state)
        assertNull(finished.command)
    }

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

    @Test
    fun layoutAndSettingsEntriesShareCoreReduce() {
        // Shipped path: both feature wrappers call core.ui.reduceCustomItemDelete.
        val fromSettings = reduceCustomItemDelete(
            CustomItemDeleteState(),
            CustomItemDeleteAction.Request(item()),
        )
        val fromCore = com.lezi.babylog.core.ui.reduceCustomItemDelete(
            com.lezi.babylog.core.ui.CustomItemDeleteState(),
            com.lezi.babylog.core.ui.CustomItemDeleteAction.Request(itemRow()),
        )
        assertEquals(fromCore.state, fromSettings.state)
        assertEquals(fromCore.command, fromSettings.command)
    }

    private fun requestedState(): CustomItemDeleteState = reduceCustomItemDelete(
        CustomItemDeleteState(),
        CustomItemDeleteAction.Request(item()),
    ).state

    private fun item() = CustomRecordItem(
        id = 42L,
        name = "补充维生素 D",
        iconSlot = 2,
        sortOrder = 0,
    )

    private fun itemRow() = CustomItemManageRow(
        id = 42L,
        name = "补充维生素 D",
        iconSlot = 2,
    )
}
