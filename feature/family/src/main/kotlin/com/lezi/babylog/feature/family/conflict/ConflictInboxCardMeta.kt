package com.lezi.babylog.feature.family.conflict

import com.lezi.babylog.domain.carelog.ConflictInboxActor
import com.lezi.babylog.domain.carelog.ConflictInboxBranchTombstone
import com.lezi.babylog.domain.carelog.ConflictInboxItem
import com.lezi.babylog.domain.carelog.ConflictInboxKind
import com.lezi.babylog.domain.carelog.ConflictInboxMedia

/**
 * Tier B 文案常量（ui-copy-hardening 模式，JVM 测试锁定于 [ConflictInboxPhaseTest]）。
 * 术语红线（CONTEXT.md）：「待处理」仍是单一徽章 + 同一底栏口径；UI 不出现 "Owner"；
 * 不把「本机去掉」表述为家庭删除；不出现「待加载」这类工程词。
 */

/** 顶部固定心智解释：什么时候东西会出现在这里（不随相位变化）。 */
const val CONFLICT_INBOX_EXPLANATION =
    "当这台手机和家里对同一条内容有不同版本，或有内容还没在家里对齐时，待处理项会出现在这里。"

const val CONFLICT_INBOX_LOADING_TITLE = "正在获取待处理项"
const val CONFLICT_INBOX_LOADING_MESSAGE = "正在读取最新情况…"

const val CONFLICT_INBOX_EMPTY_TITLE = "目前没有待处理项"
const val CONFLICT_INBOX_EMPTY_MESSAGE = "有新的待处理项时会自动出现在这里"

const val CONFLICT_INBOX_ERROR_TITLE = "暂时拿不到待处理项"
const val CONFLICT_INBOX_ERROR_MESSAGE = "这台手机暂时读不到最新情况，可重试"
const val CONFLICT_INBOX_RETRY_ACTION = "重试"

/** 整卡详情元数据都未就绪时的占位词（取代「…待加载」）。 */
const val CONFLICT_INBOX_FETCHING_DETAILS = "正在获取详情…"

private const val CONFLICT_INBOX_META_SEPARATOR = " · "

/** 一张收件箱卡片的对外文案：可见元数据行 + 处理/审阅动词 + 读屏描述。 */
data class ConflictInboxCardMeta(
    /** 元数据行全文；null 表示该行整体不渲染。 */
    val metaLine: String?,
    val actionLabel: String,
    val onClickActionLabel: String,
) {
    /** 读屏描述沿用卡内同一份可见内容，只前置根与标题。 */
    fun contentDescription(rootLabel: String, title: String): String =
        listOfNotNull(rootLabel, title, metaLine).joinToString("，")
}

/**
 * 卡片元数据纪律（票 08）：未就绪的元数据行**不渲染该行**；详情元数据（提交者 /
 * 删除状态 / 照片）整体未就绪时给「正在获取详情…」。未对齐项没有详情可取，
 * 永远不算「获取中」。动词保持 424fc4a2 口径：未对齐 → 「处理」，冲突 → 「审阅」。
 */
internal fun conflictInboxCardMeta(item: ConflictInboxItem): ConflictInboxCardMeta {
    val unaligned = item.kind != ConflictInboxKind.Branched
    val baby = item.babyLabel?.let { "宝宝 $it" }
    val reason = item.reasonLabel
    // 未就绪的行不渲染——不再直出「删除状态待加载」「提交者详情待加载」。
    val actor = if (unaligned) {
        null
    } else {
        (item.actor as? ConflictInboxActor.Known)?.let { "提交者 ${it.label}" }
    }
    val branchTombstone = item.branchTombstone
    val deletion = if (unaligned) {
        null
    } else {
        when {
            item.stableTombstone == true -> "当前已删除"
            branchTombstone is ConflictInboxBranchTombstone.Known &&
                branchTombstone.hasCandidate -> "含删除候选"
            item.stableTombstone == false &&
                branchTombstone is ConflictInboxBranchTombstone.Known -> "当前保留"
            else -> null
        }
    }
    val media = if (unaligned) {
        null
    } else {
        (item.media as? ConflictInboxMedia.Known)?.let { "${it.totalCount}张照片" }
    }
    val fetchingDetails = !unaligned && actor == null && deletion == null && media == null
    val parts = listOfNotNull(baby, reason, actor, deletion, media)
    val metaLine = when {
        parts.isEmpty() -> if (fetchingDetails) CONFLICT_INBOX_FETCHING_DETAILS else null
        fetchingDetails ->
            parts.joinToString(CONFLICT_INBOX_META_SEPARATOR) +
                CONFLICT_INBOX_META_SEPARATOR + CONFLICT_INBOX_FETCHING_DETAILS
        else -> parts.joinToString(CONFLICT_INBOX_META_SEPARATOR)
    }
    return ConflictInboxCardMeta(
        metaLine = metaLine,
        actionLabel = if (unaligned) "处理" else "审阅",
        onClickActionLabel = if (unaligned) {
            "处理${item.rootLabel}"
        } else {
            "审阅${item.rootLabel}冲突"
        },
    )
}
