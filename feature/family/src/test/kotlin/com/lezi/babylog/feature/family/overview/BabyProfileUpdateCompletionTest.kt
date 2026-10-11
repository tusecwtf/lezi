package com.lezi.babylog.feature.family.overview

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.model.Baby
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test

class BabyProfileUpdateCompletionTest {
    @Test
    fun lookupFailureCompletesOnceAndUnlocksTheDraftWithoutStartingAWritingStep() = runTest {
        var saving = true
        var saves = 0
        val outcomes = mutableListOf<String?>()

        completeBabyProfileUpdate(
            lookupExisting = { throw IOException("/data/private/profile.db") },
            saveProfile = { saves += 1; null },
            onDone = { outcomes += it; saving = false },
        )

        assertThat(saving).isFalse()
        assertThat(saves).isEqualTo(0)
        assertThat(outcomes).containsExactly("读取宝宝档案失败，请稍后重试")
    }

    @Test
    fun lookupCancellationPropagatesWithoutReportingAnOrdinaryFailure() = runTest {
        val cancelled = CancellationException("owner cleared")
        var saves = 0
        var completions = 0

        val thrown = runCatching {
            completeBabyProfileUpdate(
                lookupExisting = { throw cancelled },
                saveProfile = { saves += 1; null },
                onDone = { completions += 1 },
            )
        }.exceptionOrNull()

        assertThat(thrown).isSameInstanceAs(cancelled)
        assertThat(saves).isEqualTo(0)
        assertThat(completions).isEqualTo(0)
    }

    @Test
    fun saveCancellationKeepsTheExistingCancellationBoundaryAndDoesNotClaimAnOutcome() = runTest {
        val cancelled = CancellationException("save cancelled")
        var completions = 0

        val thrown = runCatching {
            completeBabyProfileUpdate(
                lookupExisting = { baby },
                saveProfile = { throw cancelled },
                onDone = { completions += 1 },
            )
        }.exceptionOrNull()

        assertThat(thrown).isSameInstanceAs(cancelled)
        assertThat(completions).isEqualTo(0)
    }

    @Test
    fun missingBabyCompletesWithoutWritingAndSaveOutcomesPassThroughUnchanged() = runTest {
        var saves = 0
        val outcomes = mutableListOf<String?>()
        completeBabyProfileUpdate(
            lookupExisting = { null },
            saveProfile = { saves += 1; null },
            onDone = outcomes::add,
        )
        assertThat(saves).isEqualTo(0)
        assertThat(outcomes).containsExactly("宝宝档案不存在")

        outcomes.clear()
        for (outcome in listOf(null, "档案结果暂未确认，请保留草稿")) {
            completeBabyProfileUpdate(
                lookupExisting = { baby },
                saveProfile = { saves += 1; outcome },
                onDone = outcomes::add,
            )
        }
        assertThat(saves).isEqualTo(2)
        assertThat(outcomes).containsExactly(null, "档案结果暂未确认，请保留草稿").inOrder()
    }

    private val baby = Baby(
        id = 1,
        familyId = 1,
        nickname = "年年",
        birthdayEpochDay = 20_000,
        themeColorArgb = 0xff6688aa.toInt(),
        clientUuid = "profile-1",
        updatedAt = 1,
    )
}
