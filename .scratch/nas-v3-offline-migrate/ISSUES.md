# Issues · NAS v3 离线一次升级（拷出 → 本机转换 → 拷回）

**Status:** ready-for-agent

**Spec:** [`spec.md`](./spec.md)

本 tracker 将「从 NAS 拷出 data → 本机一次性升级到当前 schema → 校验 → 拷回 NAS → TLS cutover → APK 重登」拆为 7 个可依赖执行的本地 ticket。状态以各 ticket 文件为准。

## Dependency graph

```text
01 合同与 v3↔v11 对照 ✓
└── 02 本机库迁移器（无媒体文件）
    ├── 03 媒体文件与 publications
    └── 04 根密码重置 + 当前服务可开
         └── 05 拷出 / dry-run / 校验 CLI
              └── 06 拷回 + TLS cutover runbook
                   └── 07 维护窗实切 + APK 联调验收
```

## Tickets

| # | Ticket | Blocked by | Status |
|---|--------|------------|--------|
| [01](./issues/01-migration-contract-and-inventory.md) | 锁定迁移合同与 v3↔v11 清单 | — | complete |
| [02](./issues/02-offline-db-migrator-v3-to-current.md) | 本机离线库迁移器 v3→当前 | 01 | complete |
| [03](./issues/03-media-files-and-publications.md) | 媒体文件与 publications 映射 | 02 | complete |
| [04](./issues/04-root-password-reset-and-server-open.md) | 根密码重置与当前服务可启动 | 02 | ready-for-agent |
| [05](./issues/05-copy-out-dry-run-validate-cli.md) | 拷出、dry-run、校验 CLI | 02, 03, 04 | ready-for-agent |
| [06](./issues/06-copy-back-tls-cutover-runbook.md) | 拷回 NAS 与 TLS cutover runbook | 05 | ready-for-agent |
| [07](./issues/07-live-cutover-and-apk-smoke.md) | 维护窗实切与本地 APK 联调 | 06 | ready-for-agent |

## Frontier

- 可立即开始：**04**（与 03 并行位；03 已完成）
- 03+04 完成后：**05** → **06** → **07**
