# 跨成员睡眠醒来 ACL — issues

Spec: [`spec.md`](./spec.md)  
Tracker status: **complete** (frontier = none)  
Target: **0.3.11**

**0.3.13 规划 supersession：** 整行 `end_timestamp` 闭合与本机 B1 由
`lossless-family-causal-sync` / ADR-0021 的 SleepStart + WakeObservation 模型取代。
本 tracker 仍是 0.3.11 **已交付** 事实；不得写成 0.3.13 已上线行为。

## Graph

```text
01 ──► 02
```

| # | File | Status | Blocked by |
|---|------|--------|------------|
| 01 | [`issues/01-family-wake-publishable-and-local-b1.md`](./issues/01-family-wake-publishable-and-local-b1.md) | complete | — |
| 02 | [`issues/02-release-0.3.11.md`](./issues/02-release-0.3.11.md) | complete | 01 |

## Frontier

None for agent work. **0.3.11** released (NAS CD + gates). Residual optional: family dual-phone UI matrix for wake→B1→settle.

## Notes

- Merged former “publishable wake” + “local B1 UI” into ticket **01** (user decision).
- No server closer stamp; B1 is device-local and ends when dirty settles or authority overwrites.
- Related complete work: multi-open heal (`family-sync-hang` issue 32) — do not re-scope into 01.
