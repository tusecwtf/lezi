# 05 — Join 会话耐久顺序（0.3.1 后复核）

**What to build:** （复核票，默认不实现）确认在 0.3.1 可信同步 cutover 之后，加入/创建家庭时**先耐久保存设备会话，再拉取并应用家庭副本**；进程在中途被杀不会留下「有家庭数据却未 joined」或「有凭证却无 family 会话」的不可恢复半态。

**Blocked by:** 0.3.1 发布（`trusted-sync-endpoint-auth` 程序闭合，尤其建家/会话票）

**Status:** cancelled

**Severity:** P1  
**Lane:** post-0.3.1-reverify  
**Implement before gate:** **禁止**

## Cutover 应已覆盖

- trusted-sync：根密码建家/设备会话「session 先耐久再首次 full pull」类要求。
- 目标：两阶段 join，失败可重试同步而不重跑建家。

## 0.3.0 残差（复核对照）

- 先 apply 初始实体再 saveSession；token 与 DataStore 非同一提交。

## Re-verify checklist（0.3.1 后执行）

- [x] 对照 0.3.1 实现与测试：join/create 是否先持久会话再 apply 副本。
- [x] 人为中断/单测是否覆盖「apply 中失败可恢复」且不会出现幽灵副本无会话。
- [x] **若已满足：** `Status: cancelled`，Comments 注明对照票与证据，**不实现本票**。
- [ ] **若仍不满足：** 改写为本版 acceptance，`Status: ready-for-agent`，再开实现。

## Comments

**2026-08-01 re-verify → cancelled.** Cutover 已满足「会话先耐久、再独立 full pull；失败可重试不重跑建家」。

证据（当前 HEAD）：

1. **Create / Owner login 顺序** — `FamilySessionCoordinator.createFamily` / `ownerLogin`：`persistJoin(...)` 返回后再 `recoverReclaimedSession(session)`。源码注释明确：*“The device session is durable before this independent cursor-zero pull. Failure leaves the session intact so foreground retry never reruns create.”*（`FamilySessionCoordinator.kt` ~256–303）
2. **Member claim / QR** — `persistClaimedMemberSession` 先耐久会话（同身份直接 `saveSession`；跨身份 `saveSessionPendingReplicaReset` 再 `recoverPendingReplicaReset`），然后才 `recoverReclaimedSession`（~349–350、~462–463、~711–723）。
3. **Port 侧 recovery** — `RealSyncPort.recoverReclaimedSessionLocked` 在已提交会话上跑 `synchronizeJoinedSessionLocked`；失败映射 `InitialFamilyDataRecovery.RetryRequired`，不撤销会话（~1014–1024）。
4. **saveSession 崩溃安全** — `SyncPreferences.persistSession`：先 DataStore 写身份并置 `REAUTH_REQUIRED`/`PENDING_FAMILY_CREDENTIAL_CLEAR`，再写 Keystore refresh，最后清除 marker；中断 → reauth，不会出现「新 token + 旧 family」可推送会话（~293–359）。对照 trusted-sync-review-residuals **11**。
5. **Bootstrap entities 注记** — `persistJoin` 若 `joined.entities` 非空仍会在 `saveSession` 前 `applyInitialEntities`（~674–677）；但 `SessionBootstrapResult.entities` 默认 empty，合同注释为 *“Current session bootstrap does not carry an entity page”*（`SyncBackend.kt`）。真实副本靠 cursor-zero pull，已在 session 之后。

测试：

- `FamilySessionCoordinatorTest.createFamilyOwnsWireNormalizationInitialApplySessionPublishAndSyncRequest` — 事件序 `session-published` → `initial-pull`
- `createFamilyReclaimPublishesOwnerSessionAtCursorZeroForPortRecovery` — `session-published` → `recovery`
- `ownerLoginPersistsCanonicalSessionBeforeRecoveryAndCarriesTakeoverMode` — recovery 见已持久会话；`RetryRequired` 保留会话
- `approvedMemberClaimGatesTheNewSessionUntilReplicaResetAndNeverClaimsTwice` — claim 一次；首 pull 失败 → `RetryRequired` 且会话仍 joined
- `SyncPreferencesTest.interruptedSessionReplacementPersistsNewIdentityAsReauthBeforeWritingRefreshToken`

对照：trusted-sync-endpoint-auth 建家/会话路径 + review-residuals **07/11**。**不实现本票。**
