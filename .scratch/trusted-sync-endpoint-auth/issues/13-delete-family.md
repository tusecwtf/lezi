# 13 — 删除家庭

**What to build:** 在账户页底部提供仅管理员可见的删除家庭动作，以家庭名和根密码双重确认后删除服务端整个家庭数据集，并让所有设备安全退出。

**Blocked by:** 05 — 管理员新设备登录、接管与根密码轮换；11 — 撤销设备与退出当前设备

**Status:** complete

- [x] 删除家庭仅允许当前管理员会话发起，并要求用户输入规范化后完全匹配的家庭名以及有效根密码。
- [x] 根密码只用于本次高风险校验，不写入日志、遥测、数据库或普通会话存储；普通成员不存在删除家庭入口和接口权限。
- [x] 服务端以原子方式删除家庭、成员、设备、会话、申请、记录、计划、媒体索引和同步游标；重复请求返回明确且可安全收敛的结果。
- [x] 当前设备只有在服务端确认删除成功后才进入可重复、崩溃可恢复的本地全量清理；校验失败或网络失败不得清空本地数据。
- [x] 其他设备下一次连接原 endpoint 时收到明确的 `family_deleted` 结果并清理本地家庭数据、凭证与 endpoint 信任。
- [x] 确认页使用直白的不可恢复说明和无障碍可读错误，不展示技术字段，也不把接管管理员拆成独立入口。
- [x] 增加权限、双重确认、部分失败、幂等删除、离线设备与本地清理恢复测试。

## Verification · 2026-07-31

- `cargo fmt --all -- --check`、`cargo clippy --all-targets --all-features -- -D warnings` 通过。
- `cargo test --locked --lib`、`--test api`、`--test tls` 通过（38 + 106 + 1）；覆盖 Member 拒绝、家庭名/根密码双确认、事务失败不删媒体、成功响应丢失后的 `family_deleted` 收敛及重启持久性。
- `./gradlew :sync:testDebugUnitTest :domain:testDebugUnitTest :feature:family:testDebugUnitTest` 通过（372 + 293 + 20）。
- `./gradlew :feature:family:connectedDebugAndroidTest` 在 `lezi_api35(AVD) - 15` 通过（21 个设备测试，含双阶段确认、禁用态与 assertive live-region 错误）。
- `./gradlew :app:assembleDebug` 与 `git diff --check` 通过；Debug APK 为 29,181,857 bytes，SHA-256 `c18895f7873937f58a6bb3584a4911eed75384e7cea4fe0f4e27e49da79e9803`。
