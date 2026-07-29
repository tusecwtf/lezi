package com.lezi.babylog.feature.settings

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext

enum class SystemCalendarProviderFailure {
    PERMISSION_DENIED,
    PROVIDER_UNAVAILABLE,
    INVALID_CURSOR,
    WRITE_FAILED,
}

sealed interface SystemCalendarProviderResult<out T> {
    data class Success<T>(val value: T) : SystemCalendarProviderResult<T>
    data class Failure(val reason: SystemCalendarProviderFailure) : SystemCalendarProviderResult<Nothing>
}

/** Cancellable execution boundary for Android Calendar Provider operations. */
@OptIn(InternalCoroutinesApi::class)
class SystemCalendarProviderIo(
    private val dispatcher: CoroutineDispatcher,
    private val hasPermission: () -> Boolean,
) {
    suspend fun <T : Any> query(
        onCancel: () -> Unit = {},
        block: suspend () -> T?,
    ): SystemCalendarProviderResult<T> {
        if (!hasPermission()) {
            return SystemCalendarProviderResult.Failure(
                SystemCalendarProviderFailure.PERMISSION_DENIED,
            )
        }
        return withContext(dispatcher) {
            coroutineContext.ensureActive()
            val cancellationNotified = AtomicBoolean(false)
            fun notifyProviderCancellation() {
                if (!cancellationNotified.compareAndSet(false, true)) return
                try {
                    onCancel()
                } catch (_: Exception) {
                    // Cancellation remains authoritative even if provider abort fails.
                }
            }
            val cancellationHandle = coroutineContext.job.invokeOnCompletion(
                onCancelling = true,
                invokeImmediately = true,
            ) { cause ->
                if (cause is CancellationException) {
                    notifyProviderCancellation()
                }
            }
            try {
                val value = block()
                    ?: return@withContext SystemCalendarProviderResult.Failure(
                        SystemCalendarProviderFailure.PROVIDER_UNAVAILABLE,
                    )
                coroutineContext.ensureActive()
                SystemCalendarProviderResult.Success(value)
            } catch (cancellation: CancellationException) {
                notifyProviderCancellation()
                throw cancellation
            } catch (_: SecurityException) {
                coroutineContext.ensureActive()
                SystemCalendarProviderResult.Failure(
                    SystemCalendarProviderFailure.PERMISSION_DENIED,
                )
            } catch (_: IllegalArgumentException) {
                coroutineContext.ensureActive()
                SystemCalendarProviderResult.Failure(
                    SystemCalendarProviderFailure.INVALID_CURSOR,
                )
            } catch (_: IndexOutOfBoundsException) {
                coroutineContext.ensureActive()
                SystemCalendarProviderResult.Failure(
                    SystemCalendarProviderFailure.INVALID_CURSOR,
                )
            } catch (_: Exception) {
                coroutineContext.ensureActive()
                SystemCalendarProviderResult.Failure(
                    SystemCalendarProviderFailure.PROVIDER_UNAVAILABLE,
                )
            } finally {
                cancellationHandle.dispose()
            }
        }
    }

    suspend fun <T : Any> write(
        successful: (T) -> Boolean = { true },
        block: suspend () -> T?,
    ): SystemCalendarProviderResult<T> {
        if (!hasPermission()) {
            return SystemCalendarProviderResult.Failure(
                SystemCalendarProviderFailure.PERMISSION_DENIED,
            )
        }
        return withContext(dispatcher) {
            coroutineContext.ensureActive()
            try {
                val value = block()
                    ?.takeIf(successful)
                    ?: return@withContext SystemCalendarProviderResult.Failure(
                        SystemCalendarProviderFailure.WRITE_FAILED,
                    )
                coroutineContext.ensureActive()
                SystemCalendarProviderResult.Success(value)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: SecurityException) {
                coroutineContext.ensureActive()
                SystemCalendarProviderResult.Failure(
                    SystemCalendarProviderFailure.PERMISSION_DENIED,
                )
            } catch (_: Exception) {
                coroutineContext.ensureActive()
                SystemCalendarProviderResult.Failure(
                    SystemCalendarProviderFailure.WRITE_FAILED,
                )
            }
        }
    }
}
