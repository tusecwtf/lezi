package com.lezi.babylog.domain

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.sync.HomeLanServerConfig
import com.lezi.babylog.sync.JoinFamilyCommand
import com.lezi.babylog.sync.JoinFamilyDraft
import com.lezi.babylog.sync.NoOpSyncPort
import com.lezi.babylog.sync.SyncPort
import com.lezi.babylog.sync.SyncSession
import com.lezi.babylog.sync.SyncTrigger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test

class JoinFamilyUseCaseTest {
    @Test
    fun invalidDraftFailsBeforeAnyLocalOrNetworkSideEffect() = runTest {
        val local = RecordingJoinFamilyLocalStore()
        val sync = RecordingJoinSyncPort()
        val useCase = DefaultJoinFamilyUseCase(local, sync)

        val result = useCase.execute(
            JoinFamilyRequest(
                draft = JoinFamilyDraft(),
                displayName = "妈妈",
            ),
        )

        assertThat(result is JoinFamilyResult.Failed).isTrue()
        assertThat(local.events).isEmpty()
        assertThat(sync.events).isEmpty()
    }

    @Test
    fun scaffoldFailureStopsBeforeJoinAndNameCache() = runTest {
        val events = mutableListOf<String>()
        val local = RecordingJoinFamilyLocalStore(events).apply {
            scaffoldFailure = IllegalStateException("disk failed")
        }
        val sync = RecordingJoinSyncPort(events)

        val result = DefaultJoinFamilyUseCase(local, sync).execute(validRequest())

        assertThat(result is JoinFamilyResult.Failed).isTrue()
        assertThat((result as JoinFamilyResult.Failed).message).isEqualTo("准备本机家庭失败")
        assertThat(events).containsExactly("scaffold").inOrder()
    }

    @Test
    fun joinFailureDoesNotCacheNameOrRequestSync() = runTest {
        val events = mutableListOf<String>()
        val local = RecordingJoinFamilyLocalStore(events)
        val sync = RecordingJoinSyncPort(events).apply {
            joinResult = Result.failure(IllegalArgumentException("邀请码已失效"))
        }

        val result = DefaultJoinFamilyUseCase(local, sync).execute(validRequest())

        assertThat(result is JoinFamilyResult.Failed).isTrue()
        assertThat((result as JoinFamilyResult.Failed).message).isEqualTo("邀请码已失效")
        assertThat(events).containsExactly("scaffold", "join").inOrder()
    }

    @Test
    fun technicalJoinFailureBecomesProductCopyAtTheUseCaseSeam() = runTest {
        val sync = RecordingJoinSyncPort().apply {
            joinResult = Result.failure(
                IllegalStateException("Failed to connect to http://nas.home:8765/data/db"),
            )
        }

        val result = DefaultJoinFamilyUseCase(RecordingJoinFamilyLocalStore(), sync)
            .execute(validRequest())

        assertThat((result as JoinFamilyResult.Failed).message)
            .isEqualTo("家庭同步服务暂未连接，请稍后重试")
    }

    @Test
    fun successCachesNormalizedMembershipNameThenRequestsExactlyOnePull() = runTest {
        val events = mutableListOf<String>()
        val local = RecordingJoinFamilyLocalStore(events)
        val session = joinedSession()
        val sync = RecordingJoinSyncPort(events).apply {
            joinResult = Result.success(session)
        }

        val result = DefaultJoinFamilyUseCase(local, sync).execute(
            validRequest(displayName = "  妈妈  "),
        )

        assertThat(result).isEqualTo(JoinFamilyResult.Joined(session))
        assertThat(sync.joinedCommand!!.displayName).isEqualTo("妈妈")
        assertThat(events)
            .containsExactly("scaffold", "join", "cache:妈妈", "sync:PullToRefresh")
            .inOrder()
        assertThat(sync.syncRequests).isEqualTo(1)
    }

    @Test
    fun successfulJoinRemainsJoinedWhenLocalNameCacheFails() = runTest {
        val events = mutableListOf<String>()
        val local = RecordingJoinFamilyLocalStore(events).apply {
            cacheFailure = IllegalStateException("local name cache failed")
        }
        val session = joinedSession()
        val sync = RecordingJoinSyncPort(events).apply {
            joinResult = Result.success(session)
        }

        val result = DefaultJoinFamilyUseCase(local, sync).execute(validRequest())

        assertThat(result).isEqualTo(JoinFamilyResult.Joined(session))
        assertThat(events)
            .containsExactly("scaffold", "join", "cache:妈妈", "sync:PullToRefresh")
            .inOrder()
    }

