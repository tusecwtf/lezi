# 05 — 管理员新设备登录、接管与根密码轮换

**What to build:** 让管理员在已有家庭的新设备上输入根密码，默认把设备加入同一 Owner membership；如果所有旧管理员设备已丢失，可显式接管并撤销它们。服务器运维者更换根密码后，只有旧管理员会话失效。

**Blocked by:** 04 — 轮换设备会话并安全处理凭证丢失

**Status:** complete

- [x] configured 家庭的「我是家庭管理员」使用根密码和设备称呼登录同一个 Owner membership，不创建第二 Owner membership。
- [x] 普通登录只新增当前 Owner Device，既有管理员设备继续有效且可独立撤销。
- [x] 「丢失设备并接管」有独立危险说明和二次确认；成功原子撤销全部旧 Owner DeviceSession，再签发当前设备 session。
- [x] 管理员普通登录和「丢失设备并接管」都整合在“加入家庭”流程中；账户页不新增独立接管入口。
- [x] 更换部署环境中的根密码并重启后，全部旧 Owner session 失效，普通成员 session 保持有效；App 不提供查看或修改根密码。
- [x] 普通成员不能继承、投票成为或被自动提升为 Owner；管理员没有 QR 登录入口。
- [x] 错误根密码、普通登录重试和接管重试均幂等且不泄露家庭内部信息。
- [x] Rust Router、Android 状态机和 Compose 危险确认测试覆盖添加、接管、根密码轮换及普通成员不受影响。

## Verification

- `cd tools/lezi-sync && cargo fmt --all -- --check` and `cargo clippy --all-targets --all-features -- -D warnings` passed.
- Full Rust validation passed 38 unit tests, 96 Router black-box tests, and the HTTPS certificate integration test. After the final store-call refactor, the ordinary Owner login, takeover, and root-password-rotation regressions were rerun individually and passed.
- Router tests prove one unique Owner membership, ordinary multi-device login with active old sessions, strict idempotent retry, atomic takeover of all old Owner devices, Member-session survival, same-root restart stability, changed-root Owner-only revocation, generic wrong-root failure, and absence of raw old/new root passwords in SQLite bytes.
- `./gradlew :sync:testDebugUnitTest :domain:testDebugUnitTest :feature:family:testDebugUnitTest :feature:onboarding:testDebugUnitTest :app:assembleDebug` and `./gradlew lintDebug` passed.
- Android tests cover the header/body/path contract without an Authorization bearer, durable Owner login request IDs, session-before-recovery ordering, 401/403 product mapping, both onboarding/account state-machine projections, ordinary versus takeover selection, and non-retention of the root password.
- `:feature:family:connectedDebugAndroidTest` ran `OwnerTakeoverConfirmationDeviceTest` successfully on `lezi_api35(AVD) - 15`, proving the exact danger copy plus explicit cancel/confirm actions. `git diff --check` passed.
- No live family NAS was replaced for this non-release ticket; root-change behavior is covered against a restarted Router on the same persisted SQLite data root.
