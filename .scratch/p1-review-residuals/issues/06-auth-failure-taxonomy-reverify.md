# 06 — 鉴权失败与 leave 401 分类（0.3.1 后复核）

**What to build:** （复核票，默认不实现）确认 0.3.1 之后，客户端能区分：会话过期/需重新申请、设备被撤销、成员被删除、家庭被删除、以及普通网络/鉴权错误；**leave/delete 不得再把「任意 HTTP 401」一律当成「会话已在服务端不存在」而本地清凭证成功**。普通 401 不得误清空未同步的本地家庭数据。

**Blocked by:** 0.3.1 发布（trusted-sync 会话轮换、撤销与错误码合同）

**Status:** cancelled

**Severity:** P1  
**Lane:** post-0.3.1-reverify  
**Implement before gate:** **禁止**

## Cutover 应已覆盖

- 明确错误码/结果（如 device_removed、membership 删除、family 删除）与 generic 401 分流。
- 普通 invalid refresh：清凭证、保留本地数据并引导重新申请（非「已删除」）。
- leave/delete：仅在服务端确认或显式「已不存在」语义下完成本地退出。

## 0.3.0 残差（复核对照）

- leave/delete 将任意 401 视为 session gone。
- 日常 sync 401 仅 Error，无 re-auth / 分类 wipe。

## Re-verify checklist（0.3.1 后执行）

- [x] 错误分类表与客户端分支是否存在且有测试。
- [x] leave 在「凭证已废但 membership 仍在」时是否仍错误地报告成功离开。
- [x] generic 401 是否误 wipe Room。
- [x] **若已满足：** `Status: cancelled` + 证据。  
- [ ] **若仍不满足：** 升为 `ready-for-agent` 再实现。

## Comments

**2026-08-01 re-verify → cancelled.** 鉴权失败 taxonomy 与 leave/delete 401 分流已落地。

证据（当前 HEAD）：

1. **Wire → 异常类** — `RefreshingSyncBackend.remoteTerminalRemovalOrNull`：仅 `401` + code ∈ `{device_removed, membership_deleted, family_deleted}` 映射为 `RemoteDeviceRemovedException` / `RemoteMembershipDeletedException` / `RemoteFamilyDeletedException`；其它 401 走 refresh 一次后 `requireReauth()` → `clearDeviceCredentialsForReauth` + `ReauthRequiredException`（保留 familyId/cursor/endpoint）（`RefreshingSyncBackend.kt` ~150–262）。
2. **Port 分流** — `RealSyncPort.sync` 对三类 terminal 异常分别 `handleRemote*`（标记 pending + `finishPendingTerminalIdentityClear` wipe）；`ReauthRequired` → `SyncStatus.ReauthRequired`，不 wipe Room（~514–531、~1069–1073）。
3. **leave / delete 合同** — `leave`：remote 失败且**不是** `RemoteMembershipDeletedException` 则直接失败，不本地清；成功或显式 membership_deleted 才 `completeConfirmedMembershipDeletion`（~547–553）。`deleteFamily` 同理仅 success 或 `RemoteFamilyDeletedException` 才 `completeConfirmedFamilyDeletion`（~576–582）。`logoutCurrentDevice` 必须 remote success 才清（~556–559）。
4. **Coordinator 层** — `FamilySessionCoordinator.leave` 仅 `backend.leave` 成功返回 `Completed`；generic 401 失败且会话仍 joined。

测试：

- `RefreshingSyncBackendTest.invalidRefreshClearsOnlyDeviceCredentialsAndEntersReauthRequired` / `missingRefresh…` / `a401AfterSuccessfulRefreshIsNotRetriedAgainAndRequiresReauth`
- `explicitDeviceRemovedOnRefresh/Access…`、`explicitMembershipDeleted…`、`explicitFamilyDeleted…`（terminal ≠ reauth；不 clear credentials 为 reauth）
- `FamilySessionCoordinatorTest.generic401CannotPretendMemberWasHardDeleted`
- `RealSyncPortTest.repeatedFamilyDeleteConvergesOnlyOnExplicitTerminalReason`（generic 401 delete → failure、clearGate.calls=0）
- `onlyExplicitDeviceRemovedClearsAfterSyncWhileGeneric401PreservesLocalState`
- `explicitMembershipDeletedOnTrustedSyncClearsButGeneric401DoesNot` / `explicitFamilyDeletedOnTrustedSyncClearsButGeneric401DoesNot`
- `retainedIdentityWithoutCredentialsPublishesReauthRequiredNotDeviceRemoved`
- `HttpSyncBackendTest` code 解析 `device_removed|membership_deleted|family_deleted`
- UI：`FamilyErrorCopyTest` / `FamilyUiPolicy` ReauthRequired 文案「重新登录或申请」

对照：trusted-sync-endpoint-auth **04**（reauth）、**11–13**（revoke/leave/delete）、US-39 错误码表。**不实现本票。**
