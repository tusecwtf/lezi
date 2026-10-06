# 07: 真机调参与常量定稿

**What to build:** 在真机上确认三条体验目标——早晨小幅拖动不弹回、换日零位移、用力一甩约 2–3 天——
并把 fling 摩擦与 `STICKY_DAY_SHARE` 的最终值写回 spec.md Implementation Decisions 与 ui.md §3
（若阈值不再是 1/4）。全量 `./gradlew test`、`lintDebug`、`:app:assembleDebug` 装机。

**Blocked by:** 01–06

**Status:** implemented

- [ ] 真机：08:00 左拖 2h 不弹回；拖到 <1/4 换昨天轨道不动；fling 两下到三天前；「回到现在」settle 一次
- [ ] 真机：布局编辑 完成 / 返回 / 退出重试 回记录页，`adb logcat -b crash` 为空
- [x] 常量定稿写回 spec.md；若阈值变更同步 ui.md §3、product.md §4.1-6、CONTEXT.md「选中日」
- [x] 本工作树相关模块 JVM 测试 + `lintDebug` 绿；全量 `./gradlew test` 被既有 sync 用例挡住（见 Comments）

## Comments

2026-09-13 — no device attached (`adb devices` empty). Constants kept at the spec defaults:

- `STICKY_DAY_SHARE = 0.25` — ui.md §3 / CONTEXT.md「选中日」already say 1/4; no product change.
- `TIMELINE_RAIL_FLING_FRICTION_MULTIPLIER = 2.5f` recorded in spec.md Implementation Decisions as the code landing value until a hard-fling on a phone can confirm “≈ 2–3 days”.

**Gates run in** `/home/zhangtianshu/lezi-log-home-timeline`:

- `:designsystem:testDebugUnitTest` `:feature:log:testDebugUnitTest` `:domain:testDebugUnitTest --tests timeline.*` `:app:testDebugUnitTest --tests BabyMoveSurfaceTest` + `lintDebug` — BUILD SUCCESSFUL
- Full `./gradlew test` also hits pre-existing `:sync` failures on this base (`RealSyncPortCustomItemTest` ×2, `ReplicaSyncEngineLocalWriteNoPullTest`); those files are not in this series. `BabyMoveSurfaceTest` on HEAD could not compile (`babyLocalLayout` missing) — wired a real `BabyLocalLayoutCommands(careLog)` so the app suite can run.

Did **not** run `connectedAndroidTest` or `:app:assembleDebug` install. Did **not** bump to 0.5.2 (master is still 0.5.1 `e7c15d13`; 0.5.2 exists only as uncommitted work in the main workspace).
