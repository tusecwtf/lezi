# 05: 跟手无上限、惯性跨多日、记录按视窗范围加载

**What to build:** 用力甩两下能从今天翻到三天前，中途不被截断；到达「现在」边界惯性自然停止并有
overscroll；翻过去的每一天都有记录可看。手势：`TimelineRailScrollSession` 去掉
`accumulated.coerceIn(-width, width)`，每帧把 delta 交给状态机，`onHorizontalPan: (TimelinePanGesture)
-> Float` 返回实际应用像素（夹紧时 0）；自定义 `FlingBehavior`（`exponentialDecay` 加大摩擦）。
状态机：`DragChanged(deltaPx, widthPx, nowMs)`，`translationMs = deltaPx / width × durationMs`，去掉
`coerceIn(-1.0, 1.0)` 与 `endDrag` 的 `origin±1` 夹紧（粘性规则本身无天数限制）。数据：
`TimelineWindowRequest` 的 `railStartMillis/railEndMillis` 改显式字段并纳入 `loadKey`；`LogViewModel`
把视窗量化为「覆盖的完整本地日 ±1 天」（`distinctUntilChanged`）合并进请求流；列表/汇总/计划仍按
`selectedDay`。

**Blocked by:** 03（新 endDrag 规则）, 04（任意范围 `LocalDayGrid` 与 ms 段，否则三天外无几何）

**Status:** implemented

- [x] `TimelineInteractionTest`：连续 delta 累计超过一个宽度后视窗继续后移；多日 fling 落在占比最大日（D−3）；到现在边界后 `consumedPx == 0`
- [x] `TimelineWindowRepositoryTest`：视窗在同一组本地日内拖动不重查；跨出 ±1 才重查
- [x] `TimelineExperienceDeviceTest`：fling 跨两天；到现在边界 fling 停止且无越界
- [x] `FlingBehavior` 常量集中一处并注明真机调参入口（07）
- [ ] 真机：翻到三天前记录可见

## Comments

2026-09-13 — ticket 05 implemented in worktree `lezi-log-home-timeline` (not committed).

**Gestures:** `TimelineRailScrollSession` passes each frame’s `delta` with no `coerceIn(-width, width)`. `onHorizontalPan: (TimelinePanGesture) -> Float` (`deltaPx` + `axisLengthPx`) returns consumed pixels; now-boundary clamp returns 0 so system fling stops and overscroll can run. Custom `FlingBehavior` in `designsystem/TimelineRailFling.kt` uses `exponentialDecay` with `TIMELINE_RAIL_FLING_FRICTION_MULTIPLIER = 2.5f` (higher than the `1f` default). Ticket 07 will tune on device to “one hard fling ≈ 2–3 days”. No hard travel cap.

**Reducer:** `DragChanged(deltaPx, widthPx, nowMs)` applies `translationMs = deltaPx / width × durationMs` to the current viewport (consecutive deltas keep moving). Removed `coerceIn(-1.0, 1.0)` and `endDrag` `origin±1`. Sticky rule itself has no day cap; only the now boundary still clamps. `TimelineInteractionResult.consumedPx` is the applied px.

**Data load:** `TimelineWindowRequest.railStartMillis/railEndMillis` are explicit constructor fields and part of `TimelineWindowLoadKey`. `timelineRailRange` = complete local days overlapped by the absolute viewport, plus ±1 civil-day buffer. `LogViewModel` maps `timelineInteractionState` through that helper, `distinctUntilChanged`, and merges the range into the observe request. List / summary / plans still use `selectedDay`. `buildTimelineLanes` clips to `snapshot.request.rail*`. Same covered-day set does not reload; leaving that set (and therefore its ±1 rail) does.

**Tests:** `./gradlew :feature:log:testDebugUnitTest --tests 'com.lezi.babylog.feature.log.timeline.TimelineInteractionTest' :domain:testDebugUnitTest --tests 'com.lezi.babylog.domain.timeline.TimelineWindowRepositoryTest' :designsystem:compileDebugKotlin :feature:log:compileDebugKotlin :app:compileDebugKotlin` — BUILD SUCCESSFUL. Also compiled `:designsystem:compileDebugAndroidTestKotlin` and `:feature:log:compileDebugAndroidTestKotlin`. Did **not** run `connectedAndroidTest`. Did **not** do 真机三天前确认. Did **not** commit.

**Kept for 06/07:** midnight month/day labels (06); on-device friction / `STICKY_DAY_SHARE` final values (07).
