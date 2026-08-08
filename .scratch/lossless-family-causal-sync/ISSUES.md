# 家庭事实无损因果同步 — issues

Status: ready-for-agent — frontier after 05 complete = 06/07/08

Spec: [`spec.md`](./spec.md)

Baseline: `5bc07bbb29806efe895babdfc37305cd48155f6c`

## Graph

```text
01 contract / terminology / ADR
  ├─► 02 server schema v12 + offline migration
  │      └─► 03 causal API + Store commit/pull/resolution
  └─► 04 Android Room 27 + causal/wake/source state
             │
03 + 04 ─────┴─► 05 ReplicaSyncEngine merge/branch/projection
03 + 04 + 05 ──► 06 conflict + WakeObservation + overlap UX
03 + 04 + 05 ──► 07 non-destructive duplicate groups + bounds
03 + 05 ───────► 08 LocalWrite no-pull fast path
02–08 ─────────► 09 two-client E2E + forced cutover + release
```

| # | File | Status | Blocked by |
|---|------|--------|------------|
| 01 | [`issues/01-freeze-causal-domain-wire-adr.md`](./issues/01-freeze-causal-domain-wire-adr.md) | complete | — |
| 02 | [`issues/02-server-schema-v12-version-conflict-migration.md`](./issues/02-server-schema-v12-version-conflict-migration.md) | complete | 01 |
| 03 | [`issues/03-causal-reconcile-commit-pull-resolution.md`](./issues/03-causal-reconcile-commit-pull-resolution.md) | complete | 02 |
| 04 | [`issues/04-android-room27-causal-wake-source-state.md`](./issues/04-android-room27-causal-wake-source-state.md) | complete | 01 |
| 05 | [`issues/05-replica-engine-three-way-merge-and-branches.md`](./issues/05-replica-engine-three-way-merge-and-branches.md) | complete | 03, 04 |
| 06 | [`issues/06-conflict-wake-observation-overlap-ux.md`](./issues/06-conflict-wake-observation-overlap-ux.md) | ready-for-agent | 03, 04, 05 |
| 07 | [`issues/07-nondestructive-duplicate-groups-and-bounds.md`](./issues/07-nondestructive-duplicate-groups-and-bounds.md) | ready-for-agent | 03, 04, 05 |
| 08 | [`issues/08-localwrite-causal-no-pull-fast-path.md`](./issues/08-localwrite-causal-no-pull-fast-path.md) | ready-for-agent | 03, 05 |
| 09 | [`issues/09-two-client-cutover-release-and-acceptance.md`](./issues/09-two-client-cutover-release-and-acceptance.md) | ready-for-agent | 02, 03, 04, 05, 06, 07, 08 |

## Frontier

`05` (ReplicaSyncEngine merge/branch) is complete; next is `06` / `07` / `08` (conflict UX,
duplicate groups, LocalWrite no-pull). No runtime behavior ticket may invent a competing shape
outside the frozen contract in ticket 01 /
[`docs/prd/causal-sync-wire.md`](../../docs/prd/causal-sync-wire.md) / ADR-0019–0021.

## Primary acceptance seam

One joined client writes through `CareLog`, synchronizes through the existing public sync façade against a
developer-owned isolated real lezi-sync, and a second joined client pulls the same stable projection plus
conflict/duplicate summaries. Recording backend tests exercise the same sync façade for deterministic
ordering; Store, Room migration and aggregation tests are supporting seams, not substitutes for this chain.

## Program invariants

- No product implementation or deployment is accepted from Markdown publication alone.
- The nine tickets replace the deleted four-ticket sleep-only tracker; there is one executable owner.
- Every ticket re-pins current HEAD and preserves unrelated dirty work.
- Any server or Android wire implementation requires the listed local gates and isolated real-server smoke.
- Ticket 09 must ask for a fresh NAS maintenance-window confirmation after all local evidence exists.

