# 08 — 拆分 LogScreen 列表与弹窗宿主

**Parent:** [../spec.md](../spec.md)
**Audit ID:** `CR-20260730-P1-02`
**Severity:** P1 structural
**Status:** complete
**Activation:** satisfied — correctness 01/02 complete; user requested all scratch issues
**Size:** M–L

## What to build

延续已完成的 VM/Timeline/Dock 拆分，把约 1,362 行 `LogScreen.kt` 中的列表 cell/swipe 管理与
弹窗/管理 action host 移到专用单元。`LogRoute` 只负责订阅状态、导航和顶层编排；行为零变更。

## Acceptance criteria

- [x] `LogScreen.kt` 低于 1,000 行，route/list/dialog 三类职责有明确文件边界。
- [x] 时间线筛选、日期上下文、Composer、布局编辑、记录/计划查看编辑删除路径不变。
- [x] 管理动作权限、无障碍、失败反馈与 publish chrome 不分叉。
- [x] 扩展现有 `LogScreenStructureTest`，防止列表/弹窗主体回流。
- [x] feature/log 45 项设备基线或其 current 等价回归无失败。

## Validation

运行 `:feature:log:testDebugUnitTest`、`:feature:log:lintDebug`、相关 connected 测试与
`:app:assembleDebug`；记录前后行数和搬迁等价证据。

## Documentation gate

行为零变更不改 PRD；不得在结构搬迁中改变记录页 IA 或快捷坞契约。

## Evidence

- [validation.md](../evidence/08/validation.md)
