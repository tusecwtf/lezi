# Ticket 07 validation — LogScreen 结构拆分

Date: 2026-07-30 (Asia/Shanghai)
Implementation base: `5c5e2e131e90a87396ba17fad797a1d6c0da7c5a`

## TDD RED / GREEN

新增 `LogScreenStructureTest.routeTimelineDockAndStateHaveDedicatedUnits`，先要求独立的
`LogViewModel.kt`、`LogTimeline.kt`、`LogQuickDock.kt`，并锁定四个职责入口只出现一次。

RED：

```text
./gradlew :feature:log:testDebugUnitTest \
  --tests com.lezi.babylog.feature.log.LogScreenStructureTest

java.io.FileNotFoundException: .../LogTimeline.kt (No such file or directory)
BUILD FAILED
```

GREEN：

```text
./gradlew :feature:log:testDebugUnitTest \
  --tests com.lezi.babylog.feature.log.LogScreenStructureTest

BUILD SUCCESSFUL in 4s
105 actionable tasks
```

## Structural and equivalence evidence

- 原 `LogScreen.kt`：2366 行。
- 新 `LogScreen.kt`：1362 行，只保留 route、列表与弹窗宿主编排。
- `LogViewModel.kt`：375 行；`LogTimeline.kt`：313 行；`LogQuickDock.kt`：430 行。
- ViewModel 搬迁主体新旧 SHA-256 同为
  `ae4e02ae1fb77516232d1096d5cc6ca42b4c00f2460044529f0cc31b79ee639c`；timeline
  搬迁主体新旧 SHA-256 同为
  `378bdba18f84fafa1e7a72f747bdb399b93aab72d1778fb85d1da543711a1faa`。
- dock 搬迁主体文本 diff 只有 `MoreSheet` 从 `private` 调整为 `internal`，用于同 package
  跨文件调用；其他主体逐字相同。
- 结构契约同时断言 `LogRoute`、`LogViewModel`、`buildTimelineLanes`、`OneHandQuickDock`
  在拆分后的四个文件中各只有一份，防止产生并行规则。

## JVM, lint and APK gates

```text
./gradlew :feature:log:testDebugUnitTest :feature:log:lintDebug \
  :app:assembleDebug :app:lintDebug

BUILD SUCCESSFUL in 21s
770 actionable tasks: 110 executed, 660 up-to-date
```

整套 `feature:log` 测试覆盖 day-chart 筛选、三日轴、DST、跨夜 lanes、快捷栏、列表动作、
Composer 路由与布局写入。第一次整套运行的
`DeviceLayoutSnapshotWriterTest.failedAfterCompensationIsVisibleAndRetryRestoresUiDurability`
出现一次时序波动（期望失败态、观察到保存中）；该用例 `--rerun-tasks` 单独复跑通过，随后上述
整套命令再次通过。实现未触碰该 writer 或测试。

最终 XML 汇总：251 tests、0 failures、0 errors、0 skipped。

## API 35 device regression

Device: `emulator-5554`, `lezi_api35(AVD)`, API 35.

```text
./gradlew :feature:log:connectedDebugAndroidTest

Starting 45 tests on lezi_api35(AVD) - 15
Finished 45 tests on lezi_api35(AVD) - 15
BUILD SUCCESSFUL in 37s
201 actionable tasks: 9 executed, 192 up-to-date
```

45/45 覆盖日图/时间线、快捷槽与更多入口、布局入口、滑动编辑删除、Composer 等主路径，
0 skipped、0 failed。

## Scope limits

- 本票只拆文件边界；不改筛选、几何、颜色、记录写入或产品交互。
- 用户自有未跟踪 `AGENTS.md` 不在任务范围，未修改、未暂存。
