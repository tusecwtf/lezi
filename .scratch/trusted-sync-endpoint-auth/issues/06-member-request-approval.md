# 06 — 普通成员申请并由管理员批准

**What to build:** 让普通成员声明家庭称呼和设备称呼，发送一条没有家庭数据权限的登录申请；管理员下次前台打开 App 时看到角标，并能批准为新成员或拒绝。批准后的设备领取独立 session 并完整同步。

**Blocked by:** 04 — 轮换设备会话并安全处理凭证丢失

**Status:** complete

- [x] 登录申请 24 小时过期，并返回高熵、仅限 status/cancel/一次领取 session 的 pending secret；公开 request ID 不能查询家庭或领取凭证。
- [x] 服务端对来源请求速率和家庭待处理总量设限；申请在批准前不能调用成员、设备或同步 API。
- [x] 申请设备显示「等待管理员确认」，可检查结果、取消并保持离线；不增加后台轮询、FCM 或系统 push。
- [x] 管理员账户首页显示具有 TalkBack 数量语义的待确认角标，并可在「家庭成员与设备」中查看声明称呼、设备称呼和申请时间。
- [x] 管理员可用唯一家庭称呼新建 membership 或拒绝；批准后设备只可成功领取一次 session，再执行可独立重试的完整同步。
- [x] 普通成员看不到全家庭待处理列表；申请过期、拒绝和取消不创建 membership、Device 或 credential。
- [x] Rust Router、Android coordinator 与 Compose 测试覆盖过期、限速、无权限、角标、批准、拒绝、单次领取和首次同步失败。

## Verification

- `cd tools/lezi-sync && cargo fmt --all -- --check` and `cargo clippy --all-targets --all-features -- -D warnings` passed.
- Rust validation passed 38 library tests and 100 Router black-box tests. The sandboxed full command could not bind `127.0.0.1:0` for the TLS child (`EPERM`); `cargo test --locked --test tls` passed outside that network sandbox (1/1).
- Router regressions cover pending-secret-only status/cancel/claim, public-ID non-authority, exact 24-hour expiry, source and family caps, Owner-only listing/decisions, Unicode-normalized unique display names, terminal-state non-creation, single claim, and full pull under the claimed session.
- `./gradlew :sync:testDebugUnitTest :domain:testDebugUnitTest :feature:family:testDebugUnitTest :feature:onboarding:testDebugUnitTest` passed, including durable pending capability, HTTP routes, session-before-recovery claim ordering, terminal cleanup, and foreground-check delivery to an open waiting wizard.
- `:feature:family:connectedDebugAndroidTest` passed all 6 tests on `lezi_api35(AVD) - 15`, including the TalkBack count badge, waiting actions, and Owner approve/reject controls. `git diff --check` passed.
- No live NAS deployment or two-physical-device end-to-end run was performed for this non-release ticket; those remain release acceptance evidence boundaries.
