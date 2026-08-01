# 21 — 写清 offline-migrate 架构边界

**What to build:** 在 ADR/PRD 明确 `offline-migrate` 是一次已授权维护窗中的离线切割工具，不是 server startup/runtime migration，也不推翻 NAS fresh-current/fail-closed 合同。

**Source:** `AUDIT-20260801-P2-07`  
**Blocked by:** 14 — document the corrected departed-membership transform  
**Status:** blocked
**Size:** S

## Acceptance criteria

- [ ] ADR-0008 或新 ADR 明确允许的唯一例外：显式 CLI、停服、固定源 schema→current、临时目标、验证后切换。
- [ ] 写明服务启动仍只接受精确 current schema；不得探测旧库后自动迁移、destructive fallback 或部分原地改写。
- [ ] 文档覆盖 secret、data bind、备份/rollback、目标校验与 Ticket 14 的 departed membership hard-delete transform。
- [ ] `README`、`DEPLOY.md`、PRD/ADR 对工具目的和边界使用一致术语，并互相链接 authoritative runbook。
- [ ] 发布二进制包含子命令不再被描述成支持一般滚动兼容；普通 CD 不执行该子命令。
- [ ] 文档命令与当前 CLI `--help`/脚本实测一致，且不打印 bootstrap secret。

## Validation

运行文档链接/命令 smoke、`git diff --check`；若命令示例需 live NAS，只做只读 probe，任何 replace 仍须另行确认。

## Documentation Gate

本票完成时记录 ADR disposition，并更新 ADR index。

## Out of scope

不在文档票执行 NAS cutover，不新增第二种迁移工具。
