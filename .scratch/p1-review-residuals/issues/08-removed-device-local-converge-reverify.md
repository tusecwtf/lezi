# 08 — 被踢/撤设备本机收敛（0.3.1 后复核）

**What to build:** （复核票，默认不实现）确认 0.3.1 之后：管理员撤销某台设备或删除某成员后，该设备在**下次可信连接**收到明确结果并按合同收敛本地状态（例如 device_removed 清空家庭数据；与「普通凭证丢失保留本地」区分）。不要求离线即时远程擦除，但产品不得假装仍「已加入且可同步」却无限 Sticky Error。

**Blocked by:** 0.3.1 发布（trusted-sync 设备撤销、成员硬删、客户端 wipe 信号）

**Status:** cancelled

**Severity:** P1  
**Lane:** post-0.3.1-reverify  
**Implement before gate:** **禁止**

## Cutover 应已覆盖

- 撤销单台设备 → 明确 device_removed → 清本地家庭数据。
- 成员硬删 → 身份消失 + 受影响设备收敛。
- 与 generic 401 / refresh 丢失路径区分（见 06）。

## 0.3.0 残差（复核对照）

- 服务端 remove 会吊销凭证；客户端无分类处理，本地仍显示 joined 且保留副本。

## Re-verify checklist（0.3.1 后执行）

- [x] 被撤设备在再次同步时是否收到明确信号并 unjoin/wipe（按合同）。
- [x] 是否与「refresh 丢失保留本地」路径混淆。
- [x] 管理员侧 remove 后被踢端是否长期卡在无意义 Error。
- [x] **若已满足：** `Status: cancelled` + 证据。  
- [ ] **若仍不满足：** 升为 `ready-for-agent` 再实现。

## Comments

**2026-08-01 re-verify → cancelled.** 被撤设备 / 成员硬删 / 家庭删除在下次可信同步时明确收敛；与 ReauthRequired 路径分离。

证据（当前 HEAD）：

1. **映射** — `RefreshingSyncBackend` 将 access/refresh 上的 `device_removed|membership_deleted|family_deleted` 抛为专用异常，**不**走 `requireReauth`（~171–186、~224–226、~255–262）。
2. **同步收敛** — `RealSyncPort.sync`：
   - `RemoteDeviceRemovedException` → `handleRemoteDeviceRemoved` → `markPendingDeviceRemovalClear` + `finishPendingTerminalIdentityClear`（wipe + clearAllLocalSyncConfig）+ `updateFailureStatus` 返回 failure（状态 Disabled，非无限 Sticky joined Error）（~515、~932–945、~999–1010、~1071）。
   - `RemoteMembershipDeletedException` / `RemoteFamilyDeletedException` 同构（~958–990）。
3. **崩溃可恢复 marker** — `SyncPreferences.markPendingDeviceRemovalClear`（及 membership/family 变体）在 clear 前使凭证不可用；进程启动 `init`：`hasPending*Clear` → `finishPendingTerminalIdentityClear`（`RealSyncPort` ~153–162）。
4. **与 reauth 区分** — invalid/missing refresh → `clearDeviceCredentialsForReauth` 保留 family 身份与 Room；`SyncStatus.ReauthRequired`；`isEnabled()==false` 但不 wipe（见 06 测试与 `retainedIdentityWithoutCredentialsPublishesReauthRequiredNotDeviceRemoved`）。
5. **本机自撤** — `revokeFamilyDevice(self)` / `logoutCurrentDevice` 确认后 `completeConfirmedDeviceRemoval` 同路径 wipe。

测试：

- `RealSyncPortTest.onlyExplicitDeviceRemovedClearsAfterSyncWhileGeneric401PreservesLocalState`
- `explicitMembershipDeletedOnTrustedSyncClearsButGeneric401DoesNot`
- `explicitFamilyDeletedOnTrustedSyncClearsButGeneric401DoesNot`
- `failedLogoutPreservesEverythingWhileInterruptedCleanupResumesFromDurableMarker`（pending device-removal 恢复）
- `terminalIdentityClearBlocksConcurrentSyncUntilLocalDataAndCredentialsAreRetired`
- `RefreshingSyncBackendTest.explicitDeviceRemovedOnRefreshIsTerminalAndDoesNotBecomeOrdinaryReauth`（及 access/membership/family 对称用例）
- `SyncPreferencesTest` pending device-removal marker / credential gating
- 服务端 wire：trusted-sync evidence `api-matrix.json` device_removed / membership_deleted / family_deleted 401 bodies

对照：trusted-sync-endpoint-auth **11**（device revoke）、**12**（member hard-delete）、**13**（delete family）、US-28/30/33/39。**不实现本票。**
