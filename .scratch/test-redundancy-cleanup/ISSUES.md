# Issues — test redundancy cleanup

## Index

| ID | Title | Status |
|----|-------|--------|
| 00 | Policy grill lock + inventory | **done** |
| 01 | Wave 1 PROCESS-FIX dirty family/composer | **done** |
| 02 | Wave 1 DELETE Structure/scan suites | **done** |
| 03 | Gate REWRITE (cleartext / BareMaterial / bottom-nav) | **done** |
| 04 | Wave 2 layout/calendar device compress | **done** |
| 04b | Wave 3 guidance / owner-takeover / network security | **done** |
| [05](./issues/05-kitchen-sink-realsync-port.md) | **A** Split `RealSyncPortTest` | **done**|
| [06](./issues/06-kitchen-sink-carelog.md) | **B** Split `CareLogTest` | **done**|
| [07](./issues/07-kitchen-sink-medium-suites.md) | **C** Split medium JVM suites | **done** |
| [08](./issues/08-kitchen-sink-boundary-large.md) | **D** Boundary large files | **done**|
| [09](./issues/09-rust-lezi-sync-test-inventory.md) | **E** Rust lezi-sync inventory/split | **done** (inventory; no split) |

Policy: [spec.md](./spec.md) · Inventory: [inventory.md](./inventory.md)

## Open kitchen-sink / residual (A–E)

| Block | Ticket | Primary target |
|-------|--------|----------------|
| **A** | [05](./issues/05-kitchen-sink-realsync-port.md) | `RealSyncPortTest` ~11k LOC |
| **B** | [06](./issues/06-kitchen-sink-carelog.md) | `CareLogTest` ~8.4k LOC |
| **C** | [07](./issues/07-kitchen-sink-medium-suites.md) | Engine/HTTP/session/wizard/prefs/draft |
| **D** | [08](./issues/08-kitchen-sink-boundary-large.md) | Next-tier ~550–870 LOC files |
| **E** | [09](./issues/09-rust-lezi-sync-test-inventory.md) | `tools/lezi-sync` tests — **done** (map; store no-split; api DEFER-split) |

**Suggested order:** 05 → 06 → 07 → 08; **09 parallel**.  
**Split rule:** contract map → move by cluster → optional exact-duplicate merge; do not gut assertions.

## Landed waves (summary)

See git history / commit messages:

- `f355ad32` Wave 1 Structure cleanup  
- `dd3a661d` Wave 2 device compress  
- `471b292c` Wave 3 guidance/takeover/network  
- `160f94db` BareMaterial + bottom-nav gate restore  

Detailed wave notes remain in git; tickets 01–04 are closed above.
