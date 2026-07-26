# 26 — 并发履行的确定性权威裁决

**What to build:** 当多个离线设备履行同一计划时，以服务器认证证据稳定选择一个权威事实，使所有设备最终得到同一计划状态和唯一普通护理记录。

**Blocked by:** 25 — 跨成员履行的原子共享闭环.

**Status:** done

- [x] 裁决键依次为：提交者是否管理员、不可编辑履行确认时间更早、候选 UUID 稳定排序
- [x] NAS 到达顺序、设备更新时间、可编辑实际发生时间和后续角色变化不得改变既定比较事实
- [x] 服务端在候选首次接受时固定认证 membership、角色证据和确认时间，后续请求不能改写
- [x] 权威候选成为计划唯一关联事实；落选候选标记 conflict-not-adopted 并保留审计内容
- [x] 落选候选及其 Record/照片不进入普通时间轴、汇总、搜索或普通导出
- [x] 分页、不同同步到达顺序和全量重同步均收敛到同一赢家
- [x] 冲突裁决不会删除落选证据或误删权威附件
- [x] 服务端属性测试和双设备集成测试覆盖管理员优先、时间、UUID、到达顺序与幂等重放

Notes (care-w14):
- Pure `FulfillmentAuthority` (core/model): admin/owner > earlier frozen confirmed_at > clientUuid.
- Room v16 `adoptionStatus` on `fulfillment_candidates` (local-only mark; not wire).
- CareLog + RealSyncPort re-resolve after fulfill/apply; re-link plan.fulfilledRecordClientUuid without dirtying for LWW fights.
- Ordinary RecordDao queries exclude `conflict_not_adopted` record UUIDs (timeline/summary/search/export/listByType).
- Loser Record/photos retained (getByClientUuid / audit); ticket 27 convert UI left alone.
- FakeSyncBackend freezes submitter stamps like lezi-sync.
- Tests: FulfillmentAuthorityTest; CareLog multi-candidate admin + earlier-time; RealSyncPort arrival-order + UUID tie + idempotent replay; cargo freeze idempotent; migration 15→16.

Notes (residual fix):
- ensureFulfillmentCandidate stamps local membership/role offline trail for originator adjudication.
- applyFulfillmentCandidate merges server-frozen stamps on equal-updatedAt originator pull (ticket 26 multi-device convergence).
- Test: originatorPullMergesServerFrozenStampsOnEqualUpdatedAt.

Notes (FIX care-w14):
- Re-verified all 8 acceptance boxes against code + tests; no remaining product gaps.
- Closed residual coverage: multiCandidate admin path now uses joined Member session auto-stamp (no manual role patch); added fulfillCarePlanStampsLocalSubmitterTrailFromJoinedSession; FakeSyncBackend freeze/idempotent unit test mirrors cargo.
- Entity comments clarify offline trail vs server freeze.
- Focused green: FulfillmentAuthorityTest; CareLog multiCandidate* + stamp trail; RealSyncPort multiCandidate* + originatorPull merge; FakeSyncBackend freeze; cargo fulfillment (2); migration 15→16 already present.
