package com.lezi.babylog.feature.search

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.model.Record
import com.lezi.babylog.core.model.RecordType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    @get:Rule
    val mainDispatcherRule = SearchMainDispatcherRule()

    @Test
    fun repositoryFailureBecomesRecoverableErrorInsteadOfPermanentLoading() =
        runTest(mainDispatcherRule.testDispatcher) {
            val viewModel = SearchViewModel(
                repository = SearchRepository {
                    error("database path must not leak")
                },
            )
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.ui.collect {}
            }

            viewModel.onQuery("发烧")
            runCurrent()
            assertThat(viewModel.ui.value.searching).isTrue()

            advanceTimeBy(200)
            runCurrent()

            assertThat(viewModel.ui.value.searching).isFalse()
            assertThat(viewModel.ui.value.errorMessage).isEqualTo("搜索失败，请重试")
            assertThat(viewModel.ui.value.query).isEqualTo("发烧")
        }

    @Test
    fun cancelledEarlierQueryCannotClearSearchingForReplacementQuery() =
        runTest(mainDispatcherRule.testDispatcher) {
            val releaseCancelledQuery = CompletableDeferred<Unit>()
            val completeReplacementQuery = CompletableDeferred<List<Record>>()
            val staleResult = searchRecord(id = 99)
            val viewModel = SearchViewModel(
                repository = SearchRepository { query ->
                    if (query == "A") {
                        try {
                            awaitCancellation()
                        } catch (error: CancellationException) {
                            withContext(NonCancellable) {
                                releaseCancelledQuery.await()
                            }
                            listOf(staleResult)
                        }
                    } else {
                        completeReplacementQuery.await()
                    }
                },
            )
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.ui.collect {}
            }

            viewModel.onQuery("A")
            advanceTimeBy(200)
            runCurrent()

            viewModel.onQuery("B")
            runCurrent()
            assertThat(viewModel.ui.value.searching).isTrue()

            releaseCancelledQuery.complete(Unit)
            runCurrent()

            assertThat(viewModel.ui.value.query).isEqualTo("B")
            assertThat(viewModel.ui.value.searching).isTrue()
            assertThat(viewModel.ui.value.errorMessage).isNull()
            assertThat(viewModel.ui.value.results).isEmpty()

            completeReplacementQuery.complete(emptyList())
            runCurrent()
        }

    @Test
    fun laterQueryRecoversAfterRepositoryFailure() =
        runTest(mainDispatcherRule.testDispatcher) {
            val recovered = searchRecord(id = 7)
            val viewModel = SearchViewModel(
                repository = SearchRepository { query ->
                    if (query == "发烧") error("database unavailable")
                    listOf(recovered)
                },
            )
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.ui.collect {}
            }

            viewModel.onQuery("发烧")
            advanceTimeBy(200)
            runCurrent()
            assertThat(viewModel.ui.value.errorMessage).isNotNull()

            viewModel.onQuery("布洛芬")
            advanceTimeBy(200)
            runCurrent()

            assertThat(viewModel.ui.value.query).isEqualTo("布洛芬")
            assertThat(viewModel.ui.value.searching).isFalse()
            assertThat(viewModel.ui.value.errorMessage).isNull()
            assertThat(viewModel.ui.value.results).containsExactly(recovered)
        }

    @Test
    fun retryRecoversTheSameQueryAfterRepositoryFailure() =
        runTest(mainDispatcherRule.testDispatcher) {
            val recovered = searchRecord(id = 9)
            var firstAttempt = true
            val viewModel = SearchViewModel(
                repository = SearchRepository {
                    if (firstAttempt) {
                        firstAttempt = false
                        error("database unavailable")
                    }
                    listOf(recovered)
                },
            )
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.ui.collect {}
            }

            viewModel.onQuery("布洛芬")
            advanceTimeBy(200)
            runCurrent()
            assertThat(viewModel.ui.value.errorMessage).isNotNull()

            viewModel.retry()
            advanceTimeBy(200)
            runCurrent()

            assertThat(viewModel.ui.value.query).isEqualTo("布洛芬")
            assertThat(viewModel.ui.value.searching).isFalse()
            assertThat(viewModel.ui.value.errorMessage).isNull()
            assertThat(viewModel.ui.value.results).containsExactly(recovered)
        }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SearchMainDispatcherRule(
    val testDispatcher: TestDispatcher = StandardTestDispatcher(),
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(testDispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}

private fun searchRecord(id: Long) = Record(
    id = id,
    clientUuid = "record-$id",
    babyId = 1,
    type = RecordType.MEDICINE,
    timestamp = 1_700_000_000_000L,
    note = "布洛芬",
    updatedAt = 1_700_000_000_000L,
)
