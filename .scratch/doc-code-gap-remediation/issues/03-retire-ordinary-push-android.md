# 03 — Android 出站全部走 atomic bundle

**What to build:** 客户端 Outbox **不再调用 ordinary push** 发布实体。Record/CarePlan 维持现有 package 路径；baby（可含 avatar 媒体）、custom_item、fulfillment_candidate 改为 stage/commit bundle。deterministic `bundle_id` 按根类型+uuid+updatedAt 生成。

**Blocked by:** 02 — 服务端须先接受扩展根类型并退役 ordinary。

**Status:** complete

- [x] `OutboxPushPipeline` residual 改为 bundle 发布（无 `backend.push` 产品路径）
- [x] `AtomicBundleId` 支持 baby / custom_item / fulfillment_candidate
- [x] avatar 单独 outbox 时以当前 baby 为根打包
- [x] `RealSyncPort` / outbox 相关单测更新并通过
- [x] 注释与错误文案不再描述 ordinary residual 发布

## Evidence

- `SyncBackend` 与 `HttpSyncBackend` 不再暴露 `/v1/push` 或普通媒体 PUT；所有出站根均经
  `stageBundle` / `putBundleMedia` / `commitBundle`。
- Baby 与 avatar 共包；独立 avatar 使用当前 Baby 快照作为根。CustomItem 与
  FulfillmentCandidate 使用空媒体包，bundle id 均由根类型、UUID 与包版本确定。
- `./gradlew :sync:testDebugUnitTest`：274 tests 全部通过。
