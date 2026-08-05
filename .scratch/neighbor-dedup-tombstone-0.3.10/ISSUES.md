# 0.3.10 近邻落选 + 墓碑永胜 + 同步 chrome — issues

Spec: [`spec.md`](./spec.md)  
Tracker status: **ready-for-agent** (tickets published)

## Graph

```text
01 ──► 02 ──► 03 ──┐
                   ├──► 04
05 ────────────────┤
06 ────────────────┘
```

| # | File | Status | Blocked by |
|---|------|--------|------------|
| 01 | [`issues/01-record-tombstone-wins.md`](./issues/01-record-tombstone-wins.md) | ready-for-agent | — |
| 02 | [`issues/02-neighbor-adjudication-on-commit.md`](./issues/02-neighbor-adjudication-on-commit.md) | ready-for-agent | 01 |
| 03 | [`issues/03-client-settle-and-neighbor-toast.md`](./issues/03-client-settle-and-neighbor-toast.md) | ready-for-agent | 01, 02 |
| 05 | [`issues/05-data-pages-unified-transient-sync-chrome.md`](./issues/05-data-pages-unified-transient-sync-chrome.md) | ready-for-agent | — |
| 06 | [`issues/06-member-last-sync-visible-to-all-roles.md`](./issues/06-member-last-sync-visible-to-all-roles.md) | ready-for-agent | — |
| 04 | [`issues/04-release-0.3.10.md`](./issues/04-release-0.3.10.md) | ready-for-agent | 01, 02, 03, 05, 06 |

## Frontier

权威链从 **01** 起：01 → 02 → 03。  
Chrome 链 **05**、成员上次同步 **06** 可与权威链 **并行**。  
全部完成后做 **04** 发版收口。

Optional one-time historical neighbor backfill remains out of this ticket set (spec optional); add a later ticket if product requires upgrade-day full collapse.

Former standalone tracker `sync-chrome-and-member-last-sync` is **merged** into this directory (05/06 + spec §3–4).
