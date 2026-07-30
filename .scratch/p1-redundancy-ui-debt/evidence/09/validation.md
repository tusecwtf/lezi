# Ticket 09 validation — 时间条与记录语义色单源

Date: 2026-07-30 (Asia/Shanghai)
Implementation base: `9e8f5e4c3e182e22787a8cb48311e105b6592e49`

## Live gap

- `buildTimelineLanes` 在历史提交 `b91601c` 已从硬编码 ARGB 改为 `Color.Unspecified`，
  但 `LogScreen` 仍在绘制前按 `laneSleep` / `laneFeed` / `laneCare` / `sun` 再次 copy。
- 图例使用同一旧 lane palette；记录行、摘要条、目录与快捷类型则使用
  `RecordType.presentation.colorRole -> leziRecordColor`。因此 warm / journal 与 light / dark
  仍有两套语义色来源。

## TDD evidence

### RED 1 — one pure record palette

Command:

```text
./gradlew :designsystem:testDebugUnitTest \
  --tests com.lezi.babylog.designsystem.RecordSemanticColorPolicyTest --no-daemon
```

Result: valid compile RED because `resolveLeziRecordColor` did not exist
(`BUILD FAILED in 7s`, 15 tasks). Minimal GREEN extracted the existing light/dark literals behind
that pure resolver without changing values; the same command passed (`8s`, 17 tasks).

### RED 2 — every theme delegates lane aliases

The next policy assertion covered warm / journal crossed with light / dark. A concurrent combined
gate supplied the valid behavioral RED: 43 tests, 1 failure at the lane-alias assertion. Minimal
GREEN changed only `LeziExtendedColors.laneSleep/laneFeed/laneCare` construction to delegate to
Sleep / Milk / Pee roles. The targeted policy test then passed (`8s`, 17 tasks).

### RED 3 — lanes carry semantic roles

Command:

```text
./gradlew :feature:log:testDebugUnitTest \
  --tests com.lezi.babylog.feature.log.BuildTimelineLanesTest --no-daemon
```

Result: valid compile RED because `TimelineLaneSegment.colorRole` did not exist
(`BUILD FAILED in 7s`, 101 tasks). Minimal GREEN changed lane/legend data to semantic roles,
resolved roles in designsystem, assigned roles in the builder, and removed LogScreen recoloring.
The same test passed (`12s`, 105 tasks).

## Behavior retained

- Formula/Milk, Nursing, Sleep, Pee and Poop legend roles match their timeline and record roles.
- BOTH_DIAPER remains two marks with Pee and Poop roles and shared filter identity behavior.
- Selected marks keep the existing primary focus ring; non-selected marks retain selection dimming;
  neighbor-day dimming, tap toggle/clear and A2 day-D commit gating are unchanged.
- Marker clustering and hit testing still sort stably by semantic role rather than raw color value.

## Final gates

```text
./gradlew :designsystem:testDebugUnitTest --no-daemon
BUILD SUCCESSFUL in 5s
17 actionable tasks: 2 executed, 15 up-to-date
```

```text
./gradlew :feature:log:testDebugUnitTest \
  --tests com.lezi.babylog.feature.log.BuildTimelineLanesTest \
  --tests com.lezi.babylog.feature.log.DayChartFilterWiringTest --no-daemon
BUILD SUCCESSFUL in 6s
105 actionable tasks: 4 executed, 101 up-to-date
```

```text
./gradlew :designsystem:lintDebug :feature:log:lintDebug :app:assembleDebug --no-daemon
BUILD SUCCESSFUL in 33s
573 actionable tasks: 70 executed, 503 up-to-date
```

Root 在完整工作树上额外复跑全量 designsystem / feature-log 单测与相同 lint/assemble 门：

```text
./gradlew :designsystem:testDebugUnitTest :feature:log:testDebugUnitTest \
  :designsystem:lintDebug :feature:log:lintDebug :app:assembleDebug
BUILD SUCCESSFUL in 1s
586 actionable tasks: 3 executed, 583 up-to-date
```

## Scope limits

- No device test was required: selection geometry and filter state were unchanged and remain covered
  by JVM tests; this ticket changes the color authority feeding those paths.
- No changes to Layout10 haptics, Program WIP, `docs/prd/ui.md`, sleep moon/sun decoration, or version.
