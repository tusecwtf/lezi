# Spec: 2026-07-29 P1 冗余与 UI 还债（A–F）

Status: ready-for-agent  
Feature: p1-redundancy-ui-debt  
Source: `docs/reviews/2026-07-29-redundancy-and-ui-review.md` 主题 A–F  
Ticket count: 10  
Frontier count: 6

Related:

- `docs/reviews/2026-07-29-redundancy-and-ui-review.md`
- `CONTEXT.md`（常用记录、记录设置、布局编辑态、护理记录 / 护理计划）
- ADR-0001 / 0003 / 0005 / 0006 / 0008（计划≠记录、照片公共附件、原子包、定义共享布局本机）
- `docs/prd/ui.md` §2.1–2.2、§5.2

---

## Problem Statement

只读审查确认无 P0，但有六类 P1 还债：母乳确认双轨、布局迁移残留、媒体附件双实现、本机清理类型叠层、超大宿主模块、token/模板与 PRD 坞契约漂移。目标是**消歧与单源**，不是重做产品功能。

## Non-goals / 有意保留

- 护理计划与护理记录领域分离（ADR-0001）
- 照片所有权 XOR：`recordId` vs `carePlanId`（ADR-0003）——允许参数化 helper，禁止混所有权
- 原子同步包两 root（ADR-0005/0008）——可合并**机械**管道，不合并领域 root
- 自定义定义家庭共享、布局本机-only（ADR-0006）
- membership ≠ 凭证（ADR-0007）
- 坞不按惯用手镜像槽序（`CONTEXT.md` 已定；应改 PRD 而非改回镜像）
- P2/P3（文案下沉、表单样板、Search 滑动、Tab 指示色等）本 tracker 不收录

## Themes → tickets

| 主题 | 票 |
|------|-----|
| A 母乳双轨确认 | 01 |
| B 布局迁移残留 | 02 |
| C 媒体附件双实现 | 03 · 04 · 05 |
| D 清理 / 失败类型叠层 | 06 |
| E God module | 07 · 08 |
| F Token / 模板 / PRD 坞 | 09 · 10 |

## Dependency graph

```text
01 统一母乳确认面（frontier）

record-layout-edit-remediation/06 ──► 02 布局迁移残留清理

03 全屏照片预览单源（frontier）

04 原子包媒体机械 + customItemUuid 单 helper（frontier）

05 照片 reconcile/tombstone 参数化（frontier） ──► 08 CareLog 第一刀切片

06 清理 scope / committed 失败单源（complete）

09 时间条与记录语义色单源（frontier） ──┐
                                        ├──► 07 LogScreen 结构拆分（建议 09 后或可并行，忌同 PR 混改）
10 journal 主色政策 + PRD 坞条款 + 死组件（frontier） ─┘
```

## Process

- 一票一 PR / 提交序列；`/implement` 一次一张，清 context。
- 纯行为保持：拆文件与抽 helper 必须有回归锁住；禁止顺手改产品交互。
- Documentation Gate：触及 PRD / CONTEXT 契约的票（尤其 01 顺序全集、10 坞与 journal 主色）必须同票改文档。
- 审查报告是**发现源**，实现以 live 代码为准；已单源的路径只做确认与删残留，不重复发明。

## Comments

- 2026-07-30：`/to-tickets` 自 A–F 发布；live 核对：槽 normalize 已在 `core.model` 单源、clear 双异常多为 typealias；死布局对话框可能已删，02 以「生产残留 + 双入口」为准。
