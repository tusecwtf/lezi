# 01 — 中断恢复后对齐下次喂养持久化真相

**Parent:** [../spec.md](../spec.md)
**Audit ID:** `CR-20260730-P0-01`
**Severity:** P0
**Status:** complete
**Blocked by:** none
**Size:** L

## What to build

为 Composer 与 Nursing Timer 的共享 next-feed 流程增加“持久化真相 reconciliation”。当
`scheduleNextFeedCarePlan` 可能已提交、但 UI 成功回调因取消或进程重建丢失时，恢复态必须先
查询该宝宝的开放 next-feed marker plan，再决定显示已安排、可重试或确实未安排。

纯 reducer 不直接访问数据库；通过共享事件/effect 和同一 domain seam 编排。不得在状态仍
歧义时允许 `Skip → FinishWithoutPlan`。

## Acceptance criteria

- [x] 先新增失败回归：CarePlan 已提交、成功回调未送达、保存态从 `Scheduling` 恢复。
- [x] 恢复后的歧义阶段禁用“不安排”和重复完成；必须先完成持久化查询。
- [x] 若存在开放 next-feed marker plan，UI 收敛为 truthful scheduled 结果，不输出“未安排”。
- [x] 若确认不存在开放 marker，才进入可重试/可跳过状态；Skip 后数据库确实没有开放 marker。
- [x] 产品不提供“取消已找到 marker 再 Skip”；Found 始终收敛为 scheduled，避免新增同步 mutation/权限失败面。
- [x] Composer 与 Timer 共用同一 reconciliation contract，无复制查询或不同文案结果。
- [x] 重试保持稳定计划身份，不产生两个开放 next-feed plans；现有离线 winner 规则不变。
- [x] `CancellationException` 原样传播；回调丢失、配置重建和进程恢复均有测试。

## Primary seams

- `core/model/.../NextFeedPlanFlow.kt`
- `designsystem/.../NextFeedPlanFlow.kt`
- `domain/.../CareLog.kt`
- `feature/log/.../RecordComposer.kt`
- `feature/timer/.../TimerViewModel.kt`、`TimerScreen.kt`

## Validation

- RED/GREEN：`NextFeedPlanFlowTest` 增加 ambiguous restore/reconcile/Skip 矩阵。
- Domain 测试覆盖已存在、确实不存在、不可管理家庭计划及幂等 retry。
- Composer/Timer adapter 测试证明相同输入得到相同 durable 结果。
- Compose 或 connected 测试覆盖保存态恢复期间按钮不可跳过，以及 reconciliation 后文案。
- 运行 `:core:model:test`、`:domain:testDebugUnitTest`、`:designsystem:testDebugUnitTest`、
  `:feature:log:testDebugUnitTest`、`:feature:timer:testDebugUnitTest`、相关 lint 和
  `:app:assembleDebug`。

## Documentation gate

核对 PRD 中“计划不是事实”“不安排不残留计划”与进程恢复文案；若新增 reconciliation 状态，
同步更新 UI/数据模型说明。不得把一次 UI 回调当成持久化回执。

## Out of scope

- 改变普通 CarePlan 履行或 next-feed 家庭冲突 winner 规则。
- 借修复拆分 RecordComposer/CareLog 大文件；结构工作属于 07/09。

## Completion evidence

- [validation.md](../evidence/01/validation.md) 保留逐 slice RED、完整 JVM/lint/assemble 与 API 35
  保存态恢复设备回执。
- 关闭只覆盖持久化真相 reconciliation；未改变普通 CarePlan 履行、离线 winner、四槽布局或
  Composer 确认后落库边界。
