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
| [0008](./0008-support-only-fresh-current-product-contracts.md) | 全产品只支持 fresh-current 契约 | partially superseded by ADR-0012 (Android local); NAS fresh-current + fail-closed startup still apply; offline cutover exception only in ADR-0013 |
| [0009](./0009-family-identity-and-account-overview.md) | 家庭身份用设备 membership 称呼，账户首屏只做家庭概览 | superseded by ADR-0011 |
| [0010](./0010-trust-server-identity-not-network-name.md) | 家庭同步信任服务器身份而不是网络名称 | superseded by ADR-0011 |
| [0011](./0011-root-admin-and-multi-device-membership.md) | 根密码声索唯一管理员，成员与设备分层 | accepted |
| [0012](./0012-preserve-android-local-data-across-in-place-upgrades.md) | Android 原地升级永久保留已承诺的本地数据 | accepted |
| [0013](./0013-offline-migrate-is-maintenance-window-cutover.md) | offline-migrate 是已授权维护窗中的离线切割工具 | accepted |
| [0014](./0014-owner-device-restores-only-empty-family-servers.md) | 只允许旧 Owner 设备恢复空家庭服务器 | accepted |
| [0015](./0015-isolate-lan-invite-install-distribution.md) | 隔离家庭 LAN 邀请首装分发与可信同步 | accepted |
| [0016](./0016-reconcile-before-ephemeral-publish-planning.md) | 先对账，再从 Room 临时规划家庭发布 | partially superseded by ADR-0017（保留先对账/临时 plan，取代无 head 协议限制） |
| [0017](./0017-authoritative-reconcile-settles-local-deltas.md) | 家庭服务器权威裁决必须终结本机待对账修改 | accepted |

## Numbering

Scan this directory for the highest existing number and increment by one. Do not reuse a number, even if an ADR is superseded.

## When to add an ADR

Only when all three hold: hard to reverse, surprising without context, and the result of a real trade-off. Product behaviour that is not a lasting architecture choice belongs in [`docs/prd/`](../prd/). Domain *terms* belong in root [`CONTEXT.md`](../../CONTEXT.md).

See also [`docs/agents/domain.md`](../agents/domain.md).
