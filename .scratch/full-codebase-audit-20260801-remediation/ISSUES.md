# 2026-08-01 全库审计整改 · 票索引

Spec: [spec.md](./spec.md)  
**Status:** ready-for-agent  
Audit baseline: `3df1c3ebcbee0b582621405e1510ecb9deea1d8c`  
Current validation HEAD: `0aa225bf25b21315dec3a84983124086ff9858d1`

## Current audit disposition

- Findings: **21**（P1 14、P2 7）
- Still valid on current HEAD: **21**
- Fixed / rejected / duplicate active tickets: **0 / 0 / 0**
- 所有 ticket 均为 `ready-for-agent`；P2 不是 Release blocker 的同义词，但仍是已确认的待办。

## Coordination · architecture-readability-optimization

并行 tracker [`.scratch/architecture-readability-optimization/`](../architecture-readability-optimization/)（grill 锁定可读性程序）：

- **16** 与可读性 **04** 同一 SyncPort 死 `pull`/`push`/`saveServer`/`isEnabled` 表面 — **once-only**。
- **17** 与可读性 **05** 同一 tech.md Gradle 边 — **once-only**。
- **18** 大拆 SyncPort 能力端口：**可读性 tracker 明确不做**（对抗核验收窄为 prune-only）。18 仍仅在本审计 tracker 打开，直到产品重开完整 capability split。

## Dependency graph

```text
01 ─► 16 ────────────────┐
02 ─► 03 ────────────────┼─► 18
02 ─► 07 ─► 15           │
04 ─► 05                 │
08 ─► 09                 │
10 ─► 11                 │
14 ─► 21                 │
06、12、13、17、19、20 独立
```

## Frontier

当前 frontier：**01、02、04、06、08、10、12、13、14、17、19、20**。

## Tickets

| ID | Audit ID | Ticket | Blocked by | Size | Status |
|---|---|---|---|---|---|
| [01](./issues/01-preserve-forced-update-shell.md) | P1-01 | 保留 CUR 强制更新壳 | — | S–M | ready-for-agent |
| [02](./issues/02-cas-media-commit-receipts.md) | P1-02 | 用条件回写确认媒体 commit | — | M | ready-for-agent |
| [03](./issues/03-acknowledge-synthetic-bundle-roots.md) | P1-03 | 对齐独立媒体包的根发布回执 | 02 | M | ready-for-agent |
| [04](./issues/04-freeze-fulfillment-candidate-evidence.md) | P1-04 | 冻结履行候选业务证据 | — | M | ready-for-agent |
| [05](./issues/05-require-complete-fulfillment-pair.md) | P1-05 | completed 计划必须原子绑定完整 pair | 04 | S–M | ready-for-agent |
| [06](./issues/06-clear-nursing-timer-with-local-data.md) | P1-06 | 本机清空同步停止并清除计时器 | — | M | ready-for-agent |
| [07](./issues/07-tombstone-avatar-when-deleting-baby.md) | P1-07 | 删除宝宝同时 tombstone 头像 | 02 | M | ready-for-agent |
| [08](./issues/08-carry-plan-photos-through-timer-fulfillment.md) | P1-08 | 计时履行继承护理计划照片 | — | M | ready-for-agent |
| [09](./issues/09-transfer-composer-draft-to-timer-safely.md) | P1-09 | Composer→Timer 安全转移草稿 | 08 | M | ready-for-agent |
| [10](./issues/10-handle-all-timer-transition-failures.md) | P1-10 | Timer transition 覆盖全部非取消异常 | — | S–M | ready-for-agent |
| [11](./issues/11-persist-timer-completion-ui-state.md) | P1-11 | 计时完成态跨配置重建 | 10 | M | ready-for-agent |
| [12](./issues/12-restore-composer-next-feed-offer.md) | P1-12 | Composer 保存后恢复下次喂养 offer | — | M | ready-for-agent |
| [13](./issues/13-exclude-future-facts-from-aggregation.md) | P1-13 | 聚合排除尚未发生的点事实 | — | S–M | ready-for-agent |
| [14](./issues/14-drop-departed-memberships-during-migration.md) | P1-14 | 离线迁移丢弃 departed membership | — | M | ready-for-agent |
| [15](./issues/15-delete-media-outside-room-transaction.md) | P2-01 | 媒体文件删除移出 Room 写事务 | 02、07 | M | ready-for-agent |
| [16](./issues/16-remove-dead-family-id-sync-entrypoints.md) | P2-02 | 删除忽略 familyId 的 pull/push 门面 | 01 | S–M | ready-for-agent |
| [17](./issues/17-align-module-dependency-documentation.md) | P2-03 | 对齐模块依赖文档与 Gradle 真相 | — | S | ready-for-agent |
| [18](./issues/18-split-sync-capability-ports.md) | P2-04 | 按能力拆分 SyncPort/RealSyncPort | 01、02、03、16 | L | ready-for-agent |
| [19](./issues/19-guard-next-feed-marker-cross-language.md) | P2-05 | 用跨语言 fixture 锁定 next-feed marker | — | S–M | ready-for-agent |
| [20](./issues/20-retain-layout-undo-across-recreation.md) | P2-06 | 布局 undo 跨配置重建 | — | S–M | ready-for-agent |
| [21](./issues/21-document-offline-migrate-boundary.md) | P2-07 | 写清 offline-migrate 架构边界 | 14 | S | ready-for-agent |

## Execution discipline

- 一次只领取当前 frontier 中的一票；完成后按 blocker 重新计算 frontier。
- 01/16/18、02/03/07/15、04/05、08/09、10/11、14/21 为高冲突链，不并行改同一 seam。
- 每票只提交 owned 文件；当前未跟踪 `docs/design/2026-08-01-community-forum-design.md` 不属于本 tracker。
- P2-04 使用 expand–migrate–contract，禁止以“大文件”为由一次性重写并混入行为变化。
- ticket 完成状态必须引用 current-HEAD 验证；历史审计结论和旧 tracker 不能替代验收。
