package com.lezi.babylog.domain.localdata

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test

class LocalDataMutationEpochTest {
    @Test
    fun mutationFailsBeforeRunningWhenClearOwnsTheEpoch() = runTest {
        val epoch = LocalDataMutationEpoch()
        val clearEntered = CompletableDeferred<Unit>()
        val allowClear = CompletableDeferred<Unit>()
        val clearing = launch {
            epoch.withClearEpoch {
                clearEntered.complete(Unit)
                allowClear.await()
            }
        }
        clearEntered.await()
        var mutationRan = false

        val failure = runCatching {
            epoch.withMutation { mutationRan = true }
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(LocalDataClearInProgressException::class.java)
        assertThat(mutationRan).isFalse()
        allowClear.complete(Unit)
        clearing.join()
    }

    @Test
    fun clearFailsWithoutWaitingWhenMutationOwnsTheEpoch() = runTest {
        val epoch = LocalDataMutationEpoch()
        val mutationEntered = CompletableDeferred<Unit>()
        val allowMutation = CompletableDeferred<Unit>()
        val mutation = launch {
            epoch.withMutation {
                mutationEntered.complete(Unit)
                allowMutation.await()
            }
        }
        mutationEntered.await()
        var clearRan = false

        val failure = runCatching {
            epoch.withClearEpoch { clearRan = true }
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(LocalDataMutationInProgressException::class.java)
        assertThat(clearRan).isFalse()
        allowMutation.complete(Unit)
        mutation.join()
    }

    @Test
    fun staleFeatureSessionCannotWriteAfterACompletedClearEpoch() = runTest {
        val epoch = LocalDataMutationEpoch()
        val staleGeneration = epoch.currentGeneration()
        epoch.withClearEpoch { }
        var staleWriteRan = false

        val failure = runCatching {
            epoch.withMutationInGeneration(staleGeneration) {
                staleWriteRan = true
            }
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(LocalDataEpochInvalidatedException::class.java)
        assertThat(staleWriteRan).isFalse()
        var currentWriteRan = false
        epoch.withMutationInGeneration(epoch.currentGeneration()) {
            currentWriteRan = true
        }
        assertThat(currentWriteRan).isTrue()
    }
}
