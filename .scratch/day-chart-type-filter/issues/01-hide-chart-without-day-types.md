# 01 — 无日图类型则隐藏时间条

**Parent:** [../spec.md](../spec.md)

**What to build:** 照护者在记录首页查看某自然日时：若当日没有任何**日图类型**护理记录（奶、母乳、睡眠、尿、便），则**不显示**一日时间条卡片；若至少有一类，则仍按现网三轨样式显示时间条。本票落地可 JVM 单测的**日图类型纯模型**（归类、是否出图、图例集合、筛选、选中归约），并在记录页**仅用「是否出图」**接线隐藏/显示。`filterRecords` / `toggleSelection` / `reconcileSelection` / `legendCategories` 本票以单测锁定语义、**可不接 UI**（02 接线）。点选筛选行为保持现网段级 tip，**不**提升页级 `selected`。

**Blocked by:** None — can start immediately

**Status:** done

## Acceptance criteria

- [x] 日图类型归类：奶 = 配方奶 + 瓶喂挤出乳；母乳；睡眠（含未结束）；尿（含尿+便）；便（含尿+便）；吸奶与其它类型不属日图类型（`categoriesOf` 为空）
- [x] 当日 0 条护理记录 → 不渲染一日时间条（列表空态/引导仍走现网，不因本票改动）
- [x] 当日仅有非日图类型（如体温、用药、吸奶）→ 不渲染一日时间条；吸奶不进「奶」
- [x] 当日至少一条日图类型记录（含仅未结束睡眠）→ 渲染时间条
- [x] 显示时仍走现网时间条卡片/三轨接线；本票不改轨布局、不改段级 tip/命中、不改明细过滤、不改图例交互、不把 selected 提升到页级
- [x] 纯模型对外行为可测：`categoriesOf`、`shouldShowDayChart`、`legendCategories`、`filterRecords`、`toggleSelection`、`reconcileSelection`（命名可调整，语义对齐 spec）；后四者本票 JVM 锁定即可，产品接线仅 `shouldShowDayChart`
- [x] `legendCategories` 顺序稳定（建议固定五类枚举序，仅保留当日出现者）
- [x] JVM 单测覆盖 spec Testing Decisions 主接缝用例（出图真假、五类归类、BOTH_DIAPER 双属、吸奶不入奶、筛选与选中归约等）
- [x] 无 Room / 同步 / 设置项变更；日汇总口径不变

## Comments

- 并行审查（2026-07-26）：approve-with-nits — 已补空日、负向范围、模型全量单测 vs 仅 shouldShow 接线。
- 2026-07-26 implement：domain `DayChartCategories` + `DayChartCategoriesTest`；`LogScreen` 仅以 `shouldShowDayChart` 条件渲染 `TimelineRailCard`。
