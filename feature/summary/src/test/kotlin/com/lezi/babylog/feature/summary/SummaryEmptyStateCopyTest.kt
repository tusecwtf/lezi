package com.lezi.babylog.feature.summary

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 票 09（T2）：汇总空周期空态卡文案的 Tier B 锁定（ui-copy-hardening 模式，
 * FamilyErrorCopyTest 先例）。只锁对外可见文案与文案纪律，不测私有实现。
 */
class SummaryEmptyStateCopyTest {
    @Test
    fun emptyPeriodCardStatesTheEmptyRangeDirectly() {
        assertThat(SUMMARY_EMPTY_PERIOD_TITLE).isEqualTo("这个范围还没有记录")
        assertThat(SUMMARY_EMPTY_PERIOD_MESSAGE)
            .isEqualTo("这段时间没有护理记录。去底栏「记录」页记一条，这里就会有汇总。")
    }

    @Test
    fun emptyPeriodCopyPointsToLogTabWithoutJumpsOrEngineeringJargon() {
        // 文案指向底栏「记录」页，但只是文字指引——票面不做跨页跳转按钮。
        assertThat(SUMMARY_EMPTY_PERIOD_MESSAGE).contains("底栏")
        assertThat(SUMMARY_EMPTY_PERIOD_MESSAGE).contains("「记录」")
        // 术语红线（CONTEXT.md）：UI 不出现 "Owner"、不出现「待加载」类工程词，
        // 空态卡也不替同步状态说话（同步状态由页内 shallow 状态行单独承担）。
        listOf(SUMMARY_EMPTY_PERIOD_TITLE, SUMMARY_EMPTY_PERIOD_MESSAGE).forEach { copy ->
            assertThat(copy).doesNotContain("Owner")
            assertThat(copy).doesNotContain("待加载")
            assertThat(copy).doesNotContain("同步")
            assertThat(copy).doesNotContain("null")
        }
    }
}
