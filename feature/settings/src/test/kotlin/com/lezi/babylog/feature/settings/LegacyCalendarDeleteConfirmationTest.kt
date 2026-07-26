package com.lezi.babylog.feature.settings

import com.lezi.babylog.domain.CalendarEvent
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LegacyCalendarDeleteConfirmationTest {
    @Test
    fun requestingDeletionDoesNotEmitCommandBeforeConfirmation() {
        val event = legacyEvent()

        val transition = reduceLegacyCalendarDelete(
            state = LegacyCalendarDeleteState(),
            action = LegacyCalendarDeleteAction.Request(event),
        )

        assertEquals(event, transition.state.target)
        assertNull(transition.command)
    }

    @Test
    fun cancellingConfirmationClearsTargetWithoutEmittingCommand() {
        val requested = reduceLegacyCalendarDelete(
            state = LegacyCalendarDeleteState(),
            action = LegacyCalendarDeleteAction.Request(legacyEvent()),
        ).state

        val transition = reduceLegacyCalendarDelete(
            state = requested,
            action = LegacyCalendarDeleteAction.Cancel,
        )

        assertEquals(LegacyCalendarDeleteState(), transition.state)
        assertNull(transition.command)
    }

    @Test
    fun confirmingEmitsOneCommandAndBlocksDuplicateConfirmation() {
        val event = legacyEvent()
        val requested = reduceLegacyCalendarDelete(
            state = LegacyCalendarDeleteState(),
            action = LegacyCalendarDeleteAction.Request(event),
        ).state

        val first = reduceLegacyCalendarDelete(
            state = requested,
            action = LegacyCalendarDeleteAction.Confirm,
        )
        val duplicate = reduceLegacyCalendarDelete(
            state = first.state,
            action = LegacyCalendarDeleteAction.Confirm,
        )

        assertEquals(event, first.command?.event)
        assertTrue(first.state.deleting)
        assertEquals(first.state, duplicate.state)
        assertNull(duplicate.command)
    }

    @Test
    fun deletionInProgressCannotBeDismissedBeforeCommandFinishes() {
        val requested = reduceLegacyCalendarDelete(
            state = LegacyCalendarDeleteState(),
            action = LegacyCalendarDeleteAction.Request(legacyEvent()),
        ).state
        val deleting = reduceLegacyCalendarDelete(
            state = requested,
            action = LegacyCalendarDeleteAction.Confirm,
        ).state

        val cancelled = reduceLegacyCalendarDelete(
            state = deleting,
            action = LegacyCalendarDeleteAction.Cancel,
        )

        assertEquals(deleting, cancelled.state)
        assertNull(cancelled.command)
    }

    @Test
    fun failedDeletionKeepsTargetAndCanBeRetried() {
        val event = legacyEvent()
        val requested = reduceLegacyCalendarDelete(
            state = LegacyCalendarDeleteState(),
            action = LegacyCalendarDeleteAction.Request(event),
        ).state
        val deleting = reduceLegacyCalendarDelete(
            state = requested,
            action = LegacyCalendarDeleteAction.Confirm,
        ).state

        val failed = reduceLegacyCalendarDelete(
            state = deleting,
            action = LegacyCalendarDeleteAction.Finished("删除失败，请重试"),
        )
        val retried = reduceLegacyCalendarDelete(
            state = failed.state,
            action = LegacyCalendarDeleteAction.Confirm,
        )

        assertEquals(event, failed.state.target)
        assertEquals("删除失败，请重试", failed.state.error)
        assertEquals(false, failed.state.deleting)
        assertNull(failed.command)
        assertEquals(event, retried.command?.event)
        assertTrue(retried.state.deleting)
        assertNull(retried.state.error)
    }

    @Test
    fun successfulDeletionClosesConfirmation() {
        val requested = reduceLegacyCalendarDelete(
            state = LegacyCalendarDeleteState(),
            action = LegacyCalendarDeleteAction.Request(legacyEvent()),
        ).state
        val deleting = reduceLegacyCalendarDelete(
            state = requested,
            action = LegacyCalendarDeleteAction.Confirm,
        ).state

        val finished = reduceLegacyCalendarDelete(
            state = deleting,
            action = LegacyCalendarDeleteAction.Finished(null),
        )

        assertEquals(LegacyCalendarDeleteState(), finished.state)
        assertNull(finished.command)
    }

    @Test
    fun impactCopyNamesTheLocalEventAndEveryDeletionBoundary() {
        val event = legacyEvent().copy(
            eventAt = Instant.parse("2026-07-27T02:30:00Z").toEpochMilli(),
        )

        val copy = legacyCalendarDeleteImpactCopy(
            event = event,
            zone = ZoneId.of("Asia/Shanghai"),
        )

        assertEquals(
            "「社区体检 · 7月27日 10:30」只会从本机乐记日历移除，并取消这条日程的乐记提醒；" +
                "不会改动家庭护理计划或系统日历。删除后不可撤销。",
            copy,
        )
    }

    @Test
    fun deletionFailureUsesProductCopyWithoutTechnicalDetails() {
        assertEquals(
            "删除失败，请重试",
            legacyCalendarDeleteFailureCopy(
                IllegalStateException("SQLite error at /data/user/0/com.lezi.babylog"),
            ),
        )
        assertEquals(
            "日程已发生变化，请重试",
            legacyCalendarDeleteFailureCopy(
                IllegalStateException("日程已发生变化，请重试"),
            ),
        )
    }

    @Test
    fun deleteCommandRethrowsCancellation() = runBlocking {
        val cancellation = CancellationException("screen closed")

        try {
            executeLegacyCalendarDelete(legacyEvent()) {
                throw cancellation
            }
            fail("CancellationException must escape")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
    }

    @Test
    fun deleteCommandMapsFailureForRetry() = runBlocking {
        val result = executeLegacyCalendarDelete(legacyEvent()) {
            error("SQLite error at /data/user/0/com.lezi.babylog")
        }

        assertEquals("删除失败，请重试", result)
    }
}

private fun legacyEvent(): CalendarEvent = CalendarEvent(
    id = 42L,
    clientUuid = "legacy-event-42",
    babyId = 7L,
    title = "社区体检",
    note = null,
    eventAt = 1_785_481_200_000L,
    remindAt = 1_785_477_600_000L,
)
