# 03 — Upgrade: Room/media preserve + outbox intent handoff + push

**What to build:** Local-data / Room migration so APKs upgrading from the
pre-redesign build **lose no care data or media**, and **can publish** under the
new plan after first online cycles.

**Blocked by:** 02 (or land with 02 if single PR keeps gates green).

**Status:** complete

## Why

- Durable `outbox` is being removed as a source of truth.
- Devices may already have: pending Room writes, residual outbox rows, and even
  “clean Room + leftover outbox epoch” shapes.
- User acceptance: **本机记录不丢** and **家庭侧仍能推上 NAS** after upgrade.

## Must

- [x] Preserve all Room care entities and on-disk media files across upgrade.
- [x] **Handoff:** for every pre-migration outbox identity
      `(familyId, entityType, clientUuid)`, ensure Room entity is
      **needs-publish eligible** under the new planner (e.g. set dirty / clear
      misleading receipt if required). Do **not** only `DELETE FROM outbox`
      while trusting existing `syncDirty` alone.
- [x] Drop or stop using `outbox` table after handoff (schema/contract step as
      required by local-data ledger).
- [x] Fixture or device test: DB with residual outbox + Room rows → migrate →
      reconcile+push → fake or real backend has local-ahead entities.
- [x] Document operator note: local visibility after upgrade ≠ family visibility
      until first successful push.

## Acceptance (product)

1. Upgrade old APK → new APK: open app, timeline/care data present (no wipe).
2. On home LAN, after sync: peer device (or second client fixture) receives
   writes that existed only on the upgraded device before push.
3. Pending UI count does not depend on resurrecting old outbox rows.

## Comments

- User clarified: keeping Room is enough to not lose **local** records; family
  push still requires handoff into the new plan. Both are in acceptance.
- 2026-08-04 device evidence on `lezi_api35`: adjacent contract 1→2→3 opens
  Room 26; contract 2 fixture with a clean Record plus residual outbox migrates
  to dirty Room state, drops outbox, and preserves the exact media bytes.
- The deterministic public-seam chain is covered in two halves: the device
  migration proves residual intent becomes Room dirty state; sync JVM tests
  prove a no-outbox dirty/local-ahead Room entity survives pull, enters the
  same-cycle plan, and is pushed to the fake backend. Pending count likewise
  reads Room planner eligibility rather than an outbox row count.
- Verification: targeted migration/FreshDatabase instrumentation suites and
  `:sync`, `:app`, and `:core:database` JVM suites passed.
