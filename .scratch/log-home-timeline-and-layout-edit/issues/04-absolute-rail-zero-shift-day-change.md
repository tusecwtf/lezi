# 04: 轨道以绝对瞬时渲染，换日时像素零位移

**What to build:** 从今天拖到昨天松手、TopBar 切到昨天时，时间条一个像素都不动；只有回到实时吸附才
settle。designsystem 时间轴组件（`TimelineRailCard` / `TimelineLane` / `TimelineHourLabels` /
`TimelineMarkerLayout` / 命中）全部改为绝对 epoch ms：视窗 `viewportStartMs/viewportDurationMs`、
段 `startMs/endMs/uncertainFromMs`、`dayBoundariesMs`、`hourTicks: List<Pair<Long,String>>`、`nowMs`、
`primaryRangeMs`（变浅范围）；像素 = `(t − start).toFloat() / duration × width`。
`rememberSettledViewportStartMinutes` → 只对「绘制视窗 − 目标视窗」差值（ms → Float）做 spring，
滚动中 snap。feature 侧 `TimelinePresentation` → `TimelineRailFrame`（由视窗 ± 少量边距用 `ZoneId`
规则算日界/刻度/现在/选中日范围），`ThreeDayTimelineAxis` → 按任意绝对范围构造的 `LocalDayGrid`
（本地午夜列表、DST 感知 `hourTicks`、`clipInterval`），`buildTimelineLanes` 输入裁剪范围(ms) 输出
ms 段。删除 `TimelineAxis` 三日常量、`NEIGHBOR_PEEK_MINUTES`、`panViewportStart`、
`clampViewportStart` 与 `TimelineWindowGeometry` 三日不变量；保留 `instantToAxisPx / axisPxToInstant`。
调用面（`LogTimelineList`、`ComponentPreviews`、Elder/DST device tests、`TimelineAxisTest`、
`TimelineMarkerLayoutTest`）一次切换。数据加载仍按 selectedDay ±1（05 再拆）。

**Blocked by:** 03（同一 `LogTimelineList` / reducer 接线，先落 settle 规则避免冲突）

**Status:** implemented

- [x] `TimelineExperienceDeviceTest`：拖到换日 → 提交前后 `drawnViewportStart`（绝对 ms）相同；lanes 异步刷新期间不出现整日位移
- [x] `LocalDayGridTest` 承接 `ThreeDayTimelineAxisTest` / `DstThreeDayTimelineAxisTest`：71h/73h、DST 空洞无刻度、重复本地时间双刻度带偏移
- [x] `BuildTimelineLanesTest`、`TimelineExperienceProjectionTest`（→ frame）改 ms；跨午夜睡眠不在午夜切断
- [x] designsystem `TimelineAxisTest` / `TimelineMarkerLayoutTest` 改 ms；`ComponentPreviews` 可渲染
- [ ] `./gradlew test` 与 `lintDebug` 绿；真机：换日不再回弹

## Comments

2026-09-13 — ticket 04 implemented in worktree `lezi-log-home-timeline` (not committed).

**New types:** `TimelineRailFrame` (`viewportStartMs` / `viewportDurationMs` / `dayBoundariesMs` / `hourTicks` / `nowMs` / `primaryRangeMs`) from `TimelineInteractionState.toTimelineRailFrame`. `LocalDayGrid(startMs, endExclusiveMs, zoneId)` owns covering midnights, DST `hourTicks`, and `clipInterval` in absolute ms. `TimelineLaneSegment` is `startMs` / `endMs` / `uncertainFromMs`. Thin `TimelineWindowGeometry` keeps only sorted unique `dayBoundariesMs` + `primaryRangeMs` (empty range = no dimming). Pixel helpers: `TimelineAxis.instantToAxisPx` / `axisPxToInstant`. Test hook: `TimelineRailCard.onDrawnViewportStart`.

**Settle rule:** `rememberSettledViewportStartMs` stores only drawn−target delta as Float ms. Scroll snaps delta to 0. Target is an absolute instant, so changing `selectedDay` does not change the target and does not sweep. Minute live-follow ticks (`≤ LIVE_FOLLOW_SNAP_MS` = 2 min) snap; only a large jump (ReturnToNow / live reattach / calendar reset) springs. Frame rebuilds from the absolute viewport ± 3h; `primaryRangeMs` is the selected day's real `[midnight, nextMidnight)`. `LogViewModel` still clips/loads rail records by selectedDay ±1. Device zero-shift case is live 08:00 + 3h drag (day changes, viewport is not the new natural day).

**Kept for 05/06:** one-width fling clamp (`coerceIn(-width, width)` + reducer `coerceIn(-1, 1)` / `origin±1`); no midnight month/day labels.

**Tests:** `./gradlew :designsystem:testDebugUnitTest :feature:log:testDebugUnitTest --tests 'com.lezi.babylog.feature.log.timeline.*' --tests 'com.lezi.babylog.designsystem.Timeline*'` — BUILD SUCCESSFUL. Main + androidTest compiles (`:designsystem` / `:feature:log` / `:app:compileDebugKotlin`, `:designsystem:compileDebugAndroidTestKotlin`, `:feature:log:compileDebugAndroidTestKotlin`, plus `:app:compileDebugAndroidTestKotlin` for the elder aspect-ratio host). Did **not** run `connectedAndroidTest` or full `./gradlew test` / `lintDebug`. Did **not** do 真机换日确认. Did **not** commit.
