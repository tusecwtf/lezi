# 07 — 家庭会话深模块与 RealSyncPort 收口

**What to build:** 在家庭身份/账户契约稳定后，从 `RealSyncPort` 提取家庭会话深模块，统一 endpoint 配置、create/join/invite/members/rename/leave/delete 与 session 持久化。完成后 `RealSyncPort` 仅保留公开 Port façade、home-LAN gate、状态流和对深模块的协调，生产文件 ≤800 行。

**Blocked by:** 06；`.scratch/family-identity-account-overview/issues/04-family-wizard-and-invite.md`

**Status:** ready-for-agent

**Size:** M–L
**Review finding:** Standards 4（RealSyncPort）
**Seam:** family-session module；复用现有 `SyncBackend` 与 preferences adapters

## Initial file surface

- 新增 `sync/.../FamilySessionCoordinator.kt`
- 新增 `sync/src/test/.../FamilySessionCoordinatorTest.kt`
- `sync/.../RealSyncPort.kt`
- `sync/.../SyncPort.kt`
- `sync/src/test/.../RealSyncPortTest.kt`
- PRD 仅在 interface/行为有变化时触碰

## Interface contract

- 小 interface 围绕 family-session commands 与 typed outcomes；endpoint 合并、home-LAN gate、token/session 持久化顺序必须明确。
- backend 网络调用继续走现有 `SyncBackend` seam；不得再造 HTTP adapter。
- session 切换后 receipt/outbox reset、status 更新和立即同步属于 implementation，不散回 caller。

## Acceptance criteria

- [ ] create/join/invite/members/rename/leave/delete 的 endpoint、鉴权、持久化与错误分类集中在一个 module。
- [ ] 家庭称呼/家庭名的最终 wire 契约与 `family-identity-account-overview` 已落地版本一致，不恢复可选参数旧形状。
- [ ] 删除无生产 caller 的 deprecated `joinWithPayload` / `joinWithCode`；若仍有 caller，先在本票迁移后删除。
- [ ] server changed、leave、delete 后 receipt/outbox/session reset 顺序保持并有 interface 测试。
- [ ] owner/member 权限、bootstrap 拒绝、Home-LAN gate 与取消传播不回归。
- [ ] `RealSyncPort.kt` 最终 ≤800 行；不得以 pass-through wrapper、空类或机械拆文件达标。
- [ ] `RealSyncPortTest` 只保留 Port façade / gate / status 集成测试；家庭会话行为迁至新 module interface 测试。
- [ ] 全量搜索确认无重复家庭会话 implementation。

## Validation

- `./gradlew :sync:testDebugUnitTest :feature:onboarding:testDebugUnitTest :feature:family:testDebugUnitTest --no-daemon`
- `./gradlew test :app:assembleDebug --no-daemon`
- `wc -l sync/src/main/kotlin/com/lezi/babylog/sync/RealSyncPort.kt` ≤800。
- `git diff --check`
- release signing 缺失若阻断相关 gate，必须单独报告，不能把 targeted green 写成全量 green。

## Documentation Gate

- `SyncPort` interface、endpoint/wire、会话持久化或用户错误行为变化时，同票更新 `docs/prd/data-model.md`、`docs/prd/sync-home-lan.md` 与测试；纯内部搬家才可 N/A。

## Out of scope

- 修改家庭账户 IA、上传者展示或 server Rust 协议。
- 新增后台同步、云同步、P2P、踢人或管理员转让。

## Comments

- 来源：固定范围审查 Standards finding 4；这是 05→06→07 的收口票，不得提前与当前家庭身份 WIP 混做。

