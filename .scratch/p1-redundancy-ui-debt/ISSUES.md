# 2026-07-29 P1 冗余与 UI 还债（A–F）— 票索引

Spec: [spec.md](./spec.md)  
Status: ready-for-agent  
Source: `docs/reviews/2026-07-29-redundancy-and-ui-review.md`  
Ticket count: 10  
Frontier count: 3

## 依赖图

```text
01 统一母乳确认面（complete）

record-layout-edit-remediation/06 ──► 02 布局迁移残留清理

03 全屏照片预览单源（complete）

04 原子包媒体机械 + customItemUuid 单 helper（frontier）

05 照片 reconcile/tombstone 参数化（complete） ──► 08 CareLog 第一刀切片（complete）

06 清理 scope / committed 失败单源（complete）

09 时间条与记录语义色单源（frontier） ──┐
                                        ├──► 07 LogScreen 结构拆分
10 journal 主色政策 + PRD 坞条款 + 死组件 ─┘
```

**可立即开工（frontier）：** 04 · 09 · 10

**建议顺序（冲突面）：** 先 01、03–04 / 09–10 中小票；02 等布局整改 06 完成；07 避免与 09 同 PR 改时间条色；08 已完成。

## 票列表

| ID | 标题 | 主题 | Blocked by | Size | 状态 |
|----|------|------|------------|------|------|
| [01](./issues/01-unify-nursing-confirm-surface.md) | 统一母乳确认面（Composer + 计时完成） | A | — | L | complete |
| [02](./issues/02-layout-migration-residue-cleanup.md) | 布局迁移残留与自定义管理单表面 | B | record-layout-edit-remediation/06 | M | ready-for-agent |
| [03](./issues/03-shared-photo-preview-dialog.md) | 全屏照片预览单源 | C | — | M | complete |
| [04](./issues/04-atomic-media-pack-and-custom-uuid-helper.md) | 原子包媒体机械与 customItemUuid 单 helper | C | — | M | ready-for-agent |
| [05](./issues/05-parameterized-photo-reconcile.md) | 照片 reconcile/tombstone 按所有者参数化 | C | — | M | complete |
| [06](./issues/06-clear-scope-and-committed-failure-single-source.md) | 本机清理 scope 与 committed 失败单源 | D | — | M | complete |
| [07](./issues/07-log-screen-structural-split.md) | LogScreen 结构拆分（轨 / 坞 / 宿主） | E | 建议 09 | L | ready-for-agent |
| [08](./issues/08-carelog-first-slice.md) | CareLog 第一刀：展示 helper 与照片附件 | E | 05 | L | complete |
| [09](./issues/09-timeline-record-color-single-source.md) | 时间条与记录语义色单源 | F | — | M | ready-for-agent |
| [10](./issues/10-journal-primary-and-prd-dock-contract.md) | journal 主色政策 + PRD 坞条款 + 死组件 | F | — | M | ready-for-agent |

## 过程纪律

- 一票一 PR；宽重构只做 expand–contract，保证每步绿灯。
- P1/02 是布局旧表面清理的唯一实现票；不要在布局整改 tracker 或综合审计 Ticket 25 重复删除。
- 不合并护理计划与护理记录概念；不恢复坞惯用手镜像。
- 审查编号（R1–U4 等）仅溯源，不以报告内过时符号路径为验收依据。

## Comments

- 2026-07-30：to-tickets 发布；G/H 与审查第二/三梯队未收录。
