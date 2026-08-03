# Client publish plan: reconcile-first, ephemeral outbox

Status: complete — contract, engine, and upgrade migration accepted

## Goal

Re-design how the Android client decides **what to publish** to the family NAS so
that:

1. **Remote authority is applied before a publish plan is built** (Owner included).
2. **No durable outbox table** is a second source of truth across sessions.
3. **Offline writes stay safe** as Room entities; after reconnect, plan = diff of
   local Room vs reconciled remote state.
4. **APK upgrade from pre-redesign builds** keeps all Room/media data and can still
   push anything that never reached NAS under the new mechanism.

Observed product pain (context, not the sole ticket framing): after upgrade and/or
offline periods, a device can show “暂时无法同步 · 待同步 N 项” while LAN health
is fine; peer devices never see latest local writes. Root causes may include
durable outbox drift, push-before-pull on Owner, non-retryable push failures, etc.
This track fixes the **mechanism**, not a single incident bugfix.

## Locked decisions (grill)

| Topic | Decision |
|-------|----------|
| Problem class | **Mechanism redesign** (not “prove poison outbox first”) |
| Long-lived intent | **Room entities** (content + local revision/receipt fields as needed) |
| Remote compare (v1) | **Incremental pull + local entity diff** — no full-family snapshot |
| Owner sync order | **Always** `pull/reconcile → plan → push` (same spirit as member pull-first) |
| Durable `outbox` table | **Remove / stop depending on it** |
| NAS protocol | **Unchanged** — keep pull + atomic bundle stage/put/commit |
| Acceptance depth | Behavior contract + single/dual-device automated tests |
| Upgrade | **No Room/media loss**; old pending publish intent still publishable after migrate |

## Must

### Publish lifecycle

- Each joined sync cycle for **Owner and Member**:
  1. Reconcile remote into Room (existing pull / full-resync recovery).
  2. Derive an **ephemeral publish plan** from Room (what is still local-ahead or
     never published).
  3. Push plan via existing atomic bundles.
  4. On success, update local receipts / clear “needs publish” markers only with CAS.
  5. On failure, **Room content and needs-publish markers remain**; no stale
     long-lived outbox epoch may block later plans.
- Ephemeral plan may live in memory or a same-cycle scratch structure only.
  Process death discards plan; next cycle rebuilds from Room + pull.

### Offline

- Offline writes continue to mutate Room only (existing domain paths).
- No requirement to maintain a correct durable outbox while offline.
- “待同步 N” product count must reflect **entities that still need publish after
  last successful reconcile**, not residual outbox rows.

### Upgrade / migration

- Room schema / local-data contract migration must **preserve all care entities and
  media files**.
- Pre-redesign **`outbox` rows must not be dropped without transferring intent**:
  for every outbox identity, ensure the corresponding Room entity remains
  eligible for the new plan (e.g. mark needs-publish / dirty, or equivalent),
  then drop the table / stop writing it.
- After upgrade, first successful online cycles: pull → plan → push must be able
  to deliver local-ahead data to NAS so a second device can pull it.
- Local data remaining on device is necessary but **not sufficient** acceptance:
  family visibility after push is required for “能在新机制下正常推送”.

### Non-goals (this track)

- NAS wire / `atomic_bundle` API redesign, head-by-uuid endpoints, full snapshot diff.
- Changing LWW / care-plan fulfillment product rules on the server.
- Guaranteeing push success when auth fails, media files are missing, or server
  returns 422/409 for content rules (those stay separate; plan must still be
  honest and not poisoned by durable outbox drift).
- Dual-family merge or disaster restore redesign.

## Public seams (expected)

- Android: `ReplicaSyncEngine` / `EphemeralPublishPipeline`, `OutboxDao` removal,
  local-data
  contract / Room migration, shallow sync status pending count source.
- Server: **none** for this track.

## Validation

- JVM: Owner order pull-then-plan-then-push; plan rebuilt after process-style
  “drop plan”; upgrade migration maps outbox → needs-publish; no publish plan
  depends on cross-session outbox rows; mid-failure leaves Room intact.
- Dual-device or dual-fake-backend: offline writes on A → online → B sees data after
  A’s reconcile+push.
- Upgrade fixture: DB with Room rows + residual outbox (including dirty=false /
  epoch mismatch shapes if representable) → migrate → sync → server has local-ahead
  entities.
- Gates: `./gradlew :sync:test …` and module tests as touched; no NAS CD required
  for pure client mechanism unless product asks.

## Comments

- 2026-08-04: Experimental outbox retirement patch was **reverted** before this
  track; baseline is current `master` sync engine without that patch.
- Grill notes: user chose mechanism redesign (C), remote-diff intent model with
  Room as offline intent (A), incremental pull+diff (A), Owner always
  pull→plan→push (A), delete durable outbox, no NAS protocol change, acceptance
  includes old-APK upgrade data safety + push under new mechanism.
- 2026-08-04: Room 26 / local-data contract 3 retires the table only after
  transferring supported residual identities to Room dirty state. Device
  migration fixtures preserve care rows and media bytes and cover the
  post-transaction/pre-marker retry window; dual-client fake-backend coverage
  proves the resulting Room boundary is publishable and peer-visible without
  durable queue state.
