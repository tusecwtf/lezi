package com.lezi.babylog.core.model

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Test

class CancellableSleepProjectionTest {
    @Test fun coroutineProjectionPreservesPureProjectionForEmptySmallLargeAndTiedObservations() = runBlocking {
        for (size in listOf(0, 1, 8, 20_000)) {
            val observations = List(size) { index ->
                WakeObservationFact(
                    clientUuid = "wake-${size - index}", wakeTimestamp = 1_000L + index % 17,
                    withdrawn = index % 11 == 0, deleted = index % 13 == 0,
                )
            }
            for (effective in listOf<String?>(null, "wake-2", "missing")) {
                val expected = projectSleepInterval("sleep", 1_000, effective, observations, 2_000, listOf("other" to 3_000L))
                val actual = projectSleepIntervalCancellable("sleep", 1_000, effective, observations, 2_000, listOf("other" to 3_000L))
                assertThat(actual).isEqualTo(expected)
            }
        }
    }

    @Test fun cancellationAtTheSortBoundaryDoesNotFinishTheObservationSort() = runBlocking {
        val operation = Job()
        val observations = object : AbstractCollection<WakeObservationFact>() {
            override val size = 20_000
            override fun iterator(): Iterator<WakeObservationFact> = object : Iterator<WakeObservationFact> {
                var next = 0
                override fun hasNext(): Boolean {
                    if (next == size) operation.cancel()
                    return next < size
                }
                override fun next() = WakeObservationFact("wake-${next++}", (size - next).toLong())
            }
        }
        var returned = false
        val failure = runCatching {
            withContext(operation) {
                projectSleepIntervalCancellable("sleep", 0, observations = observations)
                returned = true
            }
        }.exceptionOrNull()
        assertThat(failure).isInstanceOf(CancellationException::class.java)
        assertThat(returned).isFalse()
    }
}
