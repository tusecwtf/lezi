package com.lezi.babylog.feature.summary

/**
 * Tier B 文案常量（ui-copy-hardening 模式，JVM 测试锁定于 [SummaryEmptyStateCopyTest]）。
 * 票 09（T2）：空周期/空范围时图表区渲染空态卡（StateContainer(Empty)）；
 * KPI 区维持现状；不做跨页跳转按钮。
 * 术语红线（CONTEXT.md）：对家长用口语「这个范围」指代所选汇总周期；指向底栏
 * 「记录」页；UI 不出现 "Owner"，不出现「待加载」类工程词。
 */

/** 空态卡标题：直说所选范围没有记录（用户故事口径）。 */
const val SUMMARY_EMPTY_PERIOD_TITLE = "这个范围还没有记录"

/** 空态卡正文：说明该范围无记录，并指向底栏「记录」页（文字指引，无跳转按钮）。 */
const val SUMMARY_EMPTY_PERIOD_MESSAGE =
    "这段时间没有护理记录。去底栏「记录」页记一条，这里就会有汇总。"
