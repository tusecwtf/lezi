package com.lezi.babylog.feature.settings

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

        assertEquals(item(), requested.state.target)
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

        assertEquals(item(), failed.state.target)
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
    fun impactCopyNamesTargetAndExplainsFamilyHistoryAndLocalHideBoundaries() {
        val confirmation = customItemDeleteConfirmation(item())

        assertEquals("删除共享项目「补充维生素 D」？", confirmation.title)
        assertTrue(confirmation.message.contains("家庭共享项目中移除"))
        assertTrue(confirmation.message.contains("不能再用它新建记录或护理计划"))
        assertTrue(confirmation.message.contains("已有记录与计划仍保留原名称和图标"))
        assertTrue(confirmation.message.contains("本机显示"))
        assertTrue(confirmation.message.contains("无法撤销"))
        assertFalse(confirmation.message.contains("tombstone"))
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
}
