# 02: 履行候选有终态，待同步归零（P0-A）

**What to build:** 履行候选永久发不出去（服务端 409/422、父记录/计划已被终态拒绝或放弃、
成员非权威宝宝）时静默写 `abandoned` 回执：不计入待同步、不出未采纳卡片、不阻塞同批其它
候选。重新履行产生新 epoch 后自然重新进入待发布。

**Blocked by:** None (can start immediately)

**Status:** done

- [x] `PendingPublishDao.observeCount` 与 `PendingPublishInspector.hasPendingPublishUnits`
      对候选加 `terminal-receipt:fulfillment_candidate:<uuid>` + `contentEpoch = updatedAt` 逃逸
- [x] `EphemeralPublishPipeline`：stage/commit 收到 409/422 → 写 `abandoned` 回执、consume、
      继续下一条；5xx/传输失败照旧抛出
- [x] 成员非权威宝宝分支写同款 `abandoned` 回执（本机保留内容，不计入家庭待同步）
- [x] `CausalSettlement.abandonMutation("record"|"care_plan")` 与 `recordTerminalRejection`
      命中 record/care_plan 时级联到引用它的 dirty 候选
- [x] `unacceptedFact()` 过滤 `fulfillment_candidate` 回执（不出卡片）
- [x] SyncRig：候选 409 / 同批续发 / 成员非权威 / abandon 级联 → 一轮后 `pendingPublishCount == 0`、
      无卡片；真服务端 IsolatedLeziSyncServer seam 推迟（见 Comments）
- [x] `layers/sync.md` §3 把「FulfillmentCandidate 不再直接求和 dirty 行」落成实际行为

## 证据

- `PendingPublishDao.kt:64-67`、`PendingPublishInspector.kt:67`
- `EphemeralPublishPipeline.kt:34-37`（成员 consume 不 markSynced）、`:43-52`（409 直抛）
- `bundles.rs:1341-1372`（引用从未被接受的记录/计划 → `UnresolvedReference` → 409；
  `existing` 含 tombstone，已删不触发）
- `CausalSettlement.kt:1133-1160`、`:1329-1381` 不处理候选
- `ReplicaSyncEngine.kt:418-423`：候选残留 → `ReplicaSyncNotConvergedException` → 续跑 →
  三轮熔断 →「暂时无法同步」

## Comments

候选永久发不出去时静默写 `abandoned` 回执：`PendingPublishDao` / inspector / SyncRig 计数
都按 `terminal-receipt:fulfillment_candidate:<uuid>` + epoch 逃逸；pipeline 对 409/422
与成员非权威宝宝写回执后 consume、不 `markSynced`、不阻塞同批；`abandonMutation` /
`recordTerminalRejection` 命中 record/care_plan 时级联 dirty 候选；`unacceptedFact()`
再滤掉 `fulfillment_candidate`。成员本机子树不再提前 `markSynced` 候选；`settleMemberLocalOnlySubtrees` 当场写
`abandoned` 回执，避免本轮因果 5xx 让管道写不到回执时待同步仍卡住。

真服务端 IsolatedLeziSyncServer seam **推迟**：现成媒体故障 fixture 偏大，再为
`UnresolvedReference` 新开家庭引导/种子工厂会挡住本票；SyncRig 已覆盖 409、同批续发、
成员非权威、abandon 级联。

Ran: `./gradlew :sync:testDebugUnitTest --tests com.lezi.babylog.sync.RealSyncPortCarePlanFulfillTest --tests …ReplicaSyncEngineCausalSettlementTest.abandoningRecordWritesAbandonedReceiptForDirtyFulfillmentCandidate --tests …abandoningRejectedFactKeepsFactAndExcludesFromSyncUntilEdited` — 15 fulfill + 2 settlement passed. `:core:database:testDebugUnitTest` — Room SQL compile green.

Review 收敛（全量）：`captureLocalChanges` 与 pipeline 对同 epoch `terminal-receipt:fulfillment_candidate` 跳过，第二轮不再 stage 已放弃候选。409 用例已锁第二轮 stage 计数不变。
