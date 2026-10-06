package com.lezi.babylog.feature.family.conflict

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.domain.carelog.ConflictInbox
import com.lezi.babylog.domain.carelog.ConflictInboxActor
import com.lezi.babylog.domain.carelog.ConflictInboxBranchTombstone
import com.lezi.babylog.domain.carelog.ConflictInboxItem
import com.lezi.babylog.domain.carelog.ConflictInboxKind
import com.lezi.babylog.domain.carelog.ConflictInboxMedia
import com.lezi.babylog.sync.conflict.ConflictRootType
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * 0.5.4 票 08（T1）冲突收件箱相位建模：只测对外行为与可见状态——
 * 首帧不闪假空态、失败可原地重试、无「待加载」工程词、动词保持 424fc4a2 口径。
 *
 * 订阅方式沿用仓内先例（FamilyHostBehaviorTest）：生产 started（WhileSubscribed）
 * + UnconfinedTestDispatcher 收集器驱动相位流。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConflictInboxPhaseTest {

    // --- 相位状态机：对外可见相位 ---

    @Test
    fun firstFrameStaysNotLoadedAndIsNeverARenderableEmptyState() = runTest {
        val upstream = flow<ConflictInbox> { awaitCancellation() }
        val machine = ConflictInboxPhaseFlow(upstream, backgroundScope)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            machine.phases.collect()
        }
        runCurrent()

        assertThat(machine.phases.value).isEqualTo(ConflictInboxPhase.NotLoaded)
        assertThat(machine.phases.value).isNotEqualTo(ConflictInboxPhase.Empty)
    }

    @Test
    fun loadedProjectionSplitsEmptyFromContent() = runTest {
        val upstream = MutableStateFlow(ConflictInbox(emptyList()))
        val machine = ConflictInboxPhaseFlow(upstream, backgroundScope)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            machine.phases.collect()
        }
        runCurrent()
        assertThat(machine.phases.value).isEqualTo(ConflictInboxPhase.Empty)

        val loaded = ConflictInbox(listOf(branchedItem()))
        upstream.value = loaded
        runCurrent()
        assertThat(machine.phases.value).isEqualTo(ConflictInboxPhase.Content(loaded))
    }

    @Test
    fun projectionToPhaseNeverTurnsUnloadedIntoEmpty() {
        assertThat(ConflictInbox(emptyList()).toPhase()).isEqualTo(ConflictInboxPhase.Empty)
        val inbox = ConflictInbox(listOf(branchedItem()))
        assertThat(inbox.toPhase()).isEqualTo(ConflictInboxPhase.Content(inbox))
    }

    @Test
    fun failureShowsErrorThenRetryReloadsThroughLoadingIntoContent() = runTest {
        var attempt = 0
        val upstream = flow {
            attempt += 1
            if (attempt == 1) throw IOException("家庭服务器暂时不可达")
            delay(5_000)
            emit(ConflictInbox(listOf(branchedItem())))
        }
        val machine = ConflictInboxPhaseFlow(upstream, backgroundScope)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            machine.phases.collect()
        }
        runCurrent()
        assertThat(machine.phases.value).isEqualTo(ConflictInboxPhase.Error)

        machine.retry()
        runCurrent()
        assertThat(machine.phases.value).isEqualTo(ConflictInboxPhase.Loading)

        advanceTimeBy(5_000)
        runCurrent()
        assertThat(machine.phases.value).isInstanceOf(ConflictInboxPhase.Content::class.java)
    }

    @Test
    fun repeatedFailureKeepsErrorRetryableInPlace() = runTest {
        val upstream = flow<ConflictInbox> {
            delay(5_000)
            throw IOException("still down")
        }
        val machine = ConflictInboxPhaseFlow(upstream, backgroundScope)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            machine.phases.collect()
        }
        runCurrent()
        // 首次尝试尚未出结果：保持未加载，而不是空态。
        assertThat(machine.phases.value).isEqualTo(ConflictInboxPhase.NotLoaded)
        advanceTimeBy(5_000)
        runCurrent()
        assertThat(machine.phases.value).isEqualTo(ConflictInboxPhase.Error)

        machine.retry()
        runCurrent()
        assertThat(machine.phases.value).isEqualTo(ConflictInboxPhase.Loading)
        advanceTimeBy(5_000)
        runCurrent()
        assertThat(machine.phases.value).isEqualTo(ConflictInboxPhase.Error)

        machine.retry()
        runCurrent()
        assertThat(machine.phases.value).isEqualTo(ConflictInboxPhase.Loading)
    }

    // --- 文案：Tier B 常量锁定 + 术语红线 ---

    @Test
    fun copyUsesPlainProductLanguageWithoutEngineeringPlaceholders() {
        val all = listOf(
            CONFLICT_INBOX_EXPLANATION,
            CONFLICT_INBOX_LOADING_TITLE,
            CONFLICT_INBOX_LOADING_MESSAGE,
            CONFLICT_INBOX_EMPTY_TITLE,
            CONFLICT_INBOX_EMPTY_MESSAGE,
            CONFLICT_INBOX_ERROR_TITLE,
            CONFLICT_INBOX_ERROR_MESSAGE,
            CONFLICT_INBOX_RETRY_ACTION,
            CONFLICT_INBOX_FETCHING_DETAILS,
        )
        all.forEach { line ->
            assertThat(line.isNotBlank()).isTrue()
            assertThat(line.contains("待加载")).isFalse()
            assertThat(line.contains("Owner")).isFalse()
        }
        assertThat(CONFLICT_INBOX_RETRY_ACTION).isEqualTo("重试")
        assertThat(CONFLICT_INBOX_FETCHING_DETAILS).isEqualTo("正在获取详情…")
        // 空态与加载态必须可区分：空态说「没有」，加载态说「正在」。
        assertThat(CONFLICT_INBOX_EMPTY_TITLE).isEqualTo("目前没有待处理项")
        assertThat(CONFLICT_INBOX_LOADING_TITLE).contains("正在")
        assertThat(CONFLICT_INBOX_ERROR_TITLE).contains("暂时")
        // 顶部心智解释说明「什么时候出现」，保持「待处理」口径，不引入家庭删除措辞。
        assertThat(CONFLICT_INBOX_EXPLANATION).contains("待处理项")
        assertThat(CONFLICT_INBOX_EXPLANATION).contains("会出现在这里")
        assertThat(CONFLICT_INBOX_EXPLANATION).doesNotContain("删除")
    }

    // --- 卡片元数据纪律：未就绪行不渲染，整卡未就绪给「正在获取详情…」 ---

    @Test
    fun coldCardShowsFetchingDetailsInsteadOfEngineeringPlaceholders() {
        val cold = branchedItem(
            actor = ConflictInboxActor.RequiresDetail,
            stableTombstone = false,
            branchTombstone = ConflictInboxBranchTombstone.RequiresDetail,
            media = ConflictInboxMedia.RequiresDetail(0),
        )

        val meta = conflictInboxCardMeta(cold)

        assertThat(meta.metaLine).isEqualTo("宝宝 豆豆 · 正在获取详情…")
        assertThat(meta.actionLabel).isEqualTo("审阅")
        assertThat(meta.onClickActionLabel).isEqualTo("审阅宝宝资料冲突")
    }

    @Test
    fun partialDetailRendersOnlyReadyRows() {
        val meta = conflictInboxCardMeta(
            branchedItem(
                actor = ConflictInboxActor.Known("member-a", "爸爸"),
                stableTombstone = false,
                branchTombstone = ConflictInboxBranchTombstone.RequiresDetail,
                media = ConflictInboxMedia.RequiresDetail(2),
            ),
        )

        assertThat(meta.metaLine).isEqualTo("宝宝 豆豆 · 提交者 爸爸")
    }

    @Test
    fun deletionAndMediaRowsKeepExactReadyStates() {
        val deleted = conflictInboxCardMeta(
            branchedItem(
                actor = ConflictInboxActor.RequiresDetail,
                stableTombstone = true,
                branchTombstone = ConflictInboxBranchTombstone.RequiresDetail,
                media = ConflictInboxMedia.RequiresDetail(0),
            ),
        )
        assertThat(deleted.metaLine).isEqualTo("宝宝 豆豆 · 当前已删除")

        val candidate = conflictInboxCardMeta(
            branchedItem(
                actor = ConflictInboxActor.Known("member-b", "妈妈"),
                stableTombstone = false,
                branchTombstone = ConflictInboxBranchTombstone.Known(true),
                media = ConflictInboxMedia.Known(3),
            ),
        )
        assertThat(candidate.metaLine).isEqualTo("宝宝 豆豆 · 提交者 妈妈 · 含删除候选 · 3张照片")

        val kept = conflictInboxCardMeta(
            branchedItem(
                actor = ConflictInboxActor.RequiresDetail,
                stableTombstone = false,
                branchTombstone = ConflictInboxBranchTombstone.Known(false),
                media = ConflictInboxMedia.Known(1),
            ),
        )
        assertThat(kept.metaLine).isEqualTo("宝宝 豆豆 · 当前保留 · 1张照片")
    }

    @Test
    fun unalignedCardsKeepReasonAndProcessVerbWithoutFetchingDetails() {
        val item = ConflictInboxItem(
            conflictId = "local:extra:record:uuid-extra",
            rootType = ConflictRootType.Record,
            clientUuid = "uuid-extra",
            rootLabel = "护理记录",
            title = "护理记录 uuid-extra",
            babyLabel = null,
            actor = ConflictInboxActor.RequiresDetail,
            stableTombstone = null,
            branchTombstone = ConflictInboxBranchTombstone.RequiresDetail,
            media = ConflictInboxMedia.RequiresDetail(0),
            updatedAt = 10,
            kind = ConflictInboxKind.LocalExtra,
            reasonLabel = "本机有、家里没有",
        )

        val meta = conflictInboxCardMeta(item)

        assertThat(meta.metaLine).isEqualTo("本机有、家里没有")
        assertThat(meta.actionLabel).isEqualTo("处理")
        assertThat(meta.onClickActionLabel).isEqualTo("处理护理记录")
        assertThat(meta.contentDescription(item.rootLabel, item.title))
            .isEqualTo("护理记录，护理记录 uuid-extra，本机有、家里没有")
    }

    @Test
    fun spokenDescriptionKeepsRootTitleAndVisibleMetaOnly() {
        val item = branchedItem()
        val meta = conflictInboxCardMeta(item)

        assertThat(meta.contentDescription(item.rootLabel, item.title))
            .isEqualTo("宝宝资料，豆豆，${meta.metaLine}")
    }

    @Test
    fun noRenderedMetaEverContainsEngineeringPlaceholders() {
        val actors = listOf(
            ConflictInboxActor.RequiresDetail,
            ConflictInboxActor.Known("member-m", "妈妈"),
        )
        val tombstones = listOf(
            ConflictInboxBranchTombstone.RequiresDetail,
            ConflictInboxBranchTombstone.Known(true),
            ConflictInboxBranchTombstone.Known(false),
        )
        val medias = listOf(
            ConflictInboxMedia.RequiresDetail(0),
            ConflictInboxMedia.RequiresDetail(2),
            ConflictInboxMedia.Known(1),
        )
        val stables = listOf(true, false, null)

        for (actor in actors) {
            for (tombstone in tombstones) {
                for (media in medias) {
                    for (stable in stables) {
                        val line = conflictInboxCardMeta(
                            branchedItem(
                                actor = actor,
                                stableTombstone = stable,
                                branchTombstone = tombstone,
                                media = media,
                            ),
                        ).metaLine.orEmpty()
                        assertThat(line.contains("待加载")).isFalse()
                        assertThat(line.contains("Owner")).isFalse()
                    }
                }
            }
        }
    }

    private fun branchedItem(
        conflictId: String = "conflict-1",
        actor: ConflictInboxActor = ConflictInboxActor.Known("member-a", "爸爸"),
        stableTombstone: Boolean? = false,
        branchTombstone: ConflictInboxBranchTombstone = ConflictInboxBranchTombstone.Known(false),
        media: ConflictInboxMedia = ConflictInboxMedia.Known(1),
    ) = ConflictInboxItem(
        conflictId = conflictId,
        rootType = ConflictRootType.Baby,
        clientUuid = "baby-1",
        rootLabel = "宝宝资料",
        title = "豆豆",
        babyLabel = "豆豆",
        actor = actor,
        stableTombstone = stableTombstone,
        branchTombstone = branchTombstone,
        media = media,
        updatedAt = 100,
    )
}
