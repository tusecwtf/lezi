# Ticket 21 — 计时前台服务启动失败恢复证据

## 状态契约

- UI/DataStore 先发布不走秒的 `STARTING` 快照；只有 `NursingTimerService`
  完成通知初始化和 `startForeground` 后通过 `ResultReceiver` 回执，才发布 `RUNNING`。
- 平台入口同步抛出的受限启动、权限和普通运行时异常，以及服务内通知初始化失败、
  回执超时，统一落到不走秒的 `FAILED`；累计值、宝宝、完成幂等键和目标侧保留。
- `FAILED` 与进程恢复后的 `RECOVERABLE` 都提供显式“重试启动”。重启解析会冻结旧的
  `RUNNING` / `STARTING` 快照，不假定前台服务仍存活，也不自动重复启动。
- 同进程的页面/ViewModel 重建只有在运行快照、完成幂等键与服务内存见证三者匹配时才
  保持 `RUNNING`；因此离开计时页不会误停真实服务，进程重启也不会凭旧 JSON 伪造存活。
- 被拒绝的启动尝试不累计服务确认前的等待时长，反复失败/重试不会制造喂奶分钟数。
- 启动中的重复点击由原子 in-flight 门闩拒绝；左右状态变换仍由既有 mutex 串行化。

## TDD 与回归

- RED：
  `./gradlew :feature:timer:testDebugUnitTest --tests 'com.lezi.babylog.feature.timer.TimerServiceStartRecoveryTest'`
  在生产 seam 尚不存在时以 unresolved references 失败。
- GREEN（目标）：同一命令通过，覆盖成功回执、五类失败、通知创建失败、重复点击门闩、
  失败重试、真实服务会话匹配/不匹配，以及 `STARTING` / `FAILED` / `RUNNING` /
  普通暂停的初始化恢复。
- GREEN（模块）：`./gradlew :feature:timer:testDebugUnitTest` 通过。
- 静态检查：`./gradlew :feature:timer:lintDebug` 通过。
- 设备测试源集门禁：`./gradlew :feature:timer:compileDebugAndroidTestKotlin` 通过
  （当前该模块无独立 androidTest 源，真实 Service 行为由下述设备 smoke 收口）。
- 整包 Debug 首次构建被并发中的 Ticket 16 UI seam 中间态阻断；失败点不在计时器文件。
  该 seam 转绿后重跑 `./gradlew :app:assembleDebug` 已通过，包含 Timer 的 Hilt 注入与
  `NursingTimerService` 清单集成。

## 尚待设备门禁

需要在 API 35 设备上分别验证正常前台服务回执，以及系统限制前台服务启动时 UI 不闪退、
不显示假运行态并能在解除限制后重试成功。完成前工单保持
`implemented-awaiting-device-smoke`。
