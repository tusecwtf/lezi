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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
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
            assertThat(viewModel.ui.value.failed).isTrue()
            assertThat(viewModel.ui.value.query).isEqualTo("发烧")
        }

    @Test
    fun cancelledEarlierQueryCannotClearSearchingForReplacementQuery() =
        runTest(mainDispatcherRule.testDispatcher) {
            val releaseCancelledQuery = CompletableDeferred<Unit>()
            val completeReplacementQuery = CompletableDeferred<List<SearchResult>>()
            val staleResult = searchHit(id = 99)
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
            assertThat(viewModel.ui.value.failed).isFalse()
            assertThat(viewModel.ui.value.results).isEmpty()

            completeReplacementQuery.complete(emptyList())
            runCurrent()
        }

    @Test
    fun laterQueryRecoversAfterRepositoryFailure() =
        runTest(mainDispatcherRule.testDispatcher) {
            val recovered = searchHit(id = 7)
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
            assertThat(viewModel.ui.value.failed).isTrue()

            viewModel.onQuery("布洛芬")
            advanceTimeBy(200)
            runCurrent()

            assertThat(viewModel.ui.value.query).isEqualTo("布洛芬")
            assertThat(viewModel.ui.value.searching).isFalse()
            assertThat(viewModel.ui.value.failed).isFalse()
            assertThat(viewModel.ui.value.results).containsExactly(recovered)
        }

    @Test
    fun retryRecoversTheSameQueryAfterRepositoryFailure() =
        runTest(mainDispatcherRule.testDispatcher) {
            val recovered = searchHit(id = 9)
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
            assertThat(viewModel.ui.value.failed).isTrue()

            viewModel.retry()
            advanceTimeBy(200)
            runCurrent()

            assertThat(viewModel.ui.value.query).isEqualTo("布洛芬")
            assertThat(viewModel.ui.value.searching).isFalse()
            assertThat(viewModel.ui.value.failed).isFalse()
            assertThat(viewModel.ui.value.results).containsExactly(recovered)
        }

    @Test
    fun requestOpenRecordUsesSameEditOrViewDispositionAsTimeline() =
        runTest(mainDispatcherRule.testDispatcher) {
            val editable = searchHit(id = 1, canEdit = true)
            val foreign = searchHit(id = 2, canEdit = false)
            val viewModel = SearchViewModel(
                repository = SearchRepository { listOf(editable, foreign) },
            )
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.ui.collect {}
            }

            viewModel.onQuery("药")
            advanceTimeBy(200)
            runCurrent()

            assertThat(viewModel.requestOpenRecord(editable))
                .isEqualTo(SearchRecordOpen(recordId = 1L, canEdit = true))
            runCurrent()
            assertThat(viewModel.ui.value.noticeMessage).isNull()

            assertThat(viewModel.requestOpenRecord(foreign))
                .isEqualTo(SearchRecordOpen(recordId = 2L, canEdit = false))
            runCurrent()
            assertThat(viewModel.ui.value.noticeMessage).isNull()
        }

    @Test
    fun sameQueryRereadsWhenRepositoryRevisionsChange() =
        runTest(mainDispatcherRule.testDispatcher) {
            val baseline = SearchRevision(babyId = 1L, sourceRoleClientUuids = emptySet(), projectionTick = 1L)
            val revisions = MutableSharedFlow<SearchRevision>(extraBufferCapacity = 1)
            val first = searchHit(id = 1)
            val second = searchHit(id = 2)
            var calls = 0
            val viewModel = SearchViewModel(
                repository = object : SearchRepository {
                    override suspend fun search(query: String): List<SearchResult> {
                        calls += 1
                        return if (calls == 1) listOf(first) else listOf(second)
                    }

                    override fun revisions(): Flow<SearchRevision> = flow {
                        emit(baseline)
                        emitAll(revisions)
                    }
                },
            )
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.ui.collect {}
            }

            viewModel.onQuery("药")
            advanceTimeBy(200)
            runCurrent()

            assertThat(viewModel.ui.value.results).containsExactly(first)
            assertThat(viewModel.ui.value.searching).isFalse()
            assertThat(viewModel.ui.value.query).isEqualTo("药")

            assertThat(revisions.tryEmit(baseline.copy(projectionTick = 2L))).isTrue()
            runCurrent()

            assertThat(calls).isEqualTo(2)
            assertThat(viewModel.ui.value.results).containsExactly(second)
            assertThat(viewModel.ui.value.searching).isFalse()
            assertThat(viewModel.ui.value.query).isEqualTo("药")
        }

    @Test
    fun changeDuringSearchIsNotDroppedAsTheFirstRevision() =
        runTest(mainDispatcherRule.testDispatcher) {
            val live = MutableStateFlow(
                SearchRevision(babyId = 1L, sourceRoleClientUuids = emptySet(), projectionTick = 1L),
            )
            val during = searchHit(id = 1)
            val after = searchHit(id = 2)
            var calls = 0
            val viewModel = SearchViewModel(
                repository = object : SearchRepository {
                    override suspend fun search(query: String): List<SearchResult> {
                        calls += 1
                        if (calls == 1) {
                            live.value = live.value.copy(projectionTick = 2L)
                            return listOf(during)
                        }
                        return listOf(after)
                    }

                    override fun revisions(): Flow<SearchRevision> = live
                },
            )
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.ui.collect {}
            }

            viewModel.onQuery("药")
            advanceTimeBy(200)
            runCurrent()

            assertThat(calls).isAtLeast(2)
            assertThat(viewModel.ui.value.results).containsExactly(after)
            assertThat(viewModel.ui.value.searching).isFalse()
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

private fun searchHit(id: Long, canEdit: Boolean = true) = SearchResult(
    record = Record(
        id = id,
        clientUuid = "record-$id",
        babyId = 1,
        type = RecordType.MEDICINE,
        timestamp = 1_700_000_000_000L,
        note = "布洛芬",
        updatedAt = 1_700_000_000_000L,
    ),
    canEdit = canEdit,
)
