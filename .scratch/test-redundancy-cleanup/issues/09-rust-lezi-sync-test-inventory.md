# 09 — Kitchen sink E: Rust `lezi-sync` test inventory (and optional split)

**Block:** E  
**Status:** ready-for-agent  
**Blocked by:** none (parallel to Android 05–08; **separate tracker path**)  
**Tree:** `tools/lezi-sync/`  
**Policy:** Q6=A from grill — Android redundancy waves do **not** require this;
this ticket is the dedicated Rust follow-on.

## Goal

1. **Inventory** server tests by protocol/store contract (not line count).  
2. Classify: KEEP / DEFER-split / exact-duplicate.  
3. Only then optionally split large integration surfaces (e.g. kitchen-sink style
   `tests/api.rs` if still monolithic) **without** gutting cutover/reconciliation
   coverage.

## Primary surfaces to map

| Area | Paths (verify at start) |
|------|-------------------------|
| HTTP / API integration | `tools/lezi-sync/tests/` (`api.rs`, `tls.rs`, …) |
| Store unit/integration | `tools/lezi-sync/src/store/tests/*` |
| Offline migrate support | `src/offline_migrate/test_support.rs` etc. |

## Constraints

- Q2=A style: kitchen sinks **classify before delete**; no silent coverage loss.
- Gates remain: `cargo fmt --all -- --check`, `cargo test --locked`,
  `cargo clippy --all-targets --all-features -- -D warnings` under `tools/lezi-sync/`.
- **No default NAS CD** for pure test layout moves. If a change touches wire that
  needs live proof, follow AGENTS.md propose-then-confirm CD rules.
- Certificate / TOFU destructive tests: never against family NAS (AGENTS.md).

## Acceptance

- [ ] Markdown map under this tracker, e.g.
  `.scratch/test-redundancy-cleanup/maps/lezi-sync-tests.md`
  (module/file → contract clusters → disposition)
- [ ] List of split candidates with target module names (if any)
- [ ] If splits land: cargo gates green; no production behavior change unless
  separately ticketed
- [ ] Explicit “no further split” sign-off if inventory shows store tests already
  partitioned enough

## Out of scope

- Android kitchen sinks (05–08)
- Greenfield rewrite test pyramid as merge gate for old `lezi-sync` (see greenfield tracker)

## Comments

Opened from test-redundancy-cleanup residual plan (blocks A–E).
