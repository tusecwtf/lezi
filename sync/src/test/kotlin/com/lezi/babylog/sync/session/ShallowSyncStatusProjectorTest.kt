package com.lezi.babylog.sync.session

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.model.SyncStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ShallowSyncStatusProjectorTest {
    @Test
    fun joinedIdleWithOutboxReportsLocalCopyAndExactPendingCount() {
        val line = projectShallowSyncLine(
            ShallowSyncFacts(
                transportStatus = SyncStatus.Idle,
                joined = true,
                retainedFamilyIdentity = true,
                waitingForAdmin = false,
                pendingOutboxCount = 3,
                lastSuccessAtMillis = 1_000L,
                nowMillis = 121_000L,
            ),
        )

        assertThat(line).isEqualTo(
            ShallowSyncLine(
                state = ShallowSyncState.Pending,
                text = "已保存在本机 · 待同步 3 项",
                pendingCount = 3,
            ),
        )
    }

    @Test
    fun joinedIdleWithoutOutboxReportsRelativeLastSuccessTime() {
        val line = projectShallowSyncLine(
            ShallowSyncFacts(
                transportStatus = SyncStatus.Idle,
                joined = true,
                retainedFamilyIdentity = true,
                waitingForAdmin = false,
                pendingOutboxCount = 0,
                lastSuccessAtMillis = 1_000L,
                nowMillis = 121_000L,
            ),
        )

        assertThat(line).isEqualTo(
            ShallowSyncLine(
                state = ShallowSyncState.Synced,
                text = "已同步 · 2 分钟前",
            ),
        )
    }

    @Test
    fun joinedSyncingKeepsPendingCountVisible() {
        val line = projectShallowSyncLine(
            ShallowSyncFacts(
                transportStatus = SyncStatus.Syncing,
                joined = true,
                retainedFamilyIdentity = true,
                waitingForAdmin = false,
                pendingOutboxCount = 2,
                lastSuccessAtMillis = null,
                nowMillis = 200_000L,
            ),
        )

        assertThat(line).isEqualTo(
            ShallowSyncLine(
                state = ShallowSyncState.Syncing,
                text = "正在同步 · 待同步 2 项",
                pendingCount = 2,
            ),
        )
    }

    @Test
    fun joinedErrorProvidesPullToRetryAndKeepsPendingCountVisible() {
        val line = projectShallowSyncLine(
            ShallowSyncFacts(
                transportStatus = SyncStatus.Error,
                joined = true,
                retainedFamilyIdentity = true,
                waitingForAdmin = false,
                pendingOutboxCount = 4,
                lastSuccessAtMillis = 1_000L,
                nowMillis = 200_000L,
            ),
        )

        assertThat(line).isEqualTo(
            ShallowSyncLine(
                state = ShallowSyncState.Error,
                text = "暂时无法同步 · 待同步 4 项 · 下拉重试",
                pendingCount = 4,
            ),
        )
    }

    @Test
    fun reauthWaitingApprovalAndUnjoinedHaveDistinctProductStates() {
        fun facts(
            status: SyncStatus,
            retainedFamilyIdentity: Boolean,
            waitingForAdmin: Boolean,
        ) = ShallowSyncFacts(
            transportStatus = status,
            joined = false,
            retainedFamilyIdentity = retainedFamilyIdentity,
            waitingForAdmin = waitingForAdmin,
            pendingOutboxCount = 0,
            lastSuccessAtMillis = null,
            nowMillis = 200_000L,
        )

        val lines = listOf(
            projectShallowSyncLine(
                facts(
                    SyncStatus.ReauthRequired,
                    retainedFamilyIdentity = true,
                    waitingForAdmin = false,
                ),
            ),
            projectShallowSyncLine(
                facts(
                    SyncStatus.Disabled,
                    retainedFamilyIdentity = false,
                    waitingForAdmin = true,
                ),
            ),
            projectShallowSyncLine(
                facts(
                    SyncStatus.Disabled,
                    retainedFamilyIdentity = false,
                    waitingForAdmin = false,
                ),
            ),
        )

        assertThat(lines).containsExactly(
            ShallowSyncLine(
                state = ShallowSyncState.ReauthRequired,
                text = "登录已失效 · 请重新登录或申请",
            ),
            ShallowSyncLine(
                state = ShallowSyncState.WaitingForAdmin,
                text = "等待管理员确认",
            ),
            ShallowSyncLine(
                state = ShallowSyncState.Unjoined,
                text = "尚未加入家庭 · 数据仅保存在本机",
            ),
        ).inOrder()
    }

    @Test
    fun retainedReauthIdentityWinsBeforeTheTransportStatusCollectorCatchesUp() {
        val line = projectShallowSyncLine(
            ShallowSyncFacts(
                transportStatus = SyncStatus.Idle,
                joined = false,
                retainedFamilyIdentity = true,
                waitingForAdmin = false,
                pendingOutboxCount = 2,
                lastSuccessAtMillis = 1_000L,
                nowMillis = 2_000L,
            ),
        )

        assertThat(line).isEqualTo(
            ShallowSyncLine(
                state = ShallowSyncState.ReauthRequired,
                text = "登录已失效 · 请重新登录或申请",
            ),
        )
    }

    @Test
    fun joinedBeforeFirstSuccessDoesNotClaimDataWasSynced() {
        val line = projectShallowSyncLine(
            ShallowSyncFacts(
                transportStatus = SyncStatus.Idle,
                joined = true,
                retainedFamilyIdentity = true,
                waitingForAdmin = false,
                pendingOutboxCount = 0,
                lastSuccessAtMillis = null,
                nowMillis = 200_000L,
            ),
        )

        assertThat(line).isEqualTo(
            ShallowSyncLine(
                state = ShallowSyncState.WaitingForFirstSync,
                text = "已加入家庭 · 等待首次同步",
            ),
        )
    }

    @Test
    fun sharedFlowProjectsSessionAndOutboxIntoTheSameProductLine() = runTest {
        val line = shallowSyncLineFlow(
            transportStatus = flowOf(SyncStatus.Idle),
            session = flowOf(
                SyncSession(
                    familyId = "family-a",
                    accessToken = "opaque",
                    role = FamilyRole.Member,
                    lastSuccessAt = 1_000L,
                    serverHost = "192.168.50.4",
                ),
            ),
            pendingMemberLogin = flowOf(null),
            pendingOutboxCount = flowOf(5),
            nowMillis = { 121_000L },
        ).first()

        assertThat(line).isEqualTo(
            ShallowSyncLine(
                state = ShallowSyncState.Pending,
                text = "已保存在本机 · 待同步 5 项",
                pendingCount = 5,
            ),
        )
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun sharedFlowRefreshesRelativeSuccessTimeWhileUpstreamFactsStayStable() = runTest {
        val lines = mutableListOf<ShallowSyncLine>()
        backgroundScope.launch {
            shallowSyncLineFlow(
                transportStatus = flowOf(SyncStatus.Idle),
                session = flowOf(
                    SyncSession(
                        familyId = "family-a",
                        accessToken = "opaque",
                        role = FamilyRole.Member,
                        lastSuccessAt = 0L,
                        serverHost = "192.168.50.4",
                    ),
                ),
                pendingMemberLogin = flowOf(null),
                pendingOutboxCount = flowOf(0),
                nowMillis = { testScheduler.currentTime },
                clockTicks = shallowSyncMinuteTicks(),
            ).take(3).toList(lines)
        }

        runCurrent()
        assertThat(lines.map(ShallowSyncLine::text)).containsExactly("已同步 · 刚刚")

        advanceTimeBy(120_000L)
        runCurrent()

        assertThat(lines.map(ShallowSyncLine::text)).containsExactly(
            "已同步 · 刚刚",
            "已同步 · 1 分钟前",
            "已同步 · 2 分钟前",
        ).inOrder()
    }
}
