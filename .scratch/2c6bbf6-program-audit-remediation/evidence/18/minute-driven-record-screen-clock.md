# Ticket 18 — 记录页分钟级时钟刷新证据

## 实现契约

- `RecordScreenTimeSnapshot` 原子携带 `Instant` 与 `ZoneId`。记录页的 today、now line、
  计划有效状态、记录/计划相对时间、设备时区时钟格式与新草稿时间都从同一 snapshot
  取得，不再由每个列表项分别读取系统时间。
- `recordScreenMinuteTicks` 是冷 Flow：每次收集立即发射一次，然后按该次 snapshot 的
  epoch millis 等待到下一真实分钟边界，再重新读取 instant 与 zone。它不做高频轮询。
- Compose 以 `remember(clock)` 保持单一 Flow，并通过
  `collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)` 收集。页面暂停、
  离开或销毁会取消收集；每次恢复 RESUMED 都重新收集并立即读取新环境。
- 每个 snapshot 同步到 `LogViewModel`，使 `TimelineWindowRequest`、三日窗口、开放睡眠
  轨迹末端和计划排序使用相同的 now/zone，而不是只刷新可见文案、保留旧数据窗口。
- 时钟回拨时继续复用 `relativeTimeLabel(timestamp, now)` 的既有非负钳制，未来差值显示
  “刚刚”，不会产生负的伪相对时间。

## TDD 与静态自审

- RED seam：可注入 `RecordScreenClock` 与 `awaitBoundary`，测试不真实等待一分钟。
- `activeClockEmitsImmediatelyThenAlignsToTheNextMinuteBoundary`：`xx:xx:59.750` 首次立即
  发射，并只等待 250 ms 后重新采样。
- `minuteTickCrossesMidnightUsingTheSnapshotZone`：上海时区在 1 ms 后由 7 月 30 日切到
  7 月 31 日。
- `resumedCollectionImmediatelyRefreshesAChangedClockAndZone`：停止收集后改变 instant 与
  zone，重新收集立即得到新环境。
- `cancellingCollectionCancelsThePendingBoundaryWait`：取消收集会取消尚未完成的边界等待。
- `clockRollbackDoesNotProduceANegativeRelativeTimeLabel`：回拨后的未来记录仍显示“刚刚”。
- `nextMinuteSnapshotTurnsADuePlanIntoMissed`：同一计划在边界前为待执行，边界后为已错过。
- `git diff --check` 已通过。

主协调者在最终代码上执行：

```text
./gradlew :feature:log:testDebugUnitTest --tests '*RecordScreenMinuteClockTest'
./gradlew :feature:log:testDebugUnitTest :feature:log:lintDebug :app:assembleDebug
```

定向结果：`BUILD SUCCESSFUL in 1s`，105 tasks（4 executed，101 up-to-date）。完整
feature/log 单测、lint 与应用组装结果：`BUILD SUCCESSFUL in 9s`，578 tasks
（13 executed，565 up-to-date）。此前当前工作区跨模块组装也已成功：
`./gradlew :app:assembleDebug` 为 408 tasks（17 executed，391 up-to-date）。
提交前又移除 `LogUiState` 自己读取 `LocalDate.now()` 的初始默认值，改为同一
`initialScreenTime.localDate`；随后完整 `:feature:log:testDebugUnitTest` 重新编译并通过：
`BUILD SUCCESSFUL in 3s`，105 tasks（10 executed，95 up-to-date）。

## 午夜与选中日期边界

分钟 snapshot 跨午夜后立即刷新 `today`、返回今天 chrome、now line、相对时间和计划状态；
时区变化也会重建窗口与初始视口。Ticket 18 不擅自把用户正在查看的历史日期改成新的一天，
`dayFlow` 仍由全局选中日期/用户操作驱动。若用户原本选择“今天”，全局日期权威可把新的
today 作为 `externalDay` 下发；若用户明确停留在历史日期，分钟 ticker 不覆盖该选择。

## Documentation gate

“刚刚”及分钟/小时/天的既有阈值没有变化，因此不修改 PRD 文案规则。此票只让已有
规则按分钟持续获得新 snapshot，并统一时区与回拨处理。

## API 35 真实页面 smoke

将最终 `app-debug.apk` 以 `adb install -r` 安装到 `emulator-5554`（API 35，保留数据），
启动真实 `com.lezi.babylog.debug`。现有记录只有小时级相对时间，因此通过生产
“尿尿快捷入口 → 预填 Composer → 确认记录”路径创建一条临时记录：

1. 记录页同一 09:13 尿尿节点初次取证显示“1 分钟前”。App 保持前台且没有重启、跳 Tab
   或主动刷新；跨过后续真实分钟边界再次读取同一节点，已自动显示“3 分钟前”。这证明
   ticker 在没有记录数据流变化时持续推动页面相对时间重算。
2. 页面同时保持计划、时间轴、快捷 Dock 和日期 chrome 可见，跨界期间无崩溃或空态闪烁。
3. 取证后从该记录进入生产编辑页，使用“删除 → 确认删除”清理临时数据；最终 UI 层级
   复查输出 `TEMP_RECORD_REMOVED`，未遗留 09:13 smoke 记录。

计划恰好跨到期、后台跨界恢复、设备时区切换及跨午夜没有在此设备轮次额外篡改系统状态；
对应状态转换、冷 Flow 重收集、时区/午夜和取消由上述 fake-clock 测试确定性覆盖。本票不
声称这些四项已经完成人工设备 smoke，也不声称 spoken TalkBack 验收。
