# 06: 切换与退役——全仓引用切到 docs/spec，prd 墓碑化，治理收尾

## What to build

1. 全仓把指向 `docs/prd/...` 的引用切到 `docs/spec/...`（含 § 锚引用，节号不变所以
   锚引用只换路径）：AGENTS.md、CONTEXT.md、CONTRIBUTING.md、根 README.md、
   docs/README.md、docs/adr/*.md、docs/agents/*.md、docs/design/*.md、
   tools/lezi-sync/README.md、tools/lezi-sync/deploy/DEPLOY.md、
   .github/pull_request_template.md、活跃 `.scratch/` 树（闭合票历史正文不改——过程稿
   属 git 历史）。
2. 删除 `docs/prd/` 下 7 个原文件，留 `docs/prd/README.md` 墓碑（指向 `docs/spec/`，
   模式同 `DEPLOY-VPS.md`：禁止从 git 历史恢复旧合同文）。
3. 治理收尾：
   - `docs/README.md` 真源优先级与目录索引更新（prd 行改 spec 行；补 layers/contracts
     一行）。
   - `CONTRIBUTING.md` 与 `.github/pull_request_template.md`：PR checklist 增补
     "架构 / 接口 / 算法变更须同步 `docs/spec/architecture.md` 或对应 `layers/` 规格；
     wire 变更仍走 `contracts/causal-sync-wire.md`"。
   - `docs/adr/README.md` 索引表为 0001-0015 补状态列对齐（正文不动）。
   - `docs/design/2026-07-30-trusted-sync-onboarding-ui.md` 标 `Status: Folded`（行为
     已在 ui.md / sync-trusted-endpoint.md），`docs/design/README.md` 表同步。
   - `.scratch/README.md` active 列表登记本树。
4. `docs/spec/README.md` 撤掉"迁移期"声明。

## Blocked by

01-05（全部内容就位）

## Status

done

- [x] `rg -n "docs/prd" --glob '!docs/prd/**' --glob '!.git'` 仅剩：prd 墓碑自身、ADR/设计/闭合票的历史行（单向指向当前合同的允许残留为零——历史行中活的路径引用也已切换，仅文句性提及保留）
- [x] `docs/prd/` 仅剩墓碑 README
- [x] PR 模板与 CONTRIBUTING 含 docs/spec 同步条款
- [x] wire/endpoint/ui 的 § 锚引用（ADR-0023→wire §12、prd README→ui §5.2 等）逐条人审可解析

## Parent

[`../spec.md`](../spec.md)
