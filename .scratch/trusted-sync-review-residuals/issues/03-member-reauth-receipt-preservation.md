# 03 — Member 同家庭 reauth 保留收据与 identity-skip

**What to build:** 将 `320ac7d` / `4ff0266` 的同家庭/同身份收据保护扩展到成员批准
claim 与 QR grant claim；`resetLocalSyncReceipts` 判断「当前收据是否属于 previous」时
不得依赖 `previous.isJoined`（reauth 时为 false）。

**Blocked by:** None — can start immediately.

**Status:** complete

**Severity:** High
**Blocks release:** yes
**Review ID:** F-03

## Must

- [x] Member `checkMemberLogin` 批准 claim 与 `claimMemberLoginGrant`：当
      familyId + membershipId + role 与 previous 一致时 **跳过** receipt reset（对齐
      `persistJoin` 的 `replicaIdentityUnchanged`）。
- [x] 仅当 family 变化时 `crossingFamilyBoundary = true`；membership/role 变化仍 reset，
      但 boundary 语义与 Owner 路径一致。
- [x] `ReplicaSyncEngine.resetLocalSyncReceipts`：media receipt 保留条件基于稳定身份
      （如 non-blank familyId + baseUrl + `hasReceiptFor(previous)`），**不**用
      `previous.isJoined`。
- [x] 回归：Owner 既有 identity-skip 用例仍绿；新增 Member same-family re-claim /
      reauth 不 wipe log media receipts / 不强制 crossing wipe creators 的用例。
- [x] reauthRequired previous + `invalidateCurrentReceipts=false` 时 log media remoteUri
      可保留的单测。

## Evidence paths

- `sync/.../FamilySessionCoordinator.kt` (claim paths ~340–470, `persistJoin` ~669+)
- `sync/.../ReplicaSyncEngine.kt` (`resetLocalSyncReceipts` media branch ~1036+)
- `sync/.../SyncPreferences.kt` (`isJoined`)
- Tests: `FamilySessionCoordinatorTest`, `RealSyncPortTest`

## Comments

- 2026-07-31 review：Owner 已修、Member 未修；与 full-resync media ack (`a4dbe07`) 正交。
