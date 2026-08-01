package com.lezi.babylog.feature.settings.calendar

import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SystemCalendarProviderIoTest {
    @Test
    fun queryRunsOnTheProvidedIoDispatcher() = runTest {
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "calendar-provider-io")
        }.asCoroutineDispatcher().use { dispatcher ->
            val callerThread = Thread.currentThread().name
            val io = SystemCalendarProviderIo(
                dispatcher = dispatcher,
                hasPermission = { true },
            )

            val result = io.query { Thread.currentThread().name }

            assertEquals(
                SystemCalendarProviderResult.Success("calendar-provider-io"),
                result,
            )
            assertNotEquals(callerThread, (result as SystemCalendarProviderResult.Success).value)
        }
    }

    @Test
    fun deniedPermissionIsAClassifiedFailureWithoutCallingTheProvider() = runTest {
        var providerCalled = false
        val io = SystemCalendarProviderIo(
            dispatcher = StandardTestDispatcher(testScheduler),
            hasPermission = { false },
        )

        val result = io.query {
            providerCalled = true
            "unreachable"
        }

        assertEquals(
            SystemCalendarProviderResult.Failure(
                SystemCalendarProviderFailure.PERMISSION_DENIED,
            ),
            result,
        )
        assertFalse(providerCalled)
    }

    @Test
    fun queryClassifiesUnavailableInvalidCursorAndSecurityFailures() = runTest {
        val io = SystemCalendarProviderIo(
            dispatcher = StandardTestDispatcher(testScheduler),
            hasPermission = { true },
        )

        assertEquals(
            SystemCalendarProviderResult.Failure(
                SystemCalendarProviderFailure.PROVIDER_UNAVAILABLE,
            ),
            io.query<String> { null },
        )
        assertEquals(
            SystemCalendarProviderResult.Failure(
                SystemCalendarProviderFailure.INVALID_CURSOR,
            ),
            io.query<String> { throw IllegalArgumentException("missing column") },
        )
        assertEquals(
            SystemCalendarProviderResult.Failure(
                SystemCalendarProviderFailure.PERMISSION_DENIED,
            ),
            io.query<String> { throw SecurityException("permission revoked") },
        )
        assertEquals(
            SystemCalendarProviderResult.Failure(
                SystemCalendarProviderFailure.PROVIDER_UNAVAILABLE,
            ),
            io.query<String> { throw IllegalStateException("provider died") },
        )
    }

    @Test
    fun writeClassifiesNullZeroExceptionAndRevokedPermission() = runTest {
        val io = SystemCalendarProviderIo(
            dispatcher = StandardTestDispatcher(testScheduler),
            hasPermission = { true },
        )

        assertEquals(
            SystemCalendarProviderResult.Failure(SystemCalendarProviderFailure.WRITE_FAILED),
            io.write<String> { null },
        )
        assertEquals(
            SystemCalendarProviderResult.Failure(SystemCalendarProviderFailure.WRITE_FAILED),
            io.write(successful = { it > 0 }) { 0 },
        )
        assertEquals(
            SystemCalendarProviderResult.Failure(SystemCalendarProviderFailure.WRITE_FAILED),
            io.write<String> { throw IllegalStateException("insert failed") },
        )
        assertEquals(
            SystemCalendarProviderResult.Failure(
                SystemCalendarProviderFailure.PERMISSION_DENIED,
            ),
            io.write<String> { throw SecurityException("permission revoked") },
        )
    }

    @Test
    fun cancellingASlowQueryCancelsTheProviderAndPublishesNoResult() = runTest {
        val io = SystemCalendarProviderIo(
            dispatcher = StandardTestDispatcher(testScheduler),
            hasPermission = { true },
        )
        val queryStarted = CompletableDeferred<Unit>()
        val providerCancelled = CompletableDeferred<Unit>()
        var resultPublished = false
        val queryJob = launch {
            io.query(
                onCancel = { providerCancelled.complete(Unit) },
            ) {
                queryStarted.complete(Unit)
                kotlinx.coroutines.awaitCancellation()
            }
            resultPublished = true
        }

        queryStarted.await()
        queryJob.cancelAndJoin()

        assertEquals(Unit, providerCancelled.await())
        assertFalse(resultPublished)
    }
}
