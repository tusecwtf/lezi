# 02: dismissUnresolvedLocally 行为

**What to build:** `SyncPort` / `CareLog` `dismissUnresolvedLocally`。本机多出 /
家里拒绝：本机墓碑 + abandoned，且不把这条墓碑推进 commit。拉取洞：只撤 skip
回执并写 `dismissed-skip`，对端补齐后仍可 apply。

**Blocked by:** 01

**Status:** done

- [x] `dismissUnresolvedLocally(entityType, clientUuid, kind)`
- [x] LocalExtra / Rejected：tombstone + `syncDirty=false` + abandoned，LocalWrite 不带该墓碑
- [x] PullHole：删 pull-diagnostic，写 `dismissed-skip:`，`journalReferenceUnready` 跳过
- [x] 宝宝 / 家庭 / 无名聚合：拒绝去掉
- [x] 行为测试：wake 去掉后普查对齐、commit 单元不含该墓碑

## Comments

`SyncPort` / `CareLog` / `ReplicaSyncEngine.dismissUnresolvedLocally`。宝宝/家庭/聚合拒绝去掉。
`CausalDaos` 增加 `dismissed-skip:`；`journalReferenceUnready` 跳过已撤的洞。

Ran: `./gradlew :sync:testDebugUnitTest --tests …ReplicaSyncEngineCensusReconcileTest`.
