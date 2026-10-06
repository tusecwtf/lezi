package com.lezi.babylog.feature.export

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 票 10（T3）：导出进度/空态/终态文案的 Tier B 锁定（ui-copy-hardening 模式，
 * SummaryEmptyStateCopyTest 先例）。只锁对外可见文案与文案纪律，不测私有实现。
 */
class ExportFeedbackCopyTest {

    @Test
    fun generationProgressNoteMatchesTheTicketWording() {
        assertThat(EXPORT_GENERATION_NOTE).isEqualTo("正在生成，最长约半分钟")
    }

    @Test
    fun emptyRangeCopyStatesTheEmptyRangeDirectly() {
        assertThat(EXPORT_EMPTY_RANGE_TITLE).isEqualTo("这个范围还没有记录")
        assertThat(EXPORT_EMPTY_RANGE_MESSAGE)
            .isEqualTo("这段时间没有护理记录，没有可导出的内容；换个日期范围再试。")
    }

    @Test
    fun successTerminalNamesTheFileAndTheAgeBasedRetention() {
        assertThat(EXPORT_GENERATED_SNACKBAR).isEqualTo("已生成 · 可分享；文件保留约一天，之后自动清理")
        assertThat(EXPORT_GENERATED_SNACKBAR).contains("自动清理")
    }

    @Test
    fun cancelledShareStillTellsParentsTheFileIsAvailable() {
        assertThat(EXPORT_SHARE_CANCELLED_SNACKBAR).isEqualTo("文件已生成，可在本页再次分享")
        assertThat(EXPORT_SHARE_CANCELLED_SNACKBAR).contains("再次分享")
    }

    @Test
    fun unrecognizedFailureFallbackKeepsRecordsUnaffectedAndOffersRetry() {
        assertThat(EXPORT_UNRECOGNIZED_FAILURE_FALLBACK)
            .isEqualTo("导出没有完成，护理记录不受影响，可以再试一次")
    }

    @Test
    fun copyDisciplineAvoidsEngineeringJargonAndForbiddenTerms() {
        listOf(
            EXPORT_EMPTY_RANGE_TITLE,
            EXPORT_EMPTY_RANGE_MESSAGE,
            EXPORT_GENERATION_NOTE,
            EXPORT_GENERATED_SNACKBAR,
            EXPORT_SHARE_CANCELLED_SNACKBAR,
            EXPORT_UNRECOGNIZED_FAILURE_FALLBACK,
        ).forEach { copy ->
            assertThat(copy).doesNotContain("Owner")
            assertThat(copy).doesNotContain("待加载")
            assertThat(copy).doesNotContain("同步")
            assertThat(copy).doesNotContain("null")
            assertThat(copy).doesNotContain("Exception")
        }
    }
}
