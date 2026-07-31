# 15 — 收口旧网络与鉴权模型

**What to build:** 在新链路全部可用后执行 expand-contract 的收口阶段，删除旧 HTTP、SSID、邀请、长期家庭 token 和单设备成员模型，只保留已锁定的新协议与产品入口。

**Blocked by:** 14 — 去除 Wi-Fi 绑定并收敛前台同步体验

**Status:** complete

- [x] 服务端停用旧建家、邀请、加入、认领和长期家庭 token 接口；能力声明只公布当前 HTTPS、设备会话和成员管理协议。
- [x] Android 删除旧家庭网络策略、旧邀请/配置流程及失效的数据模型、持久化字段和测试夹具，不保留可被重新启用的平行链路。
- [x] 删除旧独立同步、安全、网络偏好和迁移说明 UI；账户、加入家庭、家庭成员与设备以及三页下拉刷新成为唯一产品入口。
- [x] 生产配置不再允许明文 HTTP，同步所需的 Wi-Fi/定位权限从 Manifest 与运行时请求中移除。
- [x] 数据库与 wire contract 只接受当前版本；旧数据库或旧客户端请求失败关闭，本阶段不实现客户端数据迁移或兼容写入。
- [x] 不额外增加审计事件、管理员登录记录、NAS 到 VPS 迁移或服务器部署迁移功能；日志继续对根密码、授权码、访问与刷新凭证脱敏。
- [x] Android 全量 JVM 测试与 lint、Rust fmt/test/clippy 和新旧协议负向测试通过，且全文检索确认旧入口和旧网络判断已无生产引用。

## Verification · 2026-07-31

- 服务端删除旧 invite/join route、store/schema/model、速率限制和部署变量；`legacy_invite_and_join_routes_are_absent` 证明两个旧 POST 路径均返回 404，当前 setup capability 只公布 trusted HTTPS、device session、membership device、atomic bundle 与 record author。
- Android 删除邀请 payload/command/use case、旧 family-token store、HTTP 公网警告和内置 NAS 地址；配置 seam 收敛为无默认地址的 `FamilyEndpointConfig`，refresh credential 只使用新安全存储 key，不读取旧 key。
- `ContractSupersededSurfacesTest` 固定旧源码、旧类型和旧生产符号不得恢复；全文生产检索对旧 route、token、SSID/Wi-Fi 门闩、旧 endpoint 配置和 invite/join seam 无命中（负向测试与历史处置文档除外）。
- `./gradlew test lintDebug :app:assembleDebug :app:processReleaseMainManifest --no-parallel` 通过；Release 合并 Manifest 为 `usesCleartextTraffic=false`，只含 `INTERNET`/`ACCESS_NETWORK_STATE` 等现行权限，无 Wi-Fi/定位权限。Debug APK SHA-256 为 `a61ac3a83031546ef2efe4502eac9c881267c894fcf6706112e8624b76648093`。
- `cargo fmt --all -- --check`、`cargo clippy --all-targets --all-features -- -D warnings`、38 个库测试、106 个 API 测试和 1 个 TLS 黑盒测试通过。
- 本票不声称物理双设备、真实网络切换或发布候选验收已完成；这些证据由固定 HEAD 上的 16 票负责。
