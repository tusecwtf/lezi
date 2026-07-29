# 03 — Android 出站全部走 atomic bundle

**What to build:** 客户端 Outbox **不再调用 ordinary push** 发布实体。Record/CarePlan 维持现有 package 路径；baby（可含 avatar 媒体）、custom_item、fulfillment_candidate 改为 stage/commit bundle。deterministic `bundle_id` 按根类型+uuid+updatedAt 生成。

**Blocked by:** 02 — 服务端须先接受扩展根类型并退役 ordinary。

**Status:** ready-for-agent

- [ ] `OutboxPushPipeline` residual 改为 bundle 发布（无 `backend.push` 产品路径）
- [ ] `AtomicBundleId` 支持 baby / custom_item / fulfillment_candidate
- [ ] avatar 单独 outbox 时以当前 baby 为根打包
- [ ] `RealSyncPort` / outbox 相关单测更新并通过
- [ ] 注释与错误文案不再描述 ordinary residual 发布
