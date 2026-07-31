# 14 — 去除 Wi-Fi 绑定并收敛前台同步体验

**What to build:** 让日常同步只依赖已信任 HTTPS endpoint 和有效设备会话，不再识别 Wi-Fi 名称；将用户可主动触发的同步收敛为记录、汇总、成长三页下拉刷新。

**Blocked by:** 08 — 成员登录二维码；10 — 成员与设备称呼生命周期；11 — 撤销设备与退出当前设备；12 — 成员彻底删除与共享数据匿名化；13 — 删除家庭

**Status:** complete

- [x] 删除运行时对 SSID、BSSID 和“仅家庭 Wi-Fi”偏好的判断与提示，不以 ping 成功作为可信或可同步的依据。
- [x] 前台 App 可在 Wi-Fi、蜂窝网络或 VPN 上连接已固定信任的 HTTPS endpoint；endpoint 不可达时保持离线并保留本地写入。
- [x] 记录、汇总、成长三页都支持下拉刷新并复用同一个同步互斥与结果状态；这是用户可主动触发“立即同步”的唯一入口。
- [x] 回到前台、本地写入成功和进页节流等触发继续作为静默自动同步，不视为用户同步动作，也不新增按钮、弹窗或独立页面。
- [x] 下拉刷新和账户页只浅提示最后成功时间或当前错误；账户页无同步按钮，不展示 IP、token、server ID、证书详情或网络偏好。
- [x] 已配置家庭的服务器地址发生变化时，用户重走证书信任、setup probe 和对应角色的普通登录；不提供客户端 endpoint 迁移、自动身份继承或数据搬运流程。
- [x] 保持前台同步边界，不新增后台轮询、前台服务、推送、P2P 或成员通知；所有触发继续保护 Outbox 与照片原子包语义。
- [x] 移除仅用于 Wi-Fi 识别的权限和代码，增加三页 Compose、不同网络类型、并发触发、离线写入与错误呈现回归测试。

## Verification · 2026-07-31

- `ContractSupersededSurfacesTest.foregroundTrustedSyncHasNoWifiIdentitySurfaceAndExactlyThreeRefreshHosts` 通过：生产 Manifest 无 Wi-Fi/定位权限，SSID/BSSID 与旧网络门闩源码已移除，全仓生产 Compose 恰有记录、汇总、成长三处下拉刷新，并都委托 `SyncPort.sync(PullToRefresh)`；账户页无同步按钮。
- `./gradlew :sync:testDebugUnitTest` 通过（344）：覆盖传输类型不参与可信判断、同一互斥下的并发触发、endpoint 不可达时保留本地事实与可重试 Outbox、前后台门闩、错误状态及照片原子包既有回归。
- `./gradlew :domain:testDebugUnitTest :feature:family:testDebugUnitTest :feature:onboarding:testDebugUnitTest :feature:summary:testDebugUnitTest :feature:growth:testDebugUnitTest :app:testDebugUnitTest --no-parallel` 通过。
- `./gradlew :app:assembleDebug :app:lintDebug --no-parallel` 与 `git diff --check` 通过；Debug APK 为 28,683,129 bytes，SHA-256 `d245395a129b4236ec5c5c5814fae9b615cd22303a801d6ee44a7240b344683a`。
- 服务端固定点 `cargo fmt --all -- --check`、Clippy、38 个库测试、106 个 API 测试及 1 个 TLS 测试通过；TLS 测试因沙箱禁止本地端口首次失败后在允许该能力的同一工作区重跑通过。
- 本票未把手势设备实测或 Wi-Fi/蜂窝/VPN 物理传输实测表述为已完成；固定候选上的跨设备与真实网络验收由 16 票执行。
