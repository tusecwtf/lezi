# 02 — Care Summary 7/30 日投影 → Summary

**Parent:** [../spec.md](../spec.md)

**What to build:** 在 Ticket 01 的 Care Summary module 内扩展滚动 7 日与 30 日事实，并迁移 Summary caller。日/周/月界面只呈现同一 module 产出的 totals、daily buckets、chart windows 与空状态。

**Blocked by:** [01-care-summary-day-log-tracer.md](./01-care-summary-day-log-tracer.md)

**Status:** done

**Dependency category:** in-process

## Seam and deletion test

- 扩展既有 interface 的 leverage，不创建第二个“图表专用聚合” seam。
- 图表 geometry、颜色和 Compose 状态留在 Summary implementation。
- 删除新 module 时，滚动窗口和日桶规则会回流到 Summary caller。

## Acceptance criteria

- [x] interface 测试覆盖 anchor date 对应的 1/7/30 日滚动窗口与日期顺序
- [x] 跨午夜及进行中睡眠在 7/30 日窗口内按 Ticket 01 同一语义裁剪
- [x] 喂养次数/容量/分钟、睡眠段数/时长、尿便、体温日值与现有可见口径一致
- [x] `SummaryViewModel` 消费 Care Summary 事实，不再自行按原始记录调用 `summarizeDays` / `summarizeDay`
- [x] 日/周/月切换、选定日期、图表数据、空态和 `showAvgSleep` 行为保持
- [x] feature 内旧滚动聚合 implementation 与只绑定其内部结构的重复测试被删除
- [x] Log 当日 tracer 仍通过，证明扩展未分叉日语义

## Validation

- [x] Targeted：Care Summary、feature:summary、feature:log 相关测试
- [x] Compile：feature:summary 与 app debug 编译
- [x] Static：`git diff --check`

## Out of scope

- Widget/CareLog 旧入口清理（Ticket 03）
- 新图表或 Summary 视觉调整
- payload reader 迁移（Ticket 06）

## Comments

- 在同一 `CareAggregation` 上扩展 `range`，复用逐日事实，没有创建图表专用聚合 seam；旧 Summary shallow 聚合已删除。
- 验证：Care Summary/Summary/Log 定向测试与 `./gradlew test assembleDebug -q`（exit 0）；`git diff --check` 通过。
- Documentation: N/A；呈现口径保持。
