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
| [0016](./0016-reconcile-before-ephemeral-publish-planning.md) | 先对账，再从 Room 临时规划家庭发布 | partially superseded by ADR-0017 and ADR-0022; Room facts remain truth, one immutable envelope per pending mutation is allowed |
| [0017](./0017-authoritative-reconcile-settles-local-deltas.md) | 家庭服务器权威裁决必须终结本机待对账修改 | partially superseded by ADR-0019/0020 for 0.3.13+ causal LWW vocabulary; settle-before-publish skeleton retained |
| [0018](./0018-neighbor-duplicate-records-and-tombstone-wins.md) | 跨成员近邻重复由服务器隐式落选，护理记录墓碑永胜 | partially superseded by ADR-0021 for 0.3.13+ new data; historical tombstones stay hidden |
| [0019](./0019-server-validates-constraints-not-care-truth.md) | 服务器验证约束，不裁决护理真相 | accepted |
| [0020](./0020-stable-projection-immutable-versions-and-branches.md) | 稳定投影 + 不可变版本/分支，三方合并与显式 resolution | partially superseded by ADR-0022 for 0.4.0 commit-first and choice-only resolution; causal versions/branches retained |
| [0021](./0021-wake-observation-and-nondestructive-duplicate-groups.md) | WakeObservation 与非破坏性疑似重复分组 | partially superseded by ADR-0023 for auto source-relation close; non-destructive provenance and no-tombstone retained |
| [0022](./0022-commit-first-choice-only-conflict-snapshots.md) | Commit-first 与 choice-only ConflictSnapshot | accepted |
| [0023](./0023-auto-near-neighbor-source-relations.md) | 近邻同型自动写成来源关系 | accepted |
| [0024](./0024-timeline-absolute-rail-and-sticky-selected-day.md) | 一日时间条以绝对瞬时渲染，选中日由粘性规则推导 | accepted |

## Numbering

Scan this directory for the highest existing number and increment by one. Do not reuse a number, even if an ADR is superseded.

## When to add an ADR

Only when all three hold: hard to reverse, surprising without context, and the result of a real trade-off. Product behaviour that is not a lasting architecture choice belongs in [`docs/spec/`](../spec/). Domain *terms* belong in root [`CONTEXT.md`](../../CONTEXT.md).

See also [`docs/agents/domain.md`](../agents/domain.md).
