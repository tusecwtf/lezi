package com.lezi.babylog.sync.session

// 192.168.77.10 is a synthetic RFC1918 LAN test endpoint, never a deployment default.

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.model.SyncStatus
import com.lezi.babylog.core.database.causal.PullDiagnosticReceipt
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
    fun joinedIdleWithDirtyRoomEntitiesReportsLocalCopyAndExactPendingCount() {
        val line = projectShallowSyncLine(
            ShallowSyncFacts(
                transportStatus = SyncStatus.Idle,
                joined = true,
                retainedFamilyIdentity = true,
                waitingForAdmin = false,
                pendingPublishCount = 3,
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
    fun joinedIdleWithoutDirtyRoomEntitiesReportsRelativeLastSuccessTime() {
        val line = projectShallowSyncLine(
            ShallowSyncFacts(
                transportStatus = SyncStatus.Idle,
                joined = true,
                retainedFamilyIdentity = true,
                waitingForAdmin = false,
                pendingPublishCount = 0,
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
                pendingPublishCount = 2,
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
                pendingPublishCount = 4,
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
        assertThat(line.isError).isTrue()
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
            pendingPublishCount = 0,
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
        assertThat(lines[0].isError).isTrue()
        assertThat(lines[1].isError).isFalse()
        assertThat(lines[2].isError).isFalse()
    }

    @Test
    fun retainedReauthIdentityWinsBeforeTheTransportStatusCollectorCatchesUp() {
        val line = projectShallowSyncLine(
            ShallowSyncFacts(
                transportStatus = SyncStatus.Idle,
                joined = false,
                retainedFamilyIdentity = true,
                waitingForAdmin = false,
                pendingPublishCount = 2,
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
    fun joinedIdleWithPendingGenerationResyncRefusesSyncedWithoutMintingAUnit() {
        val line = projectShallowSyncLine(
            ShallowSyncFacts(
                transportStatus = SyncStatus.Idle,
                joined = true,
                retainedFamilyIdentity = true,
                waitingForAdmin = false,
                pendingPublishCount = 0,
                pendingGenerationResync = true,
                lastSuccessAtMillis = 1_000L,
                nowMillis = 121_000L,
            ),
        )

        assertThat(line).isEqualTo(
            ShallowSyncLine(
                state = ShallowSyncState.Pending,
                text = "已保存在本机 · 待同步",
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
                pendingPublishCount = 0,
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
    fun sharedFlowProjectsSessionAndDirtyRoomCountIntoTheSameProductLine() = runTest {
        val line = shallowSyncLineFlow(
            transportStatus = flowOf(SyncStatus.Idle),
            session = flowOf(
                SyncSession(
                    familyId = "family-a",
                    accessToken = "opaque",
                    role = FamilyRole.Member,
                    lastSuccessAt = 1_000L,
                    serverHost = "192.168.77.4",
                ),
            ),
            pendingMemberLogin = flowOf(null),
            pendingPublishCount = flowOf(5),
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
                        serverHost = "192.168.77.4",
                    ),
                ),
                pendingMemberLogin = flowOf(null),
                pendingPublishCount = flowOf(0),
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

    @Test
    fun unacceptedFactProjectsIdentityAndTakesPrecedenceOverBadGroup() {
        val unaccepted = UnacceptedFactPresentation(
            entityType = "record",
            clientUuid = "12345678-abcd-ef01-2345-6789abcdef01",
            recordedAt = 1_000L,
        )
        val badGroup = BadGroupPresentation(
            entityType = "care_plan",
            clientUuid = "87654321-fedc-ba98-7654-3210fedcba98",
            recordedAt = 500L,
        )
        val facts = ShallowSyncFacts(
            transportStatus = SyncStatus.Error,
            joined = true,
            retainedFamilyIdentity = false,
            waitingForAdmin = false,
            pendingPublishCount = 0,
            lastSuccessAtMillis = 1_000L,
            nowMillis = 2_000L,
            unacceptedFact = unaccepted,
            badGroup = badGroup,
        )
        val line = projectShallowSyncLine(facts)
        assertThat(line.state).isEqualTo(ShallowSyncState.Error)
        assertThat(line.text).isEqualTo("护理记录 12345678 · 家里没收下")
        assertThat(line.unacceptedEntityType).isEqualTo("record")
        assertThat(line.unacceptedClientUuid).isEqualTo("12345678-abcd-ef01-2345-6789abcdef01")
    }

    @Test
    fun badGroupProjectsIdentityWhenNoLocalUnacceptedFactExists() {
        val badGroup = BadGroupPresentation(
            entityType = "care_plan",
            clientUuid = "87654321-fedc-ba98-7654-3210fedcba98",
            recordedAt = 500L,
        )
        val facts = ShallowSyncFacts(
            transportStatus = SyncStatus.Error,
            joined = true,
            retainedFamilyIdentity = false,
            waitingForAdmin = false,
            pendingPublishCount = 0,
            lastSuccessAtMillis = 1_000L,
            nowMillis = 2_000L,
            unacceptedFact = null,
            badGroup = badGroup,
        )
        val line = projectShallowSyncLine(facts)
        assertThat(line.state).isEqualTo(ShallowSyncState.Error)
        assertThat(line.text).isEqualTo("护理计划 87654321 · 家里没收下")
        assertThat(line.unacceptedEntityType).isEqualTo("care_plan")
        assertThat(line.unacceptedClientUuid).isEqualTo("87654321-fedc-ba98-7654-3210fedcba98")
    }

    @Test
    fun skippedPullWarningAggregatesWithoutDegradingSyncedMainState() {
        val skippedItems = listOf(
            SkippedPullItem(
                entityType = "care_plan",
                clientUuid = "87654321-fedc-ba98-7654-3210fedcba98",
                recordedAt = 1_000L,
                reasonGateDisplay = "计划引用不完整",
                missingIdentity = "宝宝 a1b2c3d4",
            ),
            SkippedPullItem(
                entityType = "record",
                clientUuid = "12345678-abcd-ef01-2345-6789abcdef01",
                recordedAt = 2_000L,
                reasonGateDisplay = "睡眠类型不符",
            ),
        )
        val line = projectShallowSyncLine(
            ShallowSyncFacts(
                transportStatus = SyncStatus.Idle,
                joined = true,
                retainedFamilyIdentity = false,
                waitingForAdmin = false,
                pendingPublishCount = 0,
                lastSuccessAtMillis = 1_000L,
                nowMillis = 61_000L,
                skippedPullCount = 2,
                skippedPullItems = skippedItems,
            ),
        )
        assertThat(line.state).isEqualTo(ShallowSyncState.Synced)
        assertThat(line.text).isEqualTo("已同步 · 1 分钟前")
        assertThat(line.skippedCount).isEqualTo(2)
        assertThat(line.skippedWarningText).isEqualTo("2 项未收下")
        assertThat(line.skippedItems).isEqualTo(skippedItems)
    }

    @Test
    fun singleSkippedPullUsesSingularWarningCopy() {
        val line = projectShallowSyncLine(
            ShallowSyncFacts(
                transportStatus = SyncStatus.Idle,
                joined = true,
                retainedFamilyIdentity = false,
                waitingForAdmin = false,
                pendingPublishCount = 0,
                lastSuccessAtMillis = 1_000L,
                nowMillis = 30_000L,
                skippedPullCount = 1,
                skippedPullItems = listOf(
                    SkippedPullItem(
                        entityType = "wake_observation",
                        clientUuid = "abcdef12-3456-7890-abcd-ef1234567890",
                        recordedAt = 5_000L,
                        reasonGateDisplay = "醒来改绑",
                    ),
                ),
            ),
        )
        assertThat(line.state).isEqualTo(ShallowSyncState.Synced)
        assertThat(line.skippedCount).isEqualTo(1)
        assertThat(line.skippedWarningText).isEqualTo("1 项未收下")
    }

    @Test
    fun zeroSkippedPullCountClearsWarningAndDetail() {
        val line = projectShallowSyncLine(
            ShallowSyncFacts(
                transportStatus = SyncStatus.Idle,
                joined = true,
                retainedFamilyIdentity = false,
                waitingForAdmin = false,
                pendingPublishCount = 0,
                lastSuccessAtMillis = 1_000L,
                nowMillis = 30_000L,
                skippedPullCount = 0,
                skippedPullItems = emptyList(),
            ),
        )
        assertThat(line.state).isEqualTo(ShallowSyncState.Synced)
        assertThat(line.skippedCount).isEqualTo(0)
        assertThat(line.skippedWarningText).isNull()
        assertThat(line.skippedItems).isEmpty()
    }

    @Test
    fun skippedPullWarningRidesAlongsideAnErrorMainLineWithoutRewritingIt() {
        val line = projectShallowSyncLine(
            ShallowSyncFacts(
                transportStatus = SyncStatus.Error,
                joined = true,
                retainedFamilyIdentity = false,
                waitingForAdmin = false,
                pendingPublishCount = 0,
                lastSuccessAtMillis = 1_000L,
                nowMillis = 30_000L,
                skippedPullCount = 1,
                skippedPullItems = listOf(
                    SkippedPullItem(
                        entityType = "record",
                        clientUuid = "12345678-abcd-ef01-2345-6789abcdef01",
                        recordedAt = 5_000L,
                        reasonGateDisplay = "睡眠类型不符",
                    ),
                ),
            ),
        )
        assertThat(line.state).isEqualTo(ShallowSyncState.Error)
        assertThat(line.text).isEqualTo("暂时无法同步 · 下拉重试")
        assertThat(line.skippedWarningText).isEqualTo("1 项未收下")
    }

    @Test
    fun shallowLineFlowCarriesSkippedPullWarningWhileMainStateStaysSynced() = runTest {
        val line = shallowSyncLineFlow(
            transportStatus = flowOf(SyncStatus.Idle),
            session = flowOf(
                SyncSession(
                    familyId = "family-a",
                    accessToken = "opaque",
                    role = FamilyRole.Member,
                    lastSuccessAt = 1_000L,
                    serverHost = "192.168.77.4",
                ),
            ),
            pendingMemberLogin = flowOf(null),
            pendingPublishCount = flowOf(0),
            skippedPullItems = flowOf(
                listOf(
                    SkippedPullItem(
                        entityType = "custom_item",
                        clientUuid = "abcdef12-3456-7890-abcd-ef1234567890",
                        recordedAt = 5_000L,
                        reasonGateDisplay = "自定义项目缺失",
                    ),
                ),
            ),
            nowMillis = { 61_000L },
        ).first()
        assertThat(line.state).isEqualTo(ShallowSyncState.Synced)
        assertThat(line.text).isEqualTo("已同步 · 1 分钟前")
        assertThat(line.skippedWarningText).isEqualTo("1 项未收下")
        assertThat(line.skippedItems).hasSize(1)
    }

    @Test
    fun deferredGateDisplayNamesStayHumanAndNeverLeakRawEnumNames() {
        assertThat(deferredGateDisplayName("BabyMissing")).isEqualTo("宝宝缺失")
        assertThat(deferredGateDisplayName("CustomItemMissing")).isEqualTo("自定义项目缺失")
        assertThat(deferredGateDisplayName("SleepTypeMismatch")).isEqualTo("睡眠类型不符")
        assertThat(deferredGateDisplayName("WakeRetarget")).isEqualTo("醒来改绑")
        assertThat(deferredGateDisplayName("PlanBabyMissing")).isEqualTo("计划引用不完整")
        assertThat(deferredGateDisplayName("PlanFulfilledRecordBabyMismatch"))
            .isEqualTo("计划引用不完整")
        assertThat(deferredGateDisplayName("FulfillmentPlanMissing")).isEqualTo("履行集合不完整")
        assertThat(deferredGateDisplayName("FulfillmentRecordMissing")).isEqualTo("履行集合不完整")
        assertThat(deferredGateDisplayName("MediaEditGuard")).isEqualTo("媒体编辑守卫")
        assertThat(deferredGateDisplayName("MediaBytesUnstaged")).isEqualTo("媒体字节未就绪")
        assertThat(deferredGateDisplayName("MediaRecordMissing")).isEqualTo("媒体父实体缺失")
        assertThat(deferredGateDisplayName("MediaWakeMissing")).isEqualTo("媒体父实体缺失")
        assertThat(deferredGateDisplayName("FamilyRowMissing")).isEqualTo("家庭行缺失")
        assertThat(deferredGateDisplayName("BabyLocalDirty")).isEqualTo("本机宝宝未发表")
        assertThat(deferredGateDisplayName(null)).isEqualTo("暂时未收下")
        assertThat(deferredGateDisplayName("BrandNewGate")).isEqualTo("暂时未收下")
    }

    @Test
    fun diagnosticReceiptProjectsToHumanSkippedItem() {
        val item = PullDiagnosticReceipt(
            entityType = "record",
            clientUuid = "12345678-abcd-ef01-2345-6789abcdef01",
            code = "reference_unready",
            recordedAt = 5_000L,
            reasonGate = "SleepTypeMismatch",
            missingEntityType = "record",
            missingClientUuid = "missing-sleep",
        ).toSkippedPullItem()
        assertThat(item.reasonGateDisplay).isEqualTo("睡眠类型不符")
        assertThat(item.missingIdentity).isEqualTo("护理记录 missing-")

        val familyRowMiss = PullDiagnosticReceipt(
            entityType = "baby",
            clientUuid = "abcdef12-3456-7890-abcd-ef1234567890",
            code = "reference_unready",
            recordedAt = 6_000L,
            reasonGate = "FamilyRowMissing",
            missingEntityType = "family",
            missingClientUuid = "",
        ).toSkippedPullItem()
        assertThat(familyRowMiss.reasonGateDisplay).isEqualTo("家庭行缺失")
        assertThat(familyRowMiss.missingIdentity).isNull()
    }
}
