# 01 — 产品表面与行为合同清单（research）

**Type:** research  
**Status:** resolved  
**Blocked by:**

## Question

以 `docs/prd/`、`docs/adr/`、`CONTEXT.md` 与当前发布线（0.3.x）为权威，整理**端到端功能一致**所必须覆盖的产品表面清单：用户旅程、关键不变量、明确不做项、以及 UI 信息架构主屏。输出应可直接喂给后续「薄 E2E 金线」与「截图金线」grilling，而不是实现方案。

## Acceptance of answer

- 按域分组的能力/旅程表（记录、计划、同步、家庭身份、更新、导出等）
- 每条标注：文档锚点（路径 + 节）
- 标出「用户可见」vs「纯实现」边界，避免把实现细节写进一致性合同

## Answer

**Gist:** 0.3.7 端到端 parity 表面已按域入库：~102 条用户可见旅程/能力行、28 个截图候选主屏、20 条跨域不变量、22 条明确不做、16 条 E2E skeleton；每条带 PRD/ADR/CONTEXT 锚点，并区分 UV / UV-shallow / IMPL。

**Findings:** [`.scratch/greenfield-rewrite-blueprint/assets/01-product-surface-inventory.md`](../assets/01-product-surface-inventory.md)

**For later grilling:** 文档漂移（`data-model.md` §6.4「硬家网」vs 当前 trusted-endpoint 任意网络）、`ui.md` §6 旧共享文案、冲突未采纳履行 UI 厚度、warm/journal 双模板截图策略。

**Git:** research asset + this ticket answer are on disk only; this research subagent session had no shell/git tool, so branch `research/product-surface-inventory` was **not** created/committed. Parent/operator may `git checkout -b research/product-surface-inventory` and commit just these two paths.