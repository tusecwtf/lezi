# 产品规格目录 — 已退役（2026-09-06）

本目录已由 [`docs/spec/`](../spec/README.md) 全量取代（spec 驱动 + 分层架构重构；
工单 [`.scratch/docs-spec-restructure/`](../../.scratch/docs-spec-restructure/spec.md)）。

- 产品/平台/架构规格：[`docs/spec/`](../spec/README.md)（`product.md` / `platform.md` /
  `architecture.md`）
- 分层规格：[`docs/spec/layers/`](../spec/layers/)（core / domain / sync / features / server）
- 跨层合同：[`docs/spec/contracts/`](../spec/contracts/)（wire 唯一权威、data-model、
  trusted-endpoint、ui、photo-loading、assets）

原文件与迁移对照：`README.md`→`spec/product.md`；`tech.md`→`spec/platform.md`（§1/§4-§9）
+ `spec/architecture.md`（§2）+ `spec/layers/sync.md`（§3）；`causal-sync-wire.md`/
`data-model.md`/`sync-trusted-endpoint.md`/`ui.md`/`local-photo-loading.md`/
`assets-notes.md`→`spec/contracts/` 同名（§ 节号不变）。

**不得**从 git 历史恢复本文档作为现行合同（处置规则沿用
`spec/contracts/sync-trusted-endpoint.md` §12）。
