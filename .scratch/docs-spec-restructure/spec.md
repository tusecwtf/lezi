---
triage: done
title: 文档全量重构：docs/prd → docs/spec（spec 驱动 + 分层架构）
tracker: .scratch（本仓库工单目录；AGENTS.md 指定 GitHub Issues 非跟踪器）
---

# 文档全量重构：`docs/prd/` → `docs/spec/`

> 依据：2026-09-06 三路 Explore 审查（文档盘点 / 代码架构 / 文档↔代码连线与陈旧检测）+
> 用户批准的重构计划（全量重构 + seam 锚点式接口 + 不加常驻自动校验脚本）。

## Problem Statement

文档集治理成熟（真源优先级、wire 唯一权威、`.scratch/` spec 流程、版本钉身份头），但：

1. **没有按架构分层的 spec 层**：`docs/prd/tech.md` 一文混装栈 / 模块 / 写路径 / 权限 /
   质量门；core、domain、sync、features、server 各层的接口、算法、交互从未逐层成文，
   信息散在代码与 `.scratch/` 过程稿里。
2. **没有模块↔文档↔代码连线表**：新读者（人或 agent）无法从一页看到"每个模块的规格
   在哪、代码入口在哪、测试在哪"。
3. **若干契约只活在 `.scratch/`**：前台跨拍熔断（`ForegroundRoundFuse` cap=3）、失败文案
   四段式（`FailureCatalog` → 产品文案）未写回正式合同。
4. **陈旧点**：tech.md §2.1.1 三行缺子包；data-model §6.2 签名草图漂移；根 README 版本头
   0.3.9；sync-trusted-endpoint §7.1 / tech.md §3.1 仍写"静默无限再入队"；ui.md 无失败
   文案节；design onboarding 未标 Folded。

## Solution

新建 `docs/spec/` 树，按 **产品 → 平台 → 架构（含连线表）→ 分层规格（layers/）→ 跨层合同
（contracts/）** 组织；`docs/prd/` 内容迁移后退役为墓碑。层规格统一模板：身份头（版本钉 +
权界声明）→ 职责与边界 → 公开 seam 表（名称 / 代码文件锚点 / 职责 / 不变量 / 操作按名枚举）
→ 关键算法 → 交互 → 测试契约 → 代码连线。

- **接口深度（用户定稿）**：seam 枚举 + 文件锚点；**签名唯一权威是代码**（沿用 data-model
  §6.1"以 SyncPort.kt 为准"约定），杜绝 §6.2 式签名漂移。
- **节号不变**：`contracts/causal-sync-wire.md` 等迁移文件保持既有 § 编号，ADR /
  `.scratch` 里的外部 § 引用（如 ADR-0023 → wire §12）零失效。
- **不写常驻校验脚本（用户定稿）**：迁移期内用一次性 `rg` / `grep` 检查并记录在票内；
  之后靠 PR checklist 人工保证。

## User Stories

1. 作为接手模块改动的维护者，我想打开一张连线表就找到该模块的层规格、代码入口与测试
   位置，以便不用逆向工程。
2. 作为实现 `.scratch/` 票的 agent，我想在 `docs/spec/layers/` 读到该层的 seam、算法与
   不变量，以便实现前先知合同。
3. 作为评审者，我想让 wire 字段、UI 合同等既有唯一权威的地位在重构后原样保留，以便
   不产生竞争真相。
4. 作为新会话 agent，我想让 `docs/spec/README.md` 的真源优先级与阅读路径指路，以便
   少读无关文档。

## Implementation Decisions

- **目标树**（定稿，见批准计划）：

  ```text
  docs/spec/
    README.md            # 索引 + 真源优先级 v2 + 阅读路径
    product.md           # 产品规格（原 prd/README.md）
    platform.md          # 栈/构建身份/权限/自更新/质量门（原 tech.md §1/§4/§7-§9）
    architecture.md      # 分层模型/依赖边/locality/防回潮 + ★模块连线表（原 tech.md §2 系）
    layers/{core,domain,sync,features,server}.md
    contracts/{causal-sync-wire,data-model,sync-trusted-endpoint,ui,local-photo-loading,assets-notes}.md
  ```

- **迁移期状态**：票 02-05 期间新旧两份并存（`docs/prd/` 原文件不动，spec 侧为副本 +
  修订）；票 06 删除 prd 原文、切全仓引用、留墓碑。若中途只合入到票 05，`docs/prd/`
  仍是真源（spec 树头部署名"迁移中"）。
- **tech.md 拆分去向**：§2 系 → `architecture.md`；§3 写路径 + 超时表 → `layers/sync.md`
  （platform.md 留指针）；§1/§4/§7-§9 → `platform.md`。
- **顺带修复**（并入对应票）：§2.1.1 三行补 `conflict/`、`disasterrecovery/`、
  `networksettings/`、`command/`；§6.2 锚点化；§7.1 + sync 层写跨拍熔断合同；ui.md 补
  失败文案节；根 README 版本头改"以 `app/build.gradle.kts` 为准"不硬编码。
- **不动**：`docs/adr/` 正文与编号、根 `CONTEXT.md`、`docs/agents/`、`docs/dev/`、
  `.scratch/` 流程本身、任何代码。`docs/adr/README.md` 索引表给 0001-0015 补状态列对齐
  （不改写 ADR 正文）。
- **引用切换清单（票 06）**：AGENTS.md、CONTEXT.md、CONTRIBUTING.md、根 README.md、
  docs/README.md、docs/adr/*.md、docs/agents/*.md、docs/design/*.md、
  tools/lezi-sync/{README.md,deploy/DEPLOY.md}、.github/pull_request_template.md、
  活跃 `.scratch/` 树。

## Testing Decisions（一次性人工检查，记录进票）

- 每票收尾：`rg -n "docs/prd" --glob '!docs/prd/**'` 输出只减不增；票 06 后仅剩墓碑与
  ADR/设计历史行（历史行按 docs/README 规则 6 允许单向指向）。
- wire 迁移前后 `grep '^## '` 节号序列 diff 为空。
- `docs/spec/` 内部 markdown 相对链接逐条人审可解析。
- 连线表每个代码入口路径在 tree 中存在（`test -f` 抽检）。

## Out of Scope

- 不改任何代码、Gradle 图、wire、schema。
- 不写常驻文档校验脚本 / CI 步骤（用户明确拒绝）。
- 不重写 ADR 正文、不改 ADR 编号规则。
- `docs/research/`、`docs/reviews/`（gitignore 本地草稿）不动。
- 翻译或改写既有合同语义（迁移是搬运 + 修订标注，不是重新谈判合同）。

## Further Notes

- 前置（票 00）：当前工作树未提交的 0.4.8 文档+代码改动（fuse、ADR-0023 §5、VPS 暂停
  系列）应先由既有流程合入；本树其余票基于"当前工作树内容"执行，不回退这些改动。
- 三路审查结论存档于本票 `## Parent` 指向的批准计划（会话记录）；关键事实已由审查
  agent 以 `路径:行号` 抽检验证。
