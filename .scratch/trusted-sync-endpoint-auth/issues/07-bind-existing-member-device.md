# 07 — 绑定既有成员与多设备权限

**What to build:** 允许管理员把待确认的新设备明确绑定到一个既有家庭成员，使同一 membership 可长期持有多个独立设备凭证，并按成员身份而不是设备身份执行数据权限。

**Blocked by:** 06 — 普通成员申请并由管理员批准

**Status:** complete

- [x] 管理员审批待确认设备时，可明确选择“新建成员”或“绑定既有成员”；服务器不得仅凭称呼、设备 ID 或历史设备自动合并身份。
- [x] 同一 membership 可绑定多个 Device；每台设备拥有独立凭证和设备称呼，新增设备不得替换或注销原有设备。
- [x] 服务端仅依据已验证的设备凭证解析 membership 与角色；客户端提交的成员 ID、角色或设备 ID 不得提升权限。
- [x] 同一 membership 下的设备可按既有产品规则查看、编辑和删除该成员自己的记录与计划；普通成员不得操作其他成员的数据，管理员权限保持有效。
- [x] 新绑定设备首次同步可获得该家庭完整历史，以及完整的 0–3 张照片原子包；未完整到达的照片包不得提前展示记录或计划元数据。
- [x] 家庭称呼规范化后继续在当前家庭内唯一；绑定既有成员时不得通过重复称呼创建第二个 membership。
- [x] 增加服务端路由、权限与 Android 同步回归测试，覆盖同一成员双设备、跨成员越权、伪造角色和完整历史同步。

## Verification

- SQLite fresh-current schema is v8 and persists `approval_kind`; deleting a selected target before claim fails closed instead of changing an existing-member decision into new-member creation.
- `cargo fmt --all -- --check`, `cargo clippy --all-targets --all-features -- -D warnings`, 38 Rust library tests, 101 Router black-box tests, and the HTTPS TLS integration test all passed.
- Router coverage proves explicit Owner-only binding, no same-name/ID auto-bind, no Owner-role forgery, immutable decision kind, same-membership independent credentials, old-device survival, single claim, complete history plus a three-photo atomic package, same-membership CarePlan edit, cross-member 403, and deleted-target non-creation.
- `./gradlew :sync:testDebugUnitTest :domain:testDebugUnitTest :feature:family:testDebugUnitTest :feature:onboarding:testDebugUnitTest` passed. HTTP, refresh, coordinator, and `SyncPort` tests cover the exact authenticated route/body and Member denial.
- `:feature:family:connectedDebugAndroidTest` passed all 8 tests on `lezi_api35(AVD) - 15`; the decision UI offers explicit existing/new/reject actions, excludes Owner targets, and disables normalized duplicate-name creation with an explanation. `git diff --check` passed.
- No live NAS deployment or two-physical-device run was performed for this non-release ticket; cross-device hardware evidence remains ticket 16 scope.
