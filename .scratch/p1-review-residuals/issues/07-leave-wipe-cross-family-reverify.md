# 07 — 离开/删家本机擦除与跨家庭隔离（0.3.1 后复核）

**What to build:** （复核票，默认不实现）确认 0.3.1 之后：用户主动退出当前设备或管理员删除家庭后，本机按产品合同清理家庭副本、Outbox、媒体与会话；**不会**把上一家庭的护理事实/照片在加入下一家庭时重新发布到新家庭。产品文案与真实擦除行为一致。

**Blocked by:** 0.3.1 发布（trusted-sync 退出/删家/硬删成员合同）

**Status:** cancelled

**Severity:** P1  
**Lane:** post-0.3.1-reverify  
**Implement before gate:** **禁止**

## Cutover 应已覆盖

- 主动退出当前设备：服务端确认后清 Room、Outbox、media、endpoint、session。
- 删除家庭：全设备安全退出并清本地家庭数据。
- 跨家庭：新家庭不得继承旧家庭 outbox 实体当「自己的待发布」。

## 0.3.0 残差（复核对照）

- clearSession 主要清 sync 配置与 outbox 索引，护理副本可残留。
- 存在「切家庭后 requeue 再发布」类测试编码的行为，与目标隐私合同冲突。

## Re-verify checklist（0.3.1 后执行）

- [x] 退出/删家后本机是否仍可读到原家庭护理记录与照片。
- [x] 退出后再加入/新建另一家庭，是否出现旧家庭事实被 push。
- [x] UI 文案是否仍暗示「已删光」却未擦除（或相反）。
- [x] **若已满足：** `Status: cancelled` + 证据。  
- [ ] **若仍不满足：** 升为 `ready-for-agent` 再实现。

## Comments

**2026-08-01 re-verify → cancelled.** 主动 leave/logout/deleteFamily 在服务端确认后走可恢复全量本机擦除；跨家庭 outbox/副本不再残留可推送。

证据（当前 HEAD）：

1. **确认后终端清** — `RealSyncPort.logoutCurrentDevice` / `leave` / `deleteFamily`：remote 成功（或 leave/delete 上显式 terminal 已不存在语义）→ `markPending*Clear` + `finishPendingTerminalIdentityClear`（~547–582、~921–1010）。
2. **全量家庭数据 gate** — `finishPendingTerminalIdentityClear`：`syncMutex` 内 `removedDeviceLocalClearGate.clearAllLocalFamilyData()` 然后 `preferences.clearAllLocalSyncConfig()` 并清 pending markers。DI：`DomainModule` 将 gate 绑到 `LocalDataClearCoordinator.clear(AllLocalData)`。
3. **Room 护理副本** — `LocalDataClearCoordinator` / persistence：`recordDao`/`fulfillmentCandidateDao`/`carePlanDao.deleteAll()`；`AllLocalData` 再删 `customItem`/`baby`/`membership`/`family`/`localUser`（`LocalDataClearCoordinator.kt` ~103–111）。
4. **Outbox + 媒体文件** — `LocalReplicaClearCoordinator.finish`：`AllLocalData` → `outboxDao.deleteAll()` + 快照路径上 `mediaFiles.delete` + `mediaDao.deleteByClientUuids`（~157–189）。`SyncPort` KDoc：*“AllLocalData also removes avatar media and all outbox rows so a subsequent join cannot push stale residue.”*
5. **跨家庭 join** — `persistJoin` / claim 路径在身份变化时 `resetLocalSyncReceipts(..., crossingFamilyBoundary = previous.familyId != session.familyId)`；pending replica-reset 门闩阻止未 reset 前 push（`FamilySessionCoordinator` ~668–722）。
6. **失败不先清** — remote 503/generic 401：会话与本地数据保持；见 06 与下列测试。

测试：

- `RealSyncPortTest.confirmedCurrentDeviceLogoutStagesFullClearAndRetiresLocalSession`
- `confirmedMemberLeaveStagesFullClearAndRetiresLocalIdentity`
- `confirmedFamilyDeleteStagesFullClearAndRetiresEveryLocalFamilyTrace`
- `failedLogout/Leave/FamilyDeletePreservesEverythingAndInterruptedCleanupResumes*`（pending marker 崩溃恢复）
- `CareLogTest.clearAllLocalDataWipesBabiesCustomItemsAndUsesSyncBarrier`
- `LocalDataClearCoordinatorTest` / `LocalReplicaClearCoordinatorTest` AllLocalData 范围与 outbox/media 收尾
- `FamilySessionCoordinatorTest.ownerReauthSkipsReceiptResetOnlyWhenReplicaIdentityIsUnchanged`（boundary 仅 family 变化）

对照：trusted-sync-endpoint-auth US-29/31/33、票 **11–13**。**不实现本票。**
