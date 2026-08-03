# 02 — Engine: Owner pull→plan→push, drop durable outbox dependency

**What to build:** Change Android sync engine so publish plans are rebuilt each
cycle after reconcile, and nothing correct depends on cross-session `outbox`
rows.

**Blocked by:** 01 (contract text can land in same PR if small).

**Status:** open

## Scope

- `ReplicaSyncEngine.synchronize`: **Owner** path becomes pull/reconcile first,
  then derive plan, then push (align with member pull-first spirit).
- Replace or gut `OutboxPushPipeline` consumption of durable `OutboxDao` as
  authority: plan from Room entities that still need publish after reconcile.
- Atomic bundle stage/put/commit remains; only **how candidates are chosen**
  changes.
- On push success: CAS receipts / clear needs-publish markers.
- On push failure: keep Room; discard ephemeral plan only.
- Shallow sync “待同步 N” reads needs-publish count, not outbox table count.
- Stop enqueueing durable outbox on domain capture paths once plan is Room-based
  (or make enqueue a no-op / same-cycle only until table removed in 03).

## Tests (minimum)

- [ ] Owner: order is pull then push (recording backend op order).
- [ ] After reconcile adopts remote newer revision, that identity is **not**
      planned for push; local-ahead identity **is** planned.
- [ ] Simulated “no durable outbox” still publishes dirty/local-ahead Room rows.
- [ ] Failure mid-push does not delete local Room content; next cycle can replan.

## Out of scope

- Room schema drop of `outbox` (ticket 03 if migration-coupled).
- Server changes.
- Fixing every 422/media-missing failure mode (observability optional follow-up).

## Comments

- Baseline: current tree **without** the reverted outbox-retirement experiment.
