# 02 — 服务端退役 ordinary `/v1/push`

**What to build:** NAS `lezi-sync` **完整去除 ordinary 发布路径**：实体发布只接受 atomic bundle。根类型扩展为 `record | care_plan | baby | custom_item | fulfillment_candidate`；bundle 媒体规则：record/care_plan → `kind=log`，baby → `kind=avatar`，custom/fulfillment → 无媒体。HTTP `/v1/push` fail-closed（明确 422/退役文案）。GET 媒体下载可保留；ordinary PUT log 不得成为发布旁路。

**Blocked by:** 01（契约文案）— 实现前至少对齐 ADR/PRD 表述。

**Status:** ready-for-agent

- [ ] 扩展 `AtomicBundleRoot` 校验与 stage 根类型白名单
- [ ] bundle 媒体 kind 与根类型匹配校验
- [ ] `/v1/push` 退役（不可半套 apply）
- [ ] 拒 ordinary log 媒体元数据发布；夹具/种子改为 bundle
- [ ] `cargo test`（lib + api）全绿；旧 ordinary 语义测试改为退役断言或 bundle 路径
