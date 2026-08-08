# Issues — 家庭同步权威裁决与 dirty 收敛

Status: implementation-complete — live acceptance pending

**0.3.13 规划 supersession：** head-by-UUID + LWW verdict 词汇由
`lossless-family-causal-sync` / ADR-0019/0020 的因果 reconcile/commit 取代；先对账/
终态收敛骨架保留。本 tracker 描述 **已交付** 裁决，不是无损因果目标合同。

| # | File | Title | Blocked by | Status |
|---|------|-------|------------|--------|
| 01 | [issues/01-server-authoritative-reconcile.md](./issues/01-server-authoritative-reconcile.md) | 服务端批量 head-by-UUID 权威裁决 | — | complete |
| 02 | [issues/02-android-settlement-cycle.md](./issues/02-android-settlement-cycle.md) | Android 冻结快照与裁决收敛周期 | 01 | complete |
| 03 | [issues/03-local-disposition-and-pending.md](./issues/03-local-disposition-and-pending.md) | 本机保留、技术清理与诚实待同步投影 | 02 | complete |
| 04 | [issues/04-upgrade-e2e-release.md](./issues/04-upgrade-e2e-release.md) | 0.3.6 升级验收、双端联调与发布门 | 01, 02, 03 | implementation-complete — live acceptance pending |

Parent: [spec.md](./spec.md)

## Dependency graph

```text
01 server verdict
  └─► 02 Android settle loop
        └─► 03 disposition + pending projection
              └─► 04 upgrade/E2E/release
```

## Primary acceptance seam

一台已加入的 Release 客户端通过公开同步 façade 对隔离的真实 lezi-sync 完成一个权威
收敛周期：冻结的混合 dirty 原子单元全部取得终态，静止时 pending=0；第二客户端 pull 后
看到同一家庭权威。模块测试只支撑该 seam，不替代它。

## Acceptance status (2026-08-05)

- Implementation and local validation are complete at version 0.3.7.
- API 35 instrumentation, including the 0.3.6/local-contract-3 migration fixture, passed.
- Signed Release APK metadata, signer, hash, installation, and cold launch passed locally.
- The isolated two-client real-server seam and family-NAS CD smoke remain open. They require
  the explicit deployment window described in AGENTS.md and do not block the code fixed point.
