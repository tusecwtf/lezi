# 09 — 拆分 RecordComposer state、ViewModel 与 UI

**Parent:** [../spec.md](../spec.md)
**Audit ID:** `CR-20260730-P1-02`
**Severity:** P1 structural
**Status:** complete
**Blocked by:** none
**Activation:** satisfied — 01 complete; user requested all scratch issues
**Size:** L

## What to build

把约 1,314 行 `RecordComposer.kt` 拆为 request/saved-state、ViewModel 写协调、Modal/Host UI 与
完成文案 helper。保留唯一 `RecordComposerHost` 和 `RecordComposerSessionGate`；所有入口继续
走预填草稿→用户确认→持久化，不恢复平行编辑路径。

## Acceptance criteria

- [x] 原文件与每个新职责文件均低于 1,000 行；ViewModel 与 Compose UI 不再同文件堆叠。
- [x] 01 的 next-feed durable reconciliation 完整保留，Composer 与 Timer 继续共用策略。
- [x] add/edit/fulfill/convert、照片生命周期、脏草稿放弃和 stale async session gate 行为不变。
- [x] `RecordComposerRequest`、`RecordComposerHost` 等现有 seam 的调用语义不变。
- [x] 新增结构测试防止 ViewModel、saved state 或 UI 主体回流单文件。

## Validation

运行 feature/log Composer/照片/放弃/interval/next-feed 全量单测、相关 connected Compose 测试、
`:feature:log:lintDebug` 与 `:app:assembleDebug`；记录文件行数与入口等价证据。

## Documentation gate

保持统一 Composer、confirm-before-persist、CarePlan/Record 事实边界和共享 ClockDial；结构票不改
交互或文案契约。

## Evidence

- [validation.md](../evidence/09/validation.md)