    @Test
    fun successfulJoinRemainsJoinedWhenImmediatePullRequestFails() = runTest {
        val events = mutableListOf<String>()
        val session = joinedSession()
        val sync = RecordingJoinSyncPort(events).apply {
            joinResult = Result.success(session)
            requestSyncFailure = IllegalStateException("sync scheduling failed")
        }

        val result = DefaultJoinFamilyUseCase(
            RecordingJoinFamilyLocalStore(events),
            sync,
        ).execute(validRequest())

        assertThat(result).isEqualTo(JoinFamilyResult.Joined(session))
        assertThat(events)
            .containsExactly("scaffold", "join", "cache:妈妈", "sync:PullToRefresh")
            .inOrder()
    }

    @Test
    fun successfulJoinRemainsJoinedWhenLocalNameCacheIsCancelled() = runTest {
        val events = mutableListOf<String>()
        val local = RecordingJoinFamilyLocalStore(events).apply {
            cacheFailure = CancellationException("cancel-name-cache")
        }
        val session = joinedSession()
        val sync = RecordingJoinSyncPort(events).apply {
            joinResult = Result.success(session)
        }

        val result = DefaultJoinFamilyUseCase(local, sync).execute(validRequest())

        assertThat(result).isEqualTo(JoinFamilyResult.Joined(session))
        assertThat(events)
            .containsExactly("scaffold", "join", "cache:妈妈", "sync:PullToRefresh")
            .inOrder()
    }

    @Test
    fun successfulJoinRemainsJoinedWhenImmediatePullRequestIsCancelled() = runTest {
        val events = mutableListOf<String>()
        val session = joinedSession()
        val sync = RecordingJoinSyncPort(events).apply {
            joinResult = Result.success(session)
            requestSyncFailure = CancellationException("cancel-sync-request")
        }

        val result = DefaultJoinFamilyUseCase(
            RecordingJoinFamilyLocalStore(events),
            sync,
        ).execute(validRequest())

        assertThat(result).isEqualTo(JoinFamilyResult.Joined(session))
        assertThat(events)
            .containsExactly("scaffold", "join", "cache:妈妈", "sync:PullToRefresh")
            .inOrder()
    }

    @Test
    fun scaffoldCancellationPropagatesUnchanged() = runTest {
        val cancelled = CancellationException("cancel-scaffold")
        val local = RecordingJoinFamilyLocalStore().apply {
            scaffoldFailure = cancelled
        }

        val thrown = runCatching {
            DefaultJoinFamilyUseCase(local, RecordingJoinSyncPort()).execute(validRequest())
        }.exceptionOrNull()

        assertThat(thrown).isSameInstanceAs(cancelled)
    }

    @Test
    fun cancellationReturnedByJoinPropagatesUnchanged() = runTest {
        val cancelled = CancellationException("cancel-join")
        val sync = RecordingJoinSyncPort().apply {
            joinResult = Result.failure(cancelled)
        }

        val thrown = runCatching {
            DefaultJoinFamilyUseCase(RecordingJoinFamilyLocalStore(), sync).execute(validRequest())
        }.exceptionOrNull()

        assertThat(thrown).isSameInstanceAs(cancelled)
    }

    private fun validRequest(displayName: String = "妈妈") = JoinFamilyRequest(
        draft = JoinFamilyDraft.fromConfig(
            HomeLanServerConfig(
                host = "nas.home",
                allowedSsids = listOf("Home"),
            ),
            invitation = "INVITE-1234",
        ),
        displayName = displayName,
    )

    private fun joinedSession() = SyncSession(
        familyId = "family-1",
        familyToken = "token-1",
        membershipId = "membership-1",
        serverHost = "nas.home",
        allowedSsids = listOf("Home"),
    )
}

private class RecordingJoinFamilyLocalStore(
    val events: MutableList<String> = mutableListOf(),
) : JoinFamilyLocalStore {
    var scaffoldFailure: Throwable? = null
    var cacheFailure: Throwable? = null

    override suspend fun ensureScaffold() {
        events += "scaffold"
        scaffoldFailure?.let { throw it }
    }

    override suspend fun cacheDisplayName(displayName: String) {
        events += "cache:$displayName"
        cacheFailure?.let { throw it }
    }
}

private class RecordingJoinSyncPort(
    val events: MutableList<String> = mutableListOf(),
) : SyncPort by NoOpSyncPort() {
    var joinResult: Result<SyncSession> = Result.success(SyncSession())
    var joinedCommand: JoinFamilyCommand? = null
    var syncRequests = 0
    var requestSyncFailure: Throwable? = null

    override suspend fun joinFamily(command: JoinFamilyCommand): Result<SyncSession> {
        events += "join"
        joinedCommand = command
        return joinResult
    }

    override fun requestSync(trigger: SyncTrigger) {
        events += "sync:$trigger"
        syncRequests += 1
        requestSyncFailure?.let { throw it }
    }
}
