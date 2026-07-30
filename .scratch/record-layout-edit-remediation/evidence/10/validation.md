# Ticket 10 validation

## Current disposition

布局编辑静止态不再持续晃动。触摸拖动只在成功拾取、首次进入新的合法当前目标和确实改变布局的成功放下发出短视觉脉冲与单次系统触感请求；no-op、非法释放、取消和陈旧 token 不产生成功反馈。

## Implementation receipts

- `LayoutDragFeedback` 是以 drag token 为会话键的纯 reducer，并直接消费 Ticket 03 的权威 `LayoutDropTarget`；没有复制命中判断。
- pickup 会把指针下初始目标记作 baseline，紧接着的同目标 move 不会形成第二次 target 反馈；离开后重新进入才算新的合法目标。
- accepted drop 通过 `reduceLayoutEdit(prefs, intent, known) != prefs` 判定，因此自身落点、no-op 和非法释放不伪装成成功。
- 原无限 jiggle 已删除；160ms 脉冲是画布覆盖层，不参与卡片、分类或 Dock 的测量。`MotionDurationScale <= 0` 时立即归零。
- 系统触感只调用 `LocalHapticFeedback` 一次，不探测设置也不补偿重试。TalkBack/键盘继续使用 Ticket 06 的语义动作与状态反馈。
- 删除区已有危险说明、2dp 边框、`LocalDeleted` 目标语义与普通槽/目录目标的形态及语义差异，不只依赖颜色。

## JVM, lint and build gates

```text
./gradlew :feature:log:testDebugUnitTest \
  :feature:log:lintDebug \
  :app:assembleDebug
```

结果：`BUILD SUCCESSFUL`；578 tasks（1 executed，577 up-to-date）。`LayoutDragFeedbackTest` 覆盖 pickup baseline、新目标限频、离开后重进、accepted drop、rejected/no-op、取消、陈旧 token 与动画缩放为 0。

## API 35 connected receipts

系统默认设置下执行：

```text
./gradlew :feature:log:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=\
com.lezi.babylog.feature.log.LayoutMotionHapticsDeviceTest
```

结果：`Starting 3 tests on lezi_api35(AVD) - 15`，`Finished 3 tests`，`BUILD SUCCESSFUL in 10s`；201 tasks（1 executed，200 up-to-date）。用例锁定静止节点 1 秒后 bounds 不变、成功拖动只请求 pickup/target/drop 三次、短按不请求以及自身 no-op 只请求 pickup。

随后把 `animator_duration_scale` 临时设为 `0`、`haptic_feedback_enabled` 临时设为 `0`，在同一 API 35 模拟器重跑同一 3 项测试；结果再次 `Finished 3 tests`、`BUILD SUCCESSFUL in 10s`。测试结束后动画缩放恢复为测试前的系统默认值，系统触感恢复为 `1`。

## Verification boundary

- connected 测试记录 Compose 向系统触感接口发出的事件与限频，不声称模拟器提供了可人工感知的物理震动。
- 当前镜像未提供 spoken TalkBack 人工验收；无障碍等价路径由 Ticket 06 的既有语义/键盘门禁承担，本票没有伪造触感替代。
- 本票未修改布局持久化、Record、家庭同步、版本或发布产物。
