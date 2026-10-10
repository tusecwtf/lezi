package com.lezi.babylog.domain

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test

class CareLogBabyCommitIntegrityTest {
    @Test
    fun selectionFailureReportsCommittedBabyAndStableRetryDoesNotCreateAnother() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
        val input = CreateBabyInput(nickname = "豆豆", birthdayEpochDay = 1)
        fakes.settings.currentBabyWriteFailure = IllegalStateException("settings unavailable")
        val failure = runCatching { care.addBaby(input, clientUuid = "baby-command") }.exceptionOrNull()
        assertThat(failure).isInstanceOf(BabyCreationCommittedException::class.java)
        val committedId = (failure as BabyCreationCommittedException).babyId
        assertThat(care.listBabies().map { it.id }).containsExactly(committedId)
        fakes.settings.currentBabyWriteFailure = null
        assertThat(care.addBaby(input, clientUuid = "baby-command")).isEqualTo(committedId)
        assertThat(care.listBabies()).hasSize(1)
    }

    @Test
    fun cancellationAfterCommitStillCarriesTheCommittedIdentity() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
        fakes.settings.currentBabyWriteFailure = CancellationException("screen left")
        val failure = runCatching {
            care.addBaby(CreateBabyInput(nickname = "豆豆", birthdayEpochDay = 1))
        }.exceptionOrNull()
        assertThat(failure).isInstanceOf(CancellationException::class.java)
        assertThat(failure).isInstanceOf(BabyCreationCommittedCancellationException::class.java)
        val committedId = (failure as BabyCreationCommittedCancellationException).babyId
        assertThat(care.listBabies().map { it.id }).containsExactly(committedId)
    }
}
