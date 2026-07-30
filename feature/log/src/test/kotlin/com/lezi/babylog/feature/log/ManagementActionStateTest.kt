package com.lezi.babylog.feature.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagementActionStateTest {
    private val skip = ManagementActionRequest(ManagementActionKind.SkipPlan, targetId = 7L)
    private val delete = ManagementActionRequest(ManagementActionKind.DeleteRecord, targetId = 9L)

    @Test
    fun duplicateBeginIsRejectedWhileTheFirstActionIsRunning() {
        val first = beginManagementAction(ManagementActionState.Idle, skip)
        val duplicate = beginManagementAction(first.state, skip)

        assertTrue(first.accepted)
        assertEquals(ManagementActionState.Running(skip), first.state)
        assertFalse(duplicate.accepted)
        assertEquals(first.state, duplicate.state)
    }

    @Test
    fun failureStaysOnTheTargetAndRetryCanStart() {
        val running = beginManagementAction(ManagementActionState.Idle, skip).state
        val failed = finishManagementAction(
            state = running,
            request = skip,
            result = Result.failure(Exception("跳过失败，请重试")),
        )

        assertTrue(failed.accepted)
        assertEquals(
            ManagementActionState.Failed(skip, "跳过失败，请重试"),
            failed.state,
        )
        assertEquals("跳过失败，请重试", failed.announcement)
        assertTrue(beginManagementAction(failed.state, skip).accepted)
    }

    @Test
    fun staleCompletionCannotFinishAnotherTarget() {
        val runningDelete = beginManagementAction(ManagementActionState.Idle, delete).state
        val stale = finishManagementAction(
            state = runningDelete,
            request = skip,
            result = Result.success("已跳过护理计划"),
        )

        assertFalse(stale.accepted)
        assertEquals(runningDelete, stale.state)
        assertEquals(null, stale.announcement)
    }

    @Test
    fun successReturnsToIdleAndAnnouncesExactlyOnce() {
        val running = beginManagementAction(ManagementActionState.Idle, delete).state
        val success = finishManagementAction(
            state = running,
            request = delete,
            result = Result.success("已删除记录"),
        )
        val duplicate = finishManagementAction(
            state = success.state,
            request = delete,
            result = Result.success("已删除记录"),
        )

        assertTrue(success.accepted)
        assertEquals(ManagementActionState.Idle, success.state)
        assertEquals("已删除记录", success.announcement)
        assertFalse(duplicate.accepted)
        assertEquals(null, duplicate.announcement)
    }
}
