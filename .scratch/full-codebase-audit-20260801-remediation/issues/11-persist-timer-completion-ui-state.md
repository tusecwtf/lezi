# 11 — 计时完成态跨配置重建

**What to build:** 把 completion draft、Saving、结果/错误与 pending next-feed 放进 TimerViewModel 可观察状态；新 composition 订阅状态，不依赖旧 composition 捕获的一次性 lambda。

**Source:** `AUDIT-20260801-P1-11`  
**Blocked by:** 10 — transition failure state must be total first (done)  
**Status:** done  
**Size:** M

## Design notes (public seams)

1. `TimerCompletionUiState` — single observable completion stage: draft / saving / saveError / pendingNextFeed (blob) / pendingExit / completionClientUuid / sessionBabyId. Host reads only this; no Compose `remember` dual master.
2. Pure reducers — `canMutateSheet`, `openTimerCompletionSheet`, `updateTimerCompletionDraft`, `dismissTimerCompletionSheet`, `mayStartTimerCompletionSave`, `beginTimerCompletionSave`, `timerCompletionValidationFailed`, `timerCompletionSaveFailed`, `timerCompletionSucceeded`, `consumeTimerPendingExit`, `finishTimerNextFeedToExit`, `shouldResumeTimerCompletionSave`, `decideTimerCompletionResume`, `rehydrateTimerCompletionUi`.
3. `TimerCompletionSavedState` — SavedStateHandle adapter (Serializable draft blob + saving/error/exit + single `TimerPendingNextFeed` blob + durable submit identity). Legacy multi-key next-feed migrated on restore.
4. `TimerViewModel.completionUi` / `openCompletion` / `confirmCompletion` / `acknowledgeCompletionExit` — results published on state (not composition callbacks); in-flight re-confirm is no-op; process-death resume is idempotent on durable `completionClientUuid` (completion SavedState preferred over timer DataStore alone). Missing identity mid-save → fail-closed retryable sheet, never false-success exit.
5. `TimerRoute` — collects `completionUi` only; consumable exit via `LaunchedEffect(pendingExit)` (including after next-feed finish); next-feed from `pendingNextFeed`.

## Acceptance criteria

- [x] 完成 sheet 的 draft、saving/error 与提交身份由 VM/SavedState 驱动；旋转后 sheet 和 busy 状态准确恢复。
- [x] in-flight 时再次确认不会静默 return；新 UI 看到同一 Saving，不能创建第二个 coroutine/Record。
- [x] 首次完成结果由当前订阅者消费：成功进入 next-feed 或退出，失败回到可重试 sheet；旧 composition callback 不是唯一交付者。
- [x] 配置重建发生在 domain commit 前、commit 后 DataStore clear 前、clear 后回调前均能收敛。
- [x] 进程死亡后依靠稳定 `completionClientUuid` 幂等恢复，不重复 Record、candidate、照片或 next-feed offer。
- [x] 结果/event 使用可确认消费机制；重新订阅不重复导航或 Snackbar，未消费结果不会丢。
- [x] TimerRoute 的 `remember` completion state 不再与 VM 真相形成双主。

## Validation

补 ViewModel/Compose recreation tests，运行 feature:timer tests、`:app:assembleDebug`、`lintDebug`，并 API 35 设备旋转完成 sheet。

## Documentation Gate

若状态机新增阶段，更新 Timer UI/技术状态图。

## Out of scope

不改变下次喂养业务状态机本身。
