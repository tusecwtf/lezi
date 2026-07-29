package com.lezi.babylog.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OpenSleepNormalizationTest {
    @Test
    fun emptyAndSingleCandidateNeedNoClosures() {
        assertThat(normalizeOpenSleeps(emptyList(), repairAtMillis = 10_000L))
            .isEqualTo(OpenSleepNormalization())

        val only = candidate("only", 2_000L)
        assertThat(normalizeOpenSleeps(listOf(only), repairAtMillis = 10_000L))
            .isEqualTo(OpenSleepNormalization(kept = only))
    }

    @Test
    fun permutationsKeepLatestAndCloseOlderAtNextStart() {
        val oldest = candidate("oldest", 1_000L)
        val middle = candidate("middle", 86_401_000L)
        val latest = candidate("latest", 172_801_000L)
        val expected = OpenSleepNormalization(
            kept = latest,
            closures = listOf(
                OpenSleepClosure(oldest, middle.startedAtMillis),
                OpenSleepClosure(middle, latest.startedAtMillis),
            ),
        )

        listOf(
            listOf(oldest, middle, latest),
            listOf(latest, oldest, middle),
            listOf(middle, latest, oldest),
        ).forEach { input ->
            assertThat(normalizeOpenSleeps(input, repairAtMillis = 200_000_000L))
                .isEqualTo(expected)
        }
    }

    @Test
    fun equalTimestampsUseStableKeyAndInjectedRepairClock() {
        val sameStart = 50_000L
        val a = candidate("a", sameStart)
        val b = candidate("b", sameStart)
        val c = candidate("c", sameStart)

        val result = normalizeOpenSleeps(
            candidates = listOf(c, a, b),
            repairAtMillis = 500_000L,
        )

        assertThat(result.kept).isEqualTo(c)
        assertThat(result.closures).containsExactly(
            OpenSleepClosure(a, 500_000L),
            OpenSleepClosure(b, 500_000L),
        ).inOrder()
        result.closures.forEach { closure ->
            assertThat(closure.closedAtMillis).isAtLeast(closure.candidate.startedAtMillis)
        }
    }

    @Test
    fun futureAndLongMaxCandidatesNeverProduceNegativeIntervals() {
        val futureA = candidate("future-a", 5_000_000L)
        val futureB = candidate("future-b", 5_000_000L)
        val maxA = candidate("max-a", Long.MAX_VALUE)
        val maxB = candidate("max-b", Long.MAX_VALUE)

        val future = normalizeOpenSleeps(
            listOf(futureB, futureA),
            repairAtMillis = 1_000L,
        )
        val atMax = normalizeOpenSleeps(
            listOf(maxB, maxA),
            repairAtMillis = Long.MAX_VALUE,
        )

        assertThat(future.closures.single().closedAtMillis).isEqualTo(5_060_000L)
        assertThat(atMax.closures.single().closedAtMillis).isEqualTo(Long.MAX_VALUE)
        (future.closures + atMax.closures).forEach { closure ->
            assertThat(closure.closedAtMillis).isAtLeast(closure.candidate.startedAtMillis)
        }
    }

    private fun candidate(key: String, startedAtMillis: Long) =
        OpenSleepCandidate(stableKey = key, startedAtMillis = startedAtMillis)
}
