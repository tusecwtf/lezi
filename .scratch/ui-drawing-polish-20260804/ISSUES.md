# Issues — ui-drawing-polish-20260804

Status: ready-for-agent

Serial spine (preferred agent order). **01–07** and **10** are done; next open
frontier is **08** (growth lazy history) or **11** (weak surfaces) — keep work
one-at-a-time unless a human explicitly parallelizes.

| # | File | Title | Blocked by | Status |
|---|------|-------|------------|--------|
| 01 | [issues/01-expand-motion-density-tokens.md](./issues/01-expand-motion-density-tokens.md) | Expand motion + density token tables | — | done |
| 02 | [issues/02-bounded-photo-lru.md](./issues/02-bounded-photo-lru.md) | Bounded local photo LRU | 01 | done |
| 03 | [issues/03-chrome-dial-nursing.md](./issues/03-chrome-dial-nursing.md) | High-frequency chrome: dial + nursing | 01 | done |
| 04 | [issues/04-chrome-nextfeed-photo-qr.md](./issues/04-chrome-nextfeed-photo-qr.md) | Remaining designsystem chrome | 03 | done |
| 05 | [issues/05-contract-ban-bare-material.md](./issues/05-contract-ban-bare-material.md) | Contract: ban bare Material outside wrappers | 04 | done |
| 06 | [issues/06-record-density-empty.md](./issues/06-record-density-empty.md) | Record surface density + empty language | 01, 05 | done |
| 07 | [issues/07-summary-density-empty.md](./issues/07-summary-density-empty.md) | Summary density + empty/calculating honesty | 06 | done |
| 08 | [issues/08-growth-lazy-history.md](./issues/08-growth-lazy-history.md) | Growth: lazy history + surface polish | 07 | ready-for-agent |
| 09 | [issues/09-family-lazy-members.md](./issues/09-family-lazy-members.md) | Family: lazy members/devices | 08 | ready-for-agent |
| 10 | [issues/10-shell-motion-reduce-motion.md](./issues/10-shell-motion-reduce-motion.md) | Shell motion + reduce-motion | 01, 05 | done |
| 11 | [issues/11-weak-surfaces-menu-widget-export.md](./issues/11-weak-surfaces-menu-widget-export.md) | Weak surfaces: menu / widget / export | 05, 10 | done |
| 12 | [issues/12-presentation-format-if-needed.md](./issues/12-presentation-format-if-needed.md) | Presentation format convergence (conditional) | 06, 09 | ready-for-agent |
| 13 | [issues/13-screenshot-matrix-closeout.md](./issues/13-screenshot-matrix-closeout.md) | Screenshot matrix + program close-out | 02, 07, 09, 11, 12 | ready-for-agent |

Parent: [spec.md](./spec.md)

## Dependency graph

```text
01 ─┬─► 02 ─────────────────────────────┐
    ├─► 03 ► 04 ► 05 ─┬─► 06 ► 07 ► 08 ► 09 ─┬─► 12? ─► 13
    │                  ├─► 10 ───────► 11 ────┘
    │                  └───────────────┘
```

## Frontier policy

- Default: work **top to bottom** when a ticket’s blockers are complete.
- After **01**: prefer **03** next (chrome path), then **04→05**, then either
  **06** or **10**; run **02** only when deliberately switching context to
  photos (still blocked by 01).
- **12** closes as `wontfix` if no format drift remains after 06/09.
