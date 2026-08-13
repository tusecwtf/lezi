package com.lezi.babylog.core.common.cancellation

import com.google.common.truth.Truth.assertThat
import java.net.SocketTimeoutException
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeoutException
import org.junit.Test

class CancellationCausePolicyTest {
    @Test
    fun directCancellationReturnsTheOriginalInstance() {
        val cancellation = CancellationException("caller stopped")

        val actual = cancellation.cancellationCauseOrNull()

        assertThat(actual).isSameInstanceAs(cancellation)
    }

    @Test
    fun wrappedCancellationReturnsTheOriginalNestedInstance() {
        val cancellation = CancellationException("caller stopped")
        val wrapper = IllegalStateException("operation failed", cancellation)

        val actual = wrapper.cancellationCauseOrNull()

        assertThat(actual).isSameInstanceAs(cancellation)
    }

    @Test
    fun multiLevelWrapperReturnsTheDeepCancellationInstance() {
        val cancellation = CancellationException("caller stopped")
        val wrapper = IllegalArgumentException(
            "outer",
            IllegalStateException("middle", RuntimeException("inner", cancellation)),
        )

        val actual = wrapper.cancellationCauseOrNull()

        assertThat(actual).isSameInstanceAs(cancellation)
    }

    @Test
    fun ordinaryFailuresAndBusinessTimeoutsAreNotCancellation() {
        val failures = listOf(
            IllegalStateException("database failed"),
            TimeoutException("business operation timed out"),
            SocketTimeoutException("transport timed out"),
        )

        failures.forEach { failure ->
            assertThat(failure.cancellationCauseOrNull()).isNull()
        }
    }

    @Test
    fun cyclicCauseChainStopsAtTheFirstRepeatedInstance() {
        lateinit var first: BoundedCyclicThrowable
        lateinit var second: BoundedCyclicThrowable
        first = BoundedCyclicThrowable("first") { second }
        second = BoundedCyclicThrowable("second") { first }

        val actual = first.cancellationCauseOrNull()

        assertThat(actual).isNull()
        assertThat(first.causeReads).isEqualTo(1)
        assertThat(second.causeReads).isEqualTo(1)
    }

    @Test
    fun throwingCauseAccessorStopsInspectionWithoutReplacingTheBusinessFailure() {
        val failure = ThrowingCauseThrowable()

        val actual = failure.cancellationCauseOrNull()

        assertThat(actual).isNull()
        assertThat(failure.causeReads).isEqualTo(1)
    }

    private class BoundedCyclicThrowable(
        message: String,
        private val next: () -> Throwable,
    ) : Throwable(message) {
        var causeReads = 0
            private set

        override val cause: Throwable
            get() {
                check(causeReads++ < 10) { "cycle was not bounded" }
                return next()
            }
    }

    private class ThrowingCauseThrowable : Throwable("business failure") {
        var causeReads = 0
            private set

        override val cause: Throwable?
            get() {
                causeReads += 1
                error("broken cause accessor")
            }
    }
}
