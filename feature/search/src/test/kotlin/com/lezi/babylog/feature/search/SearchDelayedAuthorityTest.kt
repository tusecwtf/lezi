package com.lezi.babylog.feature.search

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.model.Record
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.sync.session.SyncSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchDelayedAuthorityTest {
    @get:Rule
    val main = SearchMainDispatcherRule()

    @Test
    fun sameBabyOldSessionSuccessIsRejectedBeforeDelayedPermissionCombineArrives() = runTest(main.testDispatcher) {
        val repository = DelayedAuthorityRepository()
        val vm = SearchViewModel(repository)
        val painted = mutableListOf<List<SearchResult>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.ui.collect { painted += it.results }
        }
        vm.onQuery("药")
        advanceTimeBy(200)
        runCurrent()
        val original = vm.ui.value.results.single()
        assertThat(vm.requestOpenRecord(original)?.canEdit).isTrue()

        repository.tick.value = 2
        runCurrent()
        repository.changeAuthority()
        runCurrent()
        assertThat(vm.ui.value.results).containsExactly(original)
        assertThat(vm.ui.value.searching).isTrue()
        repository.releaseRead.complete(Unit)
        runCurrent()

        // The four-input combine is still blocked on the new permission token.
        // Production's direct before/after scope guard rejects its old-session result.
        assertThat(repository.releasePermissionFlow.isCompleted).isFalse()
        assertThat(vm.ui.value.results).isEmpty()
        assertThat(vm.ui.value.failed).isTrue()
        assertThat(vm.ui.value.readOnly).isFalse()
        assertThat(painted.flatten().map { it.record.id }).doesNotContain(2L)
        assertThat(vm.requestOpenRecord(original)).isNull()

        repository.releasePermissionFlow.complete(Unit)
        runCurrent()
        assertThat(vm.ui.value.results.single().record.id).isEqualTo(3L)
        assertThat(vm.ui.value.results.single().canEdit).isFalse()
        assertThat(vm.requestOpenRecord(vm.ui.value.results.single())?.canEdit).isFalse()
    }

    @Test
    fun queryFailureCannotRetainCacheWhenActualAuthorityChangedBeforeItsFlowToken() = runTest(main.testDispatcher) {
        val repository = DelayedAuthorityRepository(failSuspendedRead = true)
        val vm = SearchViewModel(repository)
        vm.onQuery("药")
        advanceTimeBy(200)
        runCurrent()
        repository.tick.value = 2
        runCurrent()
        repository.changeAuthority()
        runCurrent()
        repository.releaseRead.complete(Unit)
        runCurrent()

        assertThat(repository.releasePermissionFlow.isCompleted).isFalse()
        assertThat(vm.ui.value.results).isEmpty()
        assertThat(vm.ui.value.failed).isTrue()
        assertThat(vm.ui.value.readOnly).isFalse()
        repository.releasePermissionFlow.complete(Unit)
        runCurrent()
    }

    @Test
    fun unavailableDirectScopeCannotRetainCacheEvenWhenTheRevisionFlowStillLooksValid() = runTest(main.testDispatcher) {
        val repository = DelayedAuthorityRepository(failSuspendedRead = true)
        val vm = SearchViewModel(repository)
        vm.onQuery("药")
        advanceTimeBy(200)
        runCurrent()
        repository.tick.value = 2
        runCurrent()
        repository.failScopeRead = true
        repository.releaseRead.complete(Unit)
        runCurrent()

        assertThat(vm.ui.value.results).isEmpty()
        assertThat(vm.ui.value.failed).isTrue()
        assertThat(vm.ui.value.readOnly).isFalse()
        repository.failScopeRead = false
        vm.retry()
        advanceTimeBy(200)
        runCurrent()
        assertThat(vm.ui.value.failed).isFalse()
        assertThat(vm.ui.value.results).hasSize(1)
    }

    @Test
    fun tapCannotOpenOldEditableHitBeforeDelayedPermissionFlowArrives() = runTest(main.testDispatcher) {
        val repository = DelayedAuthorityRepository()
        val vm = SearchViewModel(repository)
        vm.onQuery("药")
        advanceTimeBy(200)
        runCurrent()
        val original = vm.ui.value.results.single()
        repository.changeAuthority()
        runCurrent()
        assertThat(repository.releasePermissionFlow.isCompleted).isFalse()
        assertThat(vm.ui.value.searching).isFalse()

        assertThat(vm.requestOpenRecord(original)).isNull()
        assertThat(vm.ui.value.results).isEmpty()
        assertThat(vm.ui.value.failed).isTrue()
        repository.releasePermissionFlow.complete(Unit)
        repository.releaseRead.complete(Unit)
        runCurrent()
    }
}

private class DelayedAuthorityRepository(
    private val failSuspendedRead: Boolean = false,
) : SearchRepository {
    private val initialSession = SyncSession(role = FamilyRole.Owner, membershipId = "owner")
    private val session = MutableStateFlow(initialSession)
    private val baby = MutableStateFlow(1L)
    private val sources = MutableStateFlow(emptySet<String>())
    val tick = MutableStateFlow(1L)
    val releaseRead = CompletableDeferred<Unit>()
    val releasePermissionFlow = CompletableDeferred<Unit>()
    var failScopeRead = false
    private var reads = 0

    fun changeAuthority() {
        session.value = session.value.copy(role = FamilyRole.Member, membershipId = "member")
    }

    override suspend fun currentScope(): SearchScope {
        if (failScopeRead) error("scope path must not leak")
        return SearchScope(baby.value, sources.value, session.value.toSearchPermissionScope())
    }

    override suspend fun search(query: String): List<SearchResult> =
        withCurrentSearchScope(::currentScope) {
            val actor = session.value
            val read = ++reads
            if (read == 2) {
                releaseRead.await()
                if (failSuspendedRead) error("record query failed")
            }
            listOf(SearchResult(
                record = Record(
                    id = read.toLong(),
                    clientUuid = "record-$read",
                    babyId = baby.value,
                    type = RecordType.MEDICINE,
                    timestamp = 1_700_000_000_000L,
                    updatedAt = 1_700_000_000_000L,
                ),
                canEdit = actor.role == FamilyRole.Owner,
            ))
        }

    override fun revisions() = combine(
        baby,
        sources,
        tick,
        session.map { it.toSearchPermissionScope() }.onEach { scope ->
            if (scope != initialSession.toSearchPermissionScope()) releasePermissionFlow.await()
        },
    ) { babyId, sourceRoles, projectionTick, permissions ->
        SearchRevision(babyId, sourceRoles, projectionTick, permissions)
    }
}
