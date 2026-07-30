# Ticket 19 — DST 安全三日时间轴证据

日期：2026-07-30

基线：`af21110`（本票据提交前）

设备：`lezi_api35`，Android API 35

## TDD 与逻辑合同

1. designsystem 红测先因缺少 `TimelineWindowGeometry`、睡眠切片无法接收动态午夜边界而编译失败。
2. Log 集成红测随后因 `buildTimelineLanes` 仍要求独立的固定窗口起止而失败。
3. 绿灯实现后以下确定性测试全部通过：
   - `DstThreeDayTimelineAxisTest`：10 个测试，覆盖纽约春跳/秋回、上海基线、缺口、重复小时、真实经过时长、半开边界、裁剪、居中与平移；
   - `ThreeDayTimelineAxisTest`：7 个测试；
   - `BuildTimelineLanesTest`：7 个测试，含跨 DST 睡眠与 D+1 午夜事件共轴；
   - `TimelineAxisTest`：13 个测试；
   - `TimelineMarkerLayoutTest`：10 个测试，含 2820 分钟 DST 主日边界的绘制/命中同坐标断言。

执行：

```text
./gradlew :designsystem:testDebugUnitTest :feature:log:testDebugUnitTest
BUILD SUCCESSFUL
```

## Compose 设备与截图门

`TimelineDstDeviceTest.springForwardGeometrySharesDrawingHitAndPanCoordinates` 在 API 35 上使用 4260 分钟春跳窗口：

- 点按真实主日结束边界 2820 分钟处的标记，回调得到 `PEE`；
- 从同一 lane 左拖后 viewport 向后移动，且不超过动态最大起点 `4260 - 1560 = 2700`；
- `captureToImage()` 成功捕获时间轴卡片且宽高均为正。

```text
./gradlew :designsystem:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.lezi.babylog.designsystem.TimelineDstDeviceTest
Starting 1 tests on lezi_api35(AVD) - 15
Finished 1 tests
BUILD SUCCESSFUL
```

JUnit 回执：`tests="1" failures="0" errors="0" skipped="0"`。

## 应用与静态门

```text
./gradlew :app:assembleDebug :feature:log:lintDebug :designsystem:lintDebug
BUILD SUCCESSFUL
```

- 应用 Debug APK 完整装配；
- `feature:log` 与 `designsystem` lint 均成功；
- `git diff --check` 通过。

## 产品边界 disposition

当前 `docs/prd/ui.md` 明确护理计划/已错过计划是事实时间轴上方的独立行，三轨只承载睡眠、喂养、护理 Record。因此验收条目中“CarePlan 标记/已过期遮罩”在当前产品没有轴上实体；本票据没有把未来计划混入 Record rail，也没有保留第二套坐标换算。现有轴上 Record、开放区间、当前时刻、午夜分隔、邻日降淡、小时标签、绘制、命中、居中与平移已全部共用 `ThreeDayTimelineAxis` / `TimelineWindowGeometry`。
