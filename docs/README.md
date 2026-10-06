# 文档

按 [ask-matt](https://github.com/mattpocock) 工程技能的流向组织。读/写前先看 [`agents/domain.md`](./agents/domain.md) 与根 [`CONTEXT.md`](../CONTEXT.md)。

## 按工作流读什么

| 阶段 | 技能 | 读 | 写 |
|------|------|----|----|
| 配置 | `/setup-matt-pocock-skills` | — | [`agents/`](./agents/) |
| 磨想法 | `/grill-with-docs` + `/domain-modeling` | `CONTEXT.md`、相关 ADR | 术语 → `CONTEXT.md`；难逆决策 → [`adr/`](./adr/) |
| 多会话构建 | `/to-spec` → `/to-tickets` | `docs/spec/` / ADR / design | [`.scratch/<feature>/`](../.scratch/)（**不**写 GitHub Issues） |
| 实现与评审 | `/implement`、`/tdd`、`/code-review` | 票 + spec + ADR | 代码与 PR；审查默认**不**落库 |
| 合入后 | — | — | 行为 → [`spec/`](./spec/)；决策 → ADR；票目录删除 |
| 架构巡检 | `/improve-codebase-architecture` | `CONTEXT.md`、ADR | HTML 报告在 **OS 临时目录** |
| 跨会话 | `/handoff` | — | **OS 临时目录**（不进仓库） |

## 目录索引

| 路径 | 说明 |
|------|------|
| **[spec/](./spec/)** | **规格树（合入后行为真源）**：产品/平台/架构 + `layers/`（core/domain/sync/features/server 分层规格）+ `contracts/`（wire/data-model/trusted-endpoint/ui 等跨层合同；索引见 [`spec/README.md`](./spec/README.md)） |
| [adr/](./adr/) | 架构决策记录（含状态索引） |
| [agents/](./agents/) | 本地 issue tracker、triage 标签、domain 消费规则、GitHub PR 约定 |
| [design/](./design/) | Implemented UI companion：家庭认证与同步（`ui.md` / `sync-trusted-endpoint`） |
| [dev/](./dev/) | 开发机工具备忘（如 Cargo worktree 共享 target 示例） |
| [../CONTEXT.md](../CONTEXT.md) | 领域术语表 |
| [../.scratch/](../.scratch/) | 进行中的 spec 与票（见 [agents/issue-tracker.md](./agents/issue-tracker.md)） |
| [../CONTRIBUTING.md](../CONTRIBUTING.md) | 贡献 / 分支 / 双 remote |
| [../SECURITY.md](../SECURITY.md) | 漏洞报告与密钥约定 |
| [../LICENSE](../LICENSE) | MIT |

## 真源优先级

1. 合入后产品与工程规格 → `docs/spec/`（wire 字段唯一权威：`spec/contracts/causal-sync-wire.md`；同步行为合同：`spec/contracts/sync-trusted-endpoint.md`；分层/seam/算法：`spec/architecture.md` + `spec/layers/`）
2. 难逆架构决策 → `docs/adr/` 中 **accepted** 行（见 `adr/README` 状态表）；superseded ADR 只作决策史
3. 领域用词 → 根 `CONTEXT.md`
4. 未写回规格树（`docs/spec/`）的交互细则 → `docs/design/` 中 Status 为 Active / Implemented（Draft 非正式合同）
5. 进行中的票 → `.scratch/`
6. **禁止**当前合同文档链回历史处置文或已 Folded 的 design；历史文若暂留只允许单向指向当前合同

## 不要放进仓库（gitignore / 临时目录）

| 落点 | 说明 |
|------|------|
| `docs/reviews/`、`docs/research/` | 已 gitignore；可选本地审计/调研草稿，**不**替代 `/code-review` 或 PR 结论 |
| handoff、架构巡检 HTML | OS 临时目录 |
| 闭合票过程稿、截图、dump | 不归档；结论写回 `prd/` / ADR / `CONTEXT.md` |
| 可执行票与 blocking 边 | 只在 `.scratch/`（过程产物如 shots/dumps 亦 gitignore） |

## agents 一览

| 文件 | 用途 |
|------|------|
| [agents/domain.md](./agents/domain.md) | 领域文档布局与消费规则（single-context） |
| [agents/issue-tracker.md](./agents/issue-tracker.md) | 本地 Markdown tracker（`.scratch/`） |
| [agents/triage-labels.md](./agents/triage-labels.md) | `Status:` 标签词表 |
| [agents/pull-requests.md](./agents/pull-requests.md) | GitHub PR 与合入 |
