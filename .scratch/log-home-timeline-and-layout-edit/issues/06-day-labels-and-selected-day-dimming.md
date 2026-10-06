# 06: 日界日期标签与选中日以外变浅

**What to build:** 在多日之间浏览时，每条午夜日界线顶端有极小「月/日」标签（如 `9/11`），选中日以外的
标签与记录标记一律变浅，让用户不看 TopBar 也知道在看哪天、列表属于哪天；「现在」线仍无字。
变浅范围 = `TimelineRailFrame.primaryRangeMs`（选中日自然日），随 03 的粘性提交更新，轨道不位移。

**Blocked by:** 04（绝对 ms 日界与 `primaryRangeMs` 已在轨道 API 中）

**Status:** implemented

- [x] `TimelineLane` / `TimelineHourLabels` 绘制日界标签（Micro 字号，随 elder 放大，不与小时刻度重叠——重叠时隐藏刻度而非标签）
- [x] 标签与标记按 `primaryRangeMs` 变浅；warm / journal 两模板一致
- [x] `ComponentPreviews` 增加跨两日预览；Elder device test 标签可读
- [ ] 真机截图：今天 08:00 实时视窗显示「昨天 月/日」标签在左侧日界

## Comments

2026-09-13 — ticket 06 implemented in worktree `lezi-log-home-timeline` (not committed).

**Labels:** feature-owned `M/d` (`8/8`, `9/11`) via `LocalDayGrid.monthDayLabel` in the grid `ZoneId` — the civil day that **starts** at that midnight. `TimelineRailFrame.dayBoundaryLabels: List<Pair<Long, String>>` is passed through `LogTimelineList` into `TimelineRailCard`. designsystem stays calendar-free (preformatted strings only). Midnight lines stay on `TimelineLane`; date text lives in `TimelineHourLabels` so it can win space against hour ticks. The 「现在」 line is still unlabeled.

**Overlap:** `placeTimelineAxisLabels` places day labels first, then hour ticks. A tick whose box would sit closer than `minGapPx` to a day label (or another tick) is hidden; the date stays. Same Micro style as ticks; elder uses `LeziThemeExt.typography.Micro` (fontScale / density).

**Dimming:** marks already used `TimelineWindowGeometry.isPrimaryInstant` / `primaryRangeMs` (ticket 04). Day labels use the same half-open selected-day range at `NEIGHBOR_DAY_LABEL_ALPHA = 0.42f`. Empty `primaryRangeMs` still dims nobody. Warm and journal share the rail; chrome only.

**Previews / tests:** `PreviewTimelineTwoDayCrossing` (+ journal). `ElderModeDayLabelDeviceTest` asserts the date exists, has a readable box, and hides the midnight hour tick (written; not connected). JVM: `TimelineHourLabelLayoutTest` overlap, `LocalDayGridTest` `M/d`, `TimelineExperienceProjectionTest` frame labels + selected-day keeps labels.

**Tests:** `./gradlew :designsystem:testDebugUnitTest --tests 'com.lezi.babylog.designsystem.Timeline*' :feature:log:testDebugUnitTest --tests 'com.lezi.babylog.feature.log.timeline.TimelineExperienceProjectionTest' :designsystem:compileDebugKotlin :feature:log:compileDebugKotlin :app:compileDebugKotlin` — BUILD SUCCESSFUL. Also `:feature:log:testDebugUnitTest --tests '…LocalDayGridTest'` and `:designsystem:compileDebugAndroidTestKotlin` / `:feature:log:compileDebugAndroidTestKotlin` — BUILD SUCCESSFUL. Did **not** run `connectedAndroidTest`. Did **not** do 真机截图. Did **not** commit. Did **not** touch fling / load-key (05).

