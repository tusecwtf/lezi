# 30 — Apply: dirty must not skip strictly newer remote

**What to build:** When remote `updatedAt` is strictly greater than local, apply remote body (including tombstones) even if `syncDirty` is true—or fail closed without advancing cursor past a skipped newer remote. Full-resync bookkeeping dirty must not permanently shadow higher remote revisions or leave push stuck on BundleRootNotNewer. Equal-revision media/custom paths must still adopt `deletedAt` when remote is tombstoned.

**Blocked by:** None.

**Status:** complete — accepted on `6b278242`

- [x] Dirty local T1 + remote tombstone T2 → local deleted; cursor honest.
- [x] Full-resync after peer advanced → device converges, not stuck dirty@old.
- [x] Mid-push: live body and outbox epoch are not mixed (abort/rebuild if clocks diverge).
- [x] JVM tests for dirty+newer remote and full-resync dirty policy.
