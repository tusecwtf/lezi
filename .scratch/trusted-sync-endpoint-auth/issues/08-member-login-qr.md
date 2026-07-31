# 08 — 成员登录二维码

**What to build:** 由管理员 App 为指定成员生成一段同时携带服务配置、证书信任信息和一次性登录授权的二维码；成员扫码后确认连接、可修改设备称呼并完成登录。

**Blocked by:** 02 — 确认自签名证书并固定 SPKI；07 — 绑定既有成员与多设备权限

**Status:** complete

- [x] 只有管理员可为当前家庭中的指定 membership 申请登录授权；授权有效期为 10 分钟且只能成功兑换一次。
- [x] 二维码由管理员 App 展示，服务端不负责显示二维码；内容同时覆盖 endpoint、证书信任材料和成员登录授权，不再要求第二张“配置二维码”。
- [x] 二维码不得包含根密码或长期设备凭证；扫码获得的一次性授权不得作为可跨进程恢复的长期秘密持久化。
- [x] 成员扫码后先完成 endpoint 与证书信任确认，再展示目标家庭、成员称呼和默认安卓设备名称；成员可在提交前修改设备称呼。
- [x] 授权过期、已使用、目标成员已删除或家庭已删除时必须失败关闭，并允许成员改走“加入家庭”人工申请流程。
- [x] 管理员登录不使用二维码；扫码流程除相机外不增加权限，二维码展示页防止系统截图并提供 TalkBack 可读说明。
- [x] 增加服务端授权兑换、二维码编解码、Compose 流程与真机扫码测试，覆盖单次兑换、过期、重放及证书不匹配。

## Verification

- SQLite fresh-current schema is v9. The server stores only a hash of the 32-byte URL-safe grant, binds it to one active ordinary membership, expires it after exactly 600 seconds, and consumes it transactionally while minting an independent DeviceSession.
- `cargo fmt --all -- --check`, `cargo clippy --all-targets --all-features -- -D warnings`, 38 Rust library tests, 102 Router black-box tests, and the HTTPS TLS integration test all passed. Router coverage includes Owner-only creation, target binding, strict bodies, plaintext-secret absence, exact expiry, replay, removed target, and deleted family failures.
- `./gradlew :sync:testDebugUnitTest :domain:testDebugUnitTest :feature:family:testDebugUnitTest :feature:onboarding:testDebugUnitTest` and `:app:assembleDebug` passed. Codec, HTTP, pinned-SPKI transport, refresh, coordinator, and `SyncPort` tests cover redaction, trust-before-grant-write, one-shot claim, and persist-before-recovery behavior.
- `:feature:family:connectedDebugAndroidTest` passed all 10 tests on `lezi_api35(AVD) - 15`. Compose coverage verifies the Owner-only target action, secure QR semantics without raw grant text, family/member confirmation, editable device name, and the manual application fallback. `git diff --check` passed.
- No live NAS deployment or two-physical-device camera scan was performed for this non-release ticket. Camera hardware, spoken TalkBack, and cross-device replay/expiry acceptance remain explicit ticket 16 evidence boundaries; no such hardware result is claimed here.
