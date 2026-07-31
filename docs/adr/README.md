# Architecture Decision Records

Hard-to-reverse decisions, written by `/domain-modeling` (via `/grill-with-docs` or architecture review). Format: sequential `NNNN-slug.md`. Index of status lives here; each file may also carry frontmatter.

| # | Title | Status |
|---|-------|--------|
| [0001](./0001-separate-care-plans-from-care-records.md) | 将未来护理计划与已发生护理记录分离 | superseded by ADR-0008 |
| [0002](./0002-retire-generic-record-entry-points.md) | 停止新建泛化记录但保留历史兼容 | superseded by ADR-0008 |
| [0003](./0003-make-photos-common-record-attachments.md) | 将照片建模为通用记录附件 | superseded by ADR-0008 |
| [0004](./0004-use-graded-system-calendar-disclosure.md) | 系统日历采用三级内容披露 | accepted |
| [0005](./0005-sync-records-and-photos-atomically.md) | 护理记录、护理计划与照片原子同步 | superseded by ADR-0008 |
| [0006](./0006-share-custom-item-definitions-not-layout.md) | 家庭共享自定义项目定义但不共享布局 | accepted |
| [0007](./0007-separate-family-membership-from-credentials.md) | 家庭 membership 与访问凭证分离 | superseded by ADR-0008 |
| [0008](./0008-support-only-fresh-current-product-contracts.md) | 全产品只支持 fresh-current 契约 | accepted |
| [0009](./0009-family-identity-and-account-overview.md) | 家庭身份用设备 membership 称呼，账户首屏只做家庭概览 | superseded by ADR-0011 |
| [0010](./0010-trust-server-identity-not-network-name.md) | 家庭同步信任服务器身份而不是网络名称 | superseded by ADR-0011 |
| [0011](./0011-root-admin-and-multi-device-membership.md) | 根密码声索唯一管理员，成员与设备分层 | accepted |

## Numbering

Scan this directory for the highest existing number and increment by one. Do not reuse a number, even if an ADR is superseded.

## When to add an ADR

Only when all three hold: hard to reverse, surprising without context, and the result of a real trade-off. Product behaviour that is not a lasting architecture choice belongs in [`docs/prd/`](../prd/). Domain *terms* belong in root [`CONTEXT.md`](../../CONTEXT.md).

See also [`docs/agents/domain.md`](../agents/domain.md).
