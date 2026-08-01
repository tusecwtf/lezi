# 12 — Composer 保存后恢复下次喂养 offer

**What to build:** 把已保存事实后的 message、suggested time 与 pending identity 统一存入 RecordComposerViewModel/SavedState，并让任意新 composition 在关闭根 request 后继续呈现/消费 offer。

**Source:** `AUDIT-20260801-P1-12`  
**Blocked by:** None — can start immediately  
**Status:** done  
**Size:** M

## Design notes (public seams)

1. `RecordComposerSavedState.pendingNextFeed()` — durable offer blob: baby/type + suggestedAt + factMessage; survives `clear()` of restorable request/draft.
2. `composerPostSaveOutcome` / `applyComposerPostSaveOutcome` — pure post-write stage: publish offer or finish, consume restorable request immediately after fact success.
3. `RecordComposerUiState.pendingNextFeedOffer` / `pendingFinishMessage` — observable stages any composition reads; `recordComposerClosedUiState` preserves them across sheet close.
4. `consumeComposerPostSavePresentation` — Host: always consume root when post-save present; present finish only when offer is gone; acknowledge finish to avoid re-fire.
5. Shared `LeziNextFeedPlanFlow` phase restore (schedule/reconcile/Skip) unchanged.

## Acceptance criteria

- [x] 保存开始时旋转，旧 composition 回调晚到后，新 composition 仍显示一次正确的 next-feed flow。
- [x] pending 状态同时保存 fact message、baby/type、suggested time 与流程阶段；不再由两个 `rememberSaveable` 和 VM identity 分别做双主。
- [x] 成功写事实后立即消费 restorable Composer request，进程重建不能重复写事实；pending offer 独立保留到明确完成/不安排。
- [x] 当前 composition 通过 observable state/可确认 event 消费结果，不依赖旧 closure 改 Compose local state。
- [x] schedule 成功响应丢失、reconcile、Skip 与配置重建继续遵守共享 NextFeedPlanFlow 不变量。
- [x] 非喂养事实保存仍关闭并显示一次结果；喂养履行既有 plan 时不错误再 offer。
- [x] 回归覆盖旋转在 domain commit 前后、process recreation、Snackbar/导航不重复。

## Validation

运行 feature:log SavedState/ViewModel/Compose tests、`:app:assembleDebug`、`lintDebug`，并设备旋转 smoke。

## Documentation Gate

更新 Composer/next-feed 恢复顺序说明，明确“先事实成功，再可恢复地选择计划”。

## Out of scope

不改变 next-feed 建议间隔或系统日历投影策略。
