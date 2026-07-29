# Ticket 05 parameterized photo reconcile validation

- Date: 2026-07-30 (Asia/Shanghai)
- Validation base HEAD: `25af7bb2fbad2a773aa6afa2a5698bec4ae73545`
- Scope: domain attachment mechanics only; no emulator, version, UI, or sync-pipeline changes

## Live audit disposition

HEAD already contained a first-pass private `reconcileLogPhotos(recordId?, carePlanId?)` and
`tombstoneLogPhotos(recordId?, carePlanId?)`, introduced with the redundancy review. The runtime
call surface still retained owner-specific wrappers, nullable owner pairs, a separate CareLog
normalizer, and no public seam that could independently lock parity. CarePlan photo-only edits
were also skipped when schedule/note/payload were unchanged.

The closeout replaces that partial state with one public `PhotoAttachmentReconciler` and sealed
positive-ID `PhotoAttachmentOwner.Record` / `.CarePlan` values. Every CareLog photo mutation calls
that seam directly. `MediaAssetEntity` continues to reject any log row whose record/care-plan
ownership is not XOR.

## TDD receipts

The agreed public seam was confirmed before tests were written. Valid RED receipts were:

1. `PhotoAttachmentReconciler` and `PhotoAttachmentOwner` unresolved during test compilation.
2. The replace/revive worked example failed because a tombstoned path was inserted with a new
   UUID and the removed live row was not tombstoned.
3. The shared-path CarePlan test failed compilation because `tombstone` did not exist.
4. The public CareLog photo-only update test failed because unchanged plan metadata bypassed
   attachment replacement.

GREEN coverage now proves:

- trim, blank removal, stable distinct order, and the ≤3 limit after normalization;
- both Record and CarePlan owners reject non-positive IDs and create only XOR-owned log rows;
- retained rows are no-ops, tombstoned paths revive with their original client UUID, UUID factory
  calls occur only for new rows, and removed/cleared rows receive tombstones;
- Record and CarePlan may hold separate active rows for one physical path; tombstoning one owner
  never changes the other;
- revive/tombstone `updatedAt` is monotonic and every mutation sets `syncDirty=true`;
- photo-only CarePlan replace/clear updates the atomic root bundle;
- a mid-reconcile DAO failure after revive and insert rolls back all media writes and leaves the
  CarePlan root unchanged.

## Gates

```text
./gradlew :domain:testDebugUnitTest :domain:lintDebug :app:assembleDebug --no-daemon
BUILD SUCCESSFUL in 21s
518 actionable tasks: 70 executed, 448 up-to-date
```

`git diff --check` also passed for the owned implementation, test, tracker, and evidence files.

## Scope limit

The reconciler deliberately relies on the enclosing CareLog domain transaction instead of
opening a nested transaction. This ticket does not change physical-file reclamation, photo
preview UI, atomic sync transport, product contracts, APK version, or release evidence.
