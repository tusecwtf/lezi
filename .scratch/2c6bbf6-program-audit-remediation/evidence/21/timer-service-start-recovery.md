# Ticket 21 — 计时前台服务启动失败恢复证据

## 状态契约

- UI/DataStore 先发布不走秒的 `STARTING` 快照；只有 `NursingTimerService`
  完成通知初始化、`startForeground`，且 Android 实际报告 foreground 后通过
  `ResultReceiver` 回执，才发布 `RUNNING`。通知权限开启时还要求 active notification；
  Android 13+ 通知权限关闭时允许系统接纳的前台服务继续运行。
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
- GREEN（目标）：同一命令通过，覆盖成功回执、五类失败、通知创建失败、平台静默忽略、
  通知权限关闭但真实前台成功、重复点击门闩、
  失败重试、真实服务会话匹配/不匹配，以及 `STARTING` / `FAILED` / `RUNNING` /
  普通暂停的初始化恢复。
- GREEN（模块）：`./gradlew :feature:timer:testDebugUnitTest` 通过。
- 静态检查：`./gradlew :feature:timer:lintDebug` 通过。
- 设备测试源集门禁：`./gradlew :feature:timer:compileDebugAndroidTestKotlin` 通过
  （当前该模块无独立 androidTest 源，真实 Service 行为由下述设备 smoke 收口）。
- 整包 Debug 首次构建被并发中的 Ticket 16 UI seam 中间态阻断；失败点不在计时器文件。
  该 seam 转绿后重跑 `./gradlew :app:assembleDebug` 已通过，包含 Timer 的 Hilt 注入与
  `NursingTimerService` 清单集成。

## API 35 固定提交设备门禁

- 固定 HEAD：`e6742f1a3bcde05443b68dcb170b83b16ab69ccb`。
- 隔离构建 APK：`/tmp/lezi-ticket21-device/app/build/outputs/apk/debug/app-debug.apk`，
  `28,257,340` bytes，SHA-256
  `8b6cca08e1ddad2610ad769ecc7a4b4a0d8bf2ebbe9998bffe31ef9e7355fc2e`；
  package `com.lezi.babylog.debug`，version `0.2.6-debug`。
- 设备：单台 API 35 `emulator-5554`。schema 24 是 fresh-current；第一次在旧 schema 23
  数据上覆盖安装按契约报缺少 23→24 migration，随后清洁安装并注入最小本机家庭/宝宝夹具。
  这不是迁移兼容验证，也不把清洁安装包装成升级支持。
- 正常/静音通知：`START_FOREGROUND=allow`、`POST_NOTIFICATION=ignore` 时，点击重试后
  UI 从 `合计 0:00` 进入走秒（`0:05`），系统报告 `startForegroundCount=1`、
  `isForeground=true`、notification id `42`。离开页面再进入，UI 继续到 `1:00`，服务仍为
  foreground，证明页面导航没有伪停或重复启动。
- 进程失联：运行中 `am force-stop` 后系统无 `NursingTimerService`；重启并进入计时页显示
  “上次计时未确认仍在运行，已安全暂停；可重试启动”，两侧按钮均为“开始”，未伪装运行。
- 静默拒绝：设置 `START_FOREGROUND=ignore` 后点“重试启动”，`startForeground()` 没有抛错，
  但最终 APK 通过系统 foreground 位识别拒绝；UI 保持 `合计 0:25`，显示“系统暂不允许…
  已安全暂停；可重试启动”，系统无存活计时服务，且无闪退。
- 恢复：恢复 `START_FOREGROUND=allow` 后点“重试启动”，UI 走到 `合计 0:30`，系统再次报告
  `startForegroundCount=1`、`isForeground=true`。验收结束已暂停并确认丢弃，AppOp 恢复为
  `allow`，无遗留计时服务。

设备 smoke 先在 `25af7bb` 捕获“AppOp 静默忽略却误报 RUNNING”，再在 `5bc6c56` 捕获
通知权限关闭时 active-notification 假阴性；两次均未错误闭票。最终由 `5bc6c56` 与
`e6742f1` 的窄修复收口，并在上述固定 APK 上完整复验。
