# 11 — 撤销设备与退出当前设备

**What to build:** 支持管理员撤销任意成员设备，以及成员让当前设备退出家庭；服务端先确认撤销，再以可恢复方式清理该设备的本地家庭数据和凭证。

**Blocked by:** 04 — 轮换设备会话并安全处理凭证丢失；09 — 家庭成员与设备页面

**Status:** complete

- [x] 管理员可撤销任意设备；普通成员只能让当前设备退出，不能撤销其他设备或其他成员凭证。
- [x] 撤销单台设备只失效该设备的访问与刷新凭证，不影响同一 membership 的其他设备或其他成员。
- [x] 当前设备主动退出时，必须先取得服务端成功确认，再进入可重复、崩溃可恢复的本地清理流程；服务端失败时不得伪装为已退出。
- [x] 被远程撤销的设备在下一次连接已信任 endpoint 时收到明确的 `device_removed` 结果并清理本地 Room、Outbox、媒体、endpoint 信任和会话材料。
- [x] 离线设备不承诺即时获知远程撤销；普通会话过期、网络错误或一般 401 不得触发家庭数据清空。
- [x] 被撤销设备可重新通过待确认申请或成员登录二维码申请绑定，不继承旧凭证。
- [x] “退出这台设备”位于账户页最下方并有清楚确认；远程撤销入口位于管理员可见的成员设备分组内。
- [x] 增加幂等撤销、双设备互不影响、离线恢复、进程中断恢复和误清空负向测试。

## Verification · 2026-07-31

- `cargo fmt --all -- --check`、`cargo clippy --all-targets --all-features -- -D warnings` 通过。
- `cargo test --locked --lib --test api` 通过（38 个库测试、104 个 API 测试）；`cargo test --locked --test tls` 通过（1 个 TLS 测试）。
- `./gradlew :sync:testDebugUnitTest :domain:testDebugUnitTest :feature:family:testDebugUnitTest` 通过（355 + 293 + 19）。
- `./gradlew :feature:family:connectedDebugAndroidTest` 在 `lezi_api35(AVD) - 15` 通过（17 个设备测试）。
- `./gradlew :app:assembleDebug` 与 `git diff --check` 通过。
