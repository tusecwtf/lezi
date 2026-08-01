# 10 — Timer transition 覆盖全部非取消异常

**What to build:** `applyTransition` 对除 CancellationException 外的全部失败执行安全停服与内存 FAILED 收口；DataStore `IOException` 不能让 UI 停在 STARTING 而 FGS 继续运行。

**Source:** `AUDIT-20260801-P1-10`  
**Blocked by:** None — can start immediately  
**Status:** done  
**Size:** S–M

## Design notes (public seams)

1. `startTimerWithConfirmation` — STARTING persist / service ack / RUNNING publish; all non-cancel failures stop FGS once and settle FAILED (memory first if durable write fails).
2. `settleNonRunningTimerTransition` — pause/clear path: stop FGS even when DataStore `IOException`; keep FAILED retry identity when session data remains.
3. `publishTimerStateBestEffort` — durable publish with memory fallback; CancellationException rethrown after stop responsibility stays with caller.
4. `TimerState.toFailedRetryable` / `restoreTimerAfterStorageFault` — pure FAILED identity (accum, side, session, failure reason) shared by transition and init restore.
5. ViewModel `applyTransition` / `init` — wire the same cover; only rethrow CancellationException after stop.

## Acceptance criteria

- [x] 回归注入 STARTING persist、service ack 后 RUNNING persist 与 pause/clear persist 的 `IOException`。
- [x] 任一非取消失败都停止 FGS/通知，并把内存态收口为 FAILED（保留累计值、side、session 与可重试原因）。
- [x] FAILED 持久化再次失败时仍先更新 `_state`，UI 不会永久显示 STARTING/RUNNING 假象。
- [x] CancellationException 停服务后原样重抛；不能被产品错误吞掉。
- [x] service start RuntimeException、timeout、notification failure 与 DataStore failure 共享同一总覆盖策略，不产生重复 stop 竞态。
- [x] retry 从 FAILED/RECOVERABLE 使用稳定 session token，成功后才显示 RUNNING。
- [x] init restore 遇到坏存储/IO 的用户可见状态和停服策略与 transition 一致。

## Validation

运行 feature:timer 与 datastore 故障注入测试、`:app:assembleDebug`、`lintDebug`，并设备 smoke 撤销通知权限/强停服务后的恢复。

## Documentation Gate

更新 `docs/prd/tech.md` Timer 状态表，列出持久化失败也必须停服并可重试。

## Out of scope

不改变计时精度或引入后台自动重启。
