# 10 — 成员与设备称呼生命周期

**What to build:** 完成成员称呼和设备称呼的创建、申请改名、审批与管理员维护规则，并统一名称规范化与唯一性约束。

**Blocked by:** 08 — 成员登录二维码；09 — 家庭成员与设备页面

**Status:** complete

- [x] 家庭称呼去除首尾空白、折叠连续空白并按统一 Unicode 规则规范化后，在当前家庭内唯一；冲突返回可理解、可重试的业务错误。
- [x] 普通成员可提交自己的改名申请；审批前继续使用原称呼，管理员可在“家庭成员与设备”中批准或拒绝。
- [x] 管理员可主动添加一个尚未绑定设备的成员称呼，并可直接修改任意成员称呼；所有入口使用同一唯一性规则。
- [x] 管理员添加的无设备成员可随后通过成员登录二维码绑定第一台设备，或通过待确认设备审批进行绑定。
- [x] 设备称呼仅在同一 membership 内规范化后唯一；成员可修改自己的设备称呼，管理员可修改任意设备称呼。
- [x] 新设备默认使用当前安卓设备名称；默认名冲突时必须引导用户确认一个可用名称，不得静默覆盖既有设备。
- [x] 名称变更不创建身份副本或身份墓碑；增加并发冲突、审批失效、跨家庭同名和同成员设备重名测试。

## Verification

- SQLite fresh-current schema is v10. It stores normalized membership and per-membership device-name keys plus explicit pending member-rename requests; active unique indexes reject normalized collisions without creating a second membership, device, or tombstone.
- Member self-renames now remain pending with the old name authoritative until Owner approval. Owner can list/approve/reject requests, add a device-less member, directly rename any member, and rename any active device; Members can rename only their own active devices. Expired, cancelled, rejected, conflicting, cross-family, and unauthorized actions fail closed.
- Device-less memberships can be targeted by the existing Member login QR and pending-device approval paths. Owner login and both grant-claim paths check the default Android device name transactionally; collisions preserve the existing device and return a retryable name conflict so the user can choose another name.
- Rust formatting and Clippy passed; 38 library tests, 103 Router/API tests, and the HTTPS TLS integration test passed. Router coverage includes NFKC/whitespace equality, approval-time collision, exactly-one concurrent rename winner, expiry/reject/cancel, cross-family same names, same-membership device collision, cross-membership reuse, QR binding, pending approval binding, and identity-count stability.
- Android gates passed: sync 347/347, domain 293/293, family 19/19, onboarding 6/6, plus `:app:assembleDebug`. HTTP and coordinator tests cover exact paths/bodies, pending-vs-updated self rename, normalization, redaction, and role gates. API 35 Family instrumentation passed 15/15 and exercises the Owner pending-rename actions, Member request wording, add member, and member/device rename affordances. `git diff --check` passed.
- No live NAS or two-physical-device camera run was performed for this non-release ticket. Cross-device collision/retry and spoken accessibility acceptance remain ticket 16 scope.
