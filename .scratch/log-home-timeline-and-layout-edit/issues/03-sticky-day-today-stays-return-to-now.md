# 03: 松手按粘性规则换日，今天不弹回，「回到现在」按钮

**What to build:** 在现有三日工作轴内先把 settle 规则换掉：今天 08:00 实时吸附时左拖 2h 松手，画面停在
松手处、TopBar 仍是今天、时间条下方出现「回到现在」；拖到今天在视窗中不足 1/4 时松手，TopBar 与
列表切到昨天；点「回到现在」回到 `[现在−24h, 现在]` 并继续跟随。`TimelineInteraction.endDrag`：
`reachedNowBoundary → reattachLive`；否则 `stickyDay(viewport, selectedDay, zone)`
（`share(selectedDay) ≥ STICKY_DAY_SHARE = 0.25` 保持，否则占比最大日，并列取较晚，≤ 日历今天）；
LiveAttached 起点未到现在 → `Browsing` 停在原地。新事件 `ReturnToNow(nowMs, zoneId)` →
`liveState(today)`。`LogTimelineList` 按钮在 `mode == Browsing` 时显示，选中日 ≠ 日历今天写
「返回今天」，= 今天写「回到现在」，点按发 `ReturnToNow` 并同步全局日期。本票保留 ±1 日夹紧与一个
宽度预算（04/05 再拆），换日回弹（R1）留给 04。

**Blocked by:** None (can start immediately)

**Status:** implemented

- [x] `TimelineInteractionTest`：08:00 实时左拖 2h 松手 → Browsing、今天、视窗不变；拖到右边界早于 06:00 → 昨天、视窗不变；历史 D 拖到 D−1 占 3/4 → D−1；到现在边界 → LiveAttached；`ReturnToNow` → LiveAttached `[now−24h, now]`；cancel 恢复原点；DST 73h 日 `share` 按真实时长
- [x] 删除旧用例 `livePastTrialReturnsToNowUntilRightEdgeCrossesTodayMidnight` 的弹回断言，替换为停留断言
- [x] `LogTimelineList` 按钮显隐/文案随 `timelineInteraction.mode` 与 `selectedDay`；device test：今天脱离吸附后出现「回到现在」，点按后回到吸附
- [ ] 真机：早晨小幅拖动不再弹回

## Comments

2026-09-13 — ticket 03 implemented in worktree `lezi-log-home-timeline` (not committed).

**Share helper:** `TimelineInteraction.dayShare` = overlap of the absolute viewport with that local day's real `[midnight, nextMidnight)` / viewport duration. DST days use `atStartOfDay(zone)` elapsed length (NY 2026-11-01 = 25h; three-day work window around it = 73h). Single constant `STICKY_DAY_SHARE = 0.25`. `endDrag` first `reattachLive`s on `reachedNowBoundary`; otherwise `stickyDay` (keep current if share ≥ 1/4, else max share / later tie / clamp ≤ today) then `origin±1`. Deleted LiveAttached + `viewport.end > today 00:00` → snap-back. `ReturnToNow(nowMs, zoneId)` reuses `reattachLive` (`CommitSelectedDay` only if the day changed). Cancel still restores origin (LiveAttached cancel → reattachLive).

**Button wiring:** `LogTimelineList` shows `LogTimelineReturnToNowButton` when `timelineMode == Browsing` (not `state.day != today`). Label is 「返回今天」 / 「回到现在」 from `state.day` vs calendar today; tag `log_timeline_return_to_now`. Click → `LogViewModel.returnToNow(nowMs)` then `onSelectedDayChange` + `onGoToday` when the committed day changed, same parent path as pan-end. LiveAttached hides the button.

**Kept for 04/05:** `changeDrag` `coerceIn(-1.0, 1.0)` one-width budget; `endDrag` `origin±1` clamp around the sticky result. Minutes→ms rail APIs untouched.

**Tests:** `./gradlew :feature:log:testDebugUnitTest --tests 'com.lezi.babylog.feature.log.timeline.TimelineInteractionTest'` and the rest of `com.lezi.babylog.feature.log.timeline.*` — BUILD SUCCESSFUL. `:feature:log:compileDebugAndroidTestKotlin` — BUILD SUCCESSFUL. `ReturnToNowButtonDeviceTest` hosts the button + `TimelineInteraction` (no Hilt): browsing today shows 「回到现在」, click returns to LiveAttached and hides the button. Did **not** run `connectedAndroidTest` (no device). Did **not** do 真机 morning-drag confirmation. Did **not** commit.
