# 02 — 复杂度热点与偶然耦合清单（research）

**Type:** research  
**Status:** resolved  
**Blocked by:**

## Question

只读勘察当前树，列出重写蓝图必须正视的**复杂度与耦合事实**（非修复方案）：超大文件/测试、跨层依赖、双轨实现、文档与代码漂移、已知 god 表面。用于后续分层与测试策略决策的对照材料。

## Acceptance of answer

- 按层（app/feature/domain/sync/core/lezi-sync/tests）的热点表：路径、量级、为何贵
- 偶然耦合边（feature↔feature、UI 文案下沉、协议与 UI 缠绕等）证据指针
- 明确区分：有意 seam（如 ADR 保留的概念分离）vs 偶然冗余

## Answer

**Gist:** 2026-08 分包后，生产侧最大审查成本仍在 **ReplicaSyncEngine / RealSyncPort / lezi-sync Store+bundles+model**；更极端的是三份巨型合同测（`api.rs` ~14k、`RealSyncPortTest` ~11k、`CareLogTest` ~8k）。feature 互不 Gradle 依赖；偶然耦合主要是 **UI 文案与 AppUpdate 对话框下沉到 `:sync`/`core:ui→sync`**、domain 向导状态机吃满 sync 类型、record/plan 推送机械孪生、自定义项双入口。有意 seam（计划≠记录、原子包、定义≠布局、membership≠凭证、deep façade、`:sync` 横向边）见 ADR/tech.md，勿当冗余合并。

**Full inventory:** [assets/02-complexity-hotspot-inventory.md](../assets/02-complexity-hotspot-inventory.md)
