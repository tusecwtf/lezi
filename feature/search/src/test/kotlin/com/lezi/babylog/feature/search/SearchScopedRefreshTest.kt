package com.lezi.babylog.feature.search

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.model.Record
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.sync.session.SyncSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchScopedRefreshTest {
    @get:Rule
    val main = SearchMainDispatcherRule()

    @Test
    fun firstFailureHasNoRetainedRowsAndUsesTheFullError() = runTest(main.testDispatcher) {
        val repository = ScopedRepository { error("private database path") }
        val vm = SearchViewModel(repository)
        search(vm)

        assertThat(vm.ui.value.results).isEmpty()
        assertThat(vm.ui.value.failed).isTrue()
        assertThat(vm.ui.value.readOnly).isFalse()
        assertThat(searchPhase(vm.ui.value)).isEqualTo(SearchPhase.Error)
    }

    @Test
    fun sameScopeFailureRetainsReadOnlyRowsAndRetrySuccessRestoresCurrentPermissions() = runTest(main.testDispatcher) {
        val first = hit(1)
        val recovered = hit(2)
        val repository = ScopedRepository { listOf(first) }
        val vm = SearchViewModel(repository)
        search(vm)

        repository.respond = { error("read failed") }
        repository.invalidate()
        runCurrent()
        assertThat(vm.ui.value.results).containsExactly(first)
        assertThat(vm.ui.value.failed).isTrue()
        assertThat(vm.ui.value.readOnly).isTrue()
        assertThat(searchPhase(vm.ui.value)).isEqualTo(SearchPhase.Results)
        assertThat(vm.requestOpenRecord(first)).isEqualTo(SearchRecordOpen(1, false))

        repository.respond = { listOf(recovered) }
        vm.retry()
        assertThat(vm.ui.value.results).containsExactly(first)
        assertThat(vm.ui.value.readOnly).isTrue()
        assertThat(vm.ui.value.searching).isTrue()
        advanceTimeBy(200)
        runCurrent()

        assertThat(vm.ui.value.results).containsExactly(recovered)
        assertThat(vm.ui.value.readOnly).isFalse()
        assertThat(vm.ui.value.failed).isFalse()
        assertThat(vm.requestOpenRecord(first)).isNull()
        assertThat(vm.requestOpenRecord(recovered)).isEqualTo(SearchRecordOpen(2, true))
    }

    @Test
    fun queryChangeDiscardsRowsBeforeDebounceAndNeverRetainsThemOnFailure() = runTest(main.testDispatcher) {
        val old = hit(1)
        val repository = ScopedRepository { listOf(old) }
        val vm = SearchViewModel(repository)
        search(vm)

        repository.respond = { error("query failed") }
        vm.onQuery("睡眠")
        assertThat(vm.ui.value.results).isEmpty()
        assertThat(vm.requestOpenRecord(old)).isNull()
        advanceTimeBy(200)
        runCurrent()

        assertThat(vm.ui.value.results).isEmpty()
        assertThat(vm.ui.value.readOnly).isFalse()
        assertThat(searchPhase(vm.ui.value)).isEqualTo(SearchPhase.Error)
    }

    @Test
    fun babyChangeRemovesRowsWhileTheReplacementReadIsSuspended() = runTest(main.testDispatcher) {
        val old = hit(1)
        val replacement = CompletableDeferred<List<SearchResult>>()
        val repository = ScopedRepository { listOf(old) }
        val vm = SearchViewModel(repository)
        search(vm)

        repository.respond = { replacement.await() }
        repository.live.value = repository.live.value.copy(babyId = 2)
        runCurrent()
        assertThat(vm.ui.value.results).isEmpty()
        assertThat(vm.requestOpenRecord(old)).isNull()

        replacement.completeExceptionally(IllegalStateException("replacement failed"))
        runCurrent()
        assertThat(vm.ui.value.failed).isTrue()
        assertThat(vm.ui.value.results).isEmpty()
    }

    @Test
    fun permissionChangeDuringRetryDebounceImmediatelyDiscardsFailedSnapshot() = runTest(main.testDispatcher) {
        val old = hit(1)
        val repository = ScopedRepository { listOf(old) }
        val vm = SearchViewModel(repository)
        search(vm)
        repository.respond = { error("refresh failed") }
        repository.invalidate()
        runCurrent()
        assertThat(vm.ui.value.readOnly).isTrue()

        vm.retry()
        assertThat(vm.ui.value.results).containsExactly(old)
        repository.live.value = repository.live.value.copy(
            permissionScope = SyncSession(reauthRequired = true).toSearchPermissionScope(),
        )
        runCurrent()
        assertThat(vm.ui.value.results).isEmpty()
        assertThat(vm.ui.value.readOnly).isFalse()
        assertThat(vm.requestOpenRecord(old)).isNull()
        advanceTimeBy(200)
        runCurrent()
        assertThat(vm.ui.value.failed).isTrue()
        assertThat(vm.ui.value.results).isEmpty()
    }

    @Test
    fun lateInitialSuccessIsNeverPaintedAfterAuthorityChanges() = runTest(main.testDispatcher) {
        val stale = hit(1)
        val initial = CompletableDeferred<List<SearchResult>>()
        var calls = 0
        val repository = ScopedRepository {
            if (++calls == 1) initial.await() else error("new authority read failed")
        }
        val vm = SearchViewModel(repository)
        val painted = mutableListOf<List<SearchResult>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.ui.collect { painted += it.results }
        }
        search(vm)
        repository.live.value = repository.live.value.copy(
            permissionScope = SyncSession(membershipId = "different-member").toSearchPermissionScope(),
        )
        runCurrent()
        initial.complete(listOf(stale))
        runCurrent()

        assertThat(calls).isEqualTo(2)
        assertThat(painted.flatten()).doesNotContain(stale)
        assertThat(vm.ui.value.failed).isTrue()
        assertThat(vm.ui.value.results).isEmpty()
    }

    @Test
    fun authorityChangingAwayAndBackCannotAuthorizeAnInFlightResult() = runTest(main.testDispatcher) {
        val first = hit(1)
        val stale = hit(2)
        val current = hit(3)
        val suspended = CompletableDeferred<List<SearchResult>>()
        var calls = 0
        val repository = ScopedRepository {
            when (++calls) {
                1 -> listOf(first)
                2 -> suspended.await()
                else -> listOf(current)
            }
        }
        val vm = SearchViewModel(repository)
        val painted = mutableListOf<List<SearchResult>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.ui.collect { painted += it.results }
        }
        search(vm)
        repository.invalidate()
        runCurrent()
        val original = repository.live.value
        repository.live.value = original.copy(babyId = 2)
        runCurrent()
        repository.live.value = original
        runCurrent()
        suspended.complete(listOf(stale))
        runCurrent()

        assertThat(painted.flatten()).doesNotContain(stale)
        assertThat(vm.ui.value.results).containsExactly(current)
    }

    @Test
    fun hiddenSourceScopeChangesInvalidateRowsAndFilterFreshOutOfScopeHits() = runTest(main.testDispatcher) {
        val hidden = hit(1)
        val wrongBaby = hit(2, babyId = 2)
        val current = hit(3)
        val repository = ScopedRepository { listOf(hidden) }
        val vm = SearchViewModel(repository)
        search(vm)
        repository.respond = { listOf(hidden, wrongBaby, current) }
        repository.live.value = repository.live.value.copy(sourceRoleClientUuids = setOf(hidden.record.clientUuid))
        runCurrent()

        assertThat(vm.ui.value.results).containsExactly(current)
        assertThat(vm.requestOpenRecord(hidden)).isNull()
        assertThat(vm.requestOpenRecord(wrongBaby)).isNull()
    }

    @Test
    fun openingUsesCurrentHitPermissionAndRejectsAnUnknownIdentity() = runTest(main.testDispatcher) {
        val viewOnly = hit(1).copy(canEdit = false)
        val repository = ScopedRepository { listOf(viewOnly) }
        val vm = SearchViewModel(repository)
        search(vm)

        assertThat(vm.requestOpenRecord(viewOnly.copy(canEdit = true)))
            .isEqualTo(SearchRecordOpen(1, false))
        assertThat(vm.requestOpenRecord(viewOnly.copy(record = viewOnly.record.copy(clientUuid = "spoof"))))
            .isNull()
        assertThat(vm.requestOpenRecord(hit(2))).isNull()
    }

    @Test
    fun repositoryWithoutScopeStreamCannotRetainRowsAcrossRetryFailure() = runTest(main.testDispatcher) {
        val old = hit(1)
        var fail = false
        val vm = SearchViewModel(SearchRepository {
            if (fail) error("read failed") else listOf(old)
        })
        search(vm)
        fail = true
        vm.retry()
        assertThat(vm.ui.value.results).isEmpty()
        advanceTimeBy(200)
        runCurrent()

        assertThat(vm.ui.value.results).isEmpty()
        assertThat(vm.ui.value.readOnly).isFalse()
        assertThat(searchPhase(vm.ui.value)).isEqualTo(SearchPhase.Error)
    }

    @Test
    fun scopeObserverFailureDiscardsRowsAndRejectsInFlightResults() = runTest(main.testDispatcher) {
        val old = hit(1)
        val suspended = CompletableDeferred<List<SearchResult>>()
        val source = ScopedRepository { listOf(old) }
        val unavailable = MutableStateFlow(false)
        val vm = SearchViewModel(object : SearchRepository {
            override suspend fun search(query: String) = source.search(query)
            override fun revisions() = combine(source.live, unavailable) { revision, failed ->
                if (failed) error("scope unavailable")
                revision
            }
        })
        search(vm)
        source.respond = { suspended.await() }
        source.invalidate()
        runCurrent()
        unavailable.value = true
        runCurrent()
        assertThat(vm.ui.value.results).isEmpty()
        assertThat(vm.requestOpenRecord(old)).isNull()
        suspended.complete(listOf(old))
        runCurrent()
        assertThat(vm.ui.value.results).isEmpty()
        assertThat(vm.ui.value.failed).isTrue()
        assertThat(vm.ui.value.searching).isFalse()
    }

    @Test
    fun retryResubscribesAfterScopeStreamFailureAndSearchesOnlyTheRecoveredScope() = runTest(main.testDispatcher) {
        val recovered = hit(1)
        val source = ScopedRepository { listOf(recovered) }
        var subscriptions = 0
        var reads = 0
        val vm = SearchViewModel(object : SearchRepository {
            override suspend fun search(query: String): List<SearchResult> {
                reads += 1
                return source.search(query)
            }
            override fun revisions() = flow {
                if (++subscriptions == 1) error("temporary scope failure")
                emitAll(source.live)
            }
        })
        search(vm)
        assertThat(vm.ui.value.failed).isTrue()
        assertThat(reads).isEqualTo(0)

        vm.retry()
        advanceTimeBy(200)
        runCurrent()
        assertThat(subscriptions).isEqualTo(2)
        assertThat(reads).isEqualTo(1)
        assertThat(vm.ui.value.failed).isFalse()
        assertThat(vm.ui.value.results).containsExactly(recovered)
        assertThat(vm.requestOpenRecord(recovered)).isEqualTo(SearchRecordOpen(1, true))
    }

    private fun TestScope.search(vm: SearchViewModel) {
        vm.onQuery("药")
        advanceTimeBy(200)
        runCurrent()
    }
}

private class ScopedRepository(
    var respond: suspend (String) -> List<SearchResult>,
) : SearchRepository {
    val live = MutableStateFlow(SearchRevision(1, emptySet(), 1))
    override suspend fun search(query: String): List<SearchResult> = respond(query)
    override fun revisions() = live
    fun invalidate() {
        live.value = live.value.copy(projectionTick = live.value.projectionTick + 1)
    }
}

private fun hit(id: Long, babyId: Long = 1) = SearchResult(
    record = Record(
        id = id,
        clientUuid = "record-$id",
        babyId = babyId,
        type = RecordType.MEDICINE,
        timestamp = 1_700_000_000_000L,
        note = "布洛芬",
        updatedAt = 1_700_000_000_000L,
    ),
    canEdit = true,
)
