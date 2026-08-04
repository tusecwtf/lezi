# APK 升级门闩 · 同步兼容

Status: done — tickets closed 2026-08-04

| # | Issue | Status | Blocked by |
|---|-------|--------|------------|
| 01 | [强制升级总能落到可安装包](./issues/01-forced-update-installable-package.md) | done | — |
| 02 | [强制壳下可恢复会话并完成安装](./issues/02-force-shell-session-recovery-install.md) | done | 01 |
| 03 | [仅在更新通道可用时抬高版本门槛](./issues/03-min-supported-only-when-channel-verified.md) | done | — |
| 04 | [setup 探测容忍服务端加字段](./issues/04-setup-status-additive-fields.md) | done | — |
| 05 | [灾难恢复也走客户端版本门槛](./issues/05-disaster-restore-client-version-gate.md) | done | — |
| 06 | [破坏性 wire 变更必须先抬 min_supported](./issues/06-wire-break-raises-min-supported.md) | done | — |

## Frontier

All tickets done.

```text
01 可安装包 ──► 02 壳下恢复+安装
03 门槛+通道诚实
04 setup 加字段
05 restore 版本闸
06 wire/min 纪律
```

## Source

- Spec: [./spec.md](./spec.md)
- Local audit (gitignore): `docs/reviews/2026-08-04-apk-upgrade-gate-and-sync-compat-audit.md`
- Prior audit tickets (01–08 layer slices) replaced by this to-tickets set

## Notes

- Do **not** loosen: same-signer/sha install identity, failed pull not demoting force shell, LocalDataGate outer gate, sync fail-open when app-update metadata missing, HTTPS/TOFU hard-block.
- Optional “local-only logging under force” is **out of scope** unless product reopens it.
