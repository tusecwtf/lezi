# 03 — Care Summary Widget 迁移与旧聚合清理

**Parent:** [../spec.md](../spec.md)

**What to build:** 完成 Care Summary epic：迁移 Widget/CareLog 的日、周与最近摘要 caller，证明 Log、Summary、Widget 口径一致，然后删除旧 `aggregateDaily`、`aggregateWeek` 和重复聚合入口。

**Blocked by:** [02-care-summary-rolling-summary.md](./02-care-summary-rolling-summary.md)

**Status:** done

**Dependency category:** in-process；查询如留在 implementation，可使用现有 local adapter

## Seam and deletion test

- Care Summary interface 成为三个 caller 的唯一 test surface。
- CareLog 可负责记录查询，但不得继续暴露另一套汇总语义。
- 删除 Care Summary module 时复杂度应回流到 Log、Summary、Widget；删除旧 helper 后复杂度不应消失。

## Acceptance criteria

- [x] Widget/recent summary 通过 Care Summary interface 得到喂养、睡眠、尿便与最近记录事实
- [x] CareLog 的 day/week/recent 入口若保留，只做新 module 的窄委托，不包含平行聚合 implementation
- [x] 固定同一宝宝、窗口、时区和 `now` 时，Log、Summary、Widget 关键数字一致
- [x] 多宝宝隔离、软删、跨午夜与进行中睡眠回归通过
- [x] 旧 `aggregateDaily`、`aggregateWeek`、旧 Summary 聚合及失去 caller 的测试被删除
- [x] 仓库中不存在可由 caller 选择的第二套 Care Summary 路径
- [x] Widget 隐私文案、Summary 图表和 Log 快记无可见回归

## Validation

- [x] Targeted：domain、feature:log、feature:summary、feature:widget
- [x] Full closure：`./gradlew test assembleDebug`
- [x] Static：`git diff --check`
- [x] Smoke：同一跨夜/进行中睡眠在 Log、Summary、Widget 口径一致

## Out of scope

- payload schema 与原始 JSON reader 迁移
- 新 Widget 功能或 Summary 视觉

## Comments

- Widget 经 CareLog 窄委托消费 `CareAggregation.widget`；删除 `DailySummary.kt`、`WeekSummary.kt` 及旧聚合入口，跨 caller 一致性由自动化 tracer 覆盖。
- 验证：domain/log/summary/widget 定向测试；`./gradlew test assembleDebug -q`（exit 0）；`git diff --check` 通过。设备 smoke 未运行。
- Documentation: N/A；Widget 隐私文案和可见行为未改。
