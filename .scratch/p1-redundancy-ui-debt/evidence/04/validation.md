# Ticket 04 atomic media publisher validation

- Date: 2026-07-30 (Asia/Shanghai)
- Validation base HEAD: `aec3a3b73d9b372f87f54ec8bf35ebe2ae612cc2`
- Scope: sync atomic media publishing mechanics and custom-item UUID characterization only; no NAS
  protocol, CareLog reconciliation, device, release, or version changes

## Live audit disposition

The live tree had already consolidated custom record definition lookup into
`resolveRecordCustomItemClientUuid` in `CustomItemClientUuid.kt`. Both Outbox and Replica retained
thin named wrappers but delegated to that one implementation. Deleting a wrapper or introducing a
second UUID resolver would not reduce logic duplication, so the closeout keeps the wrappers and
adds characterization coverage for the shared implementation.

Media preparation was also already shared inside Outbox, but the network publication sequence was
still copied in the Record and CarePlan branches: prepare manifest, stage, select missing media,
PUT bytes, write receipts, then commit. Record canonical authors/root acknowledgement and CarePlan
root acknowledgement/cleanup are intentionally different and remain in their callers.

## Approved seam and TDD receipts

The approved deep seam is internal `AtomicMediaBundlePublisher.publish`. Its dependencies are only
the NAS backend, local media-file boundary, media load/update functions, and remote-policy gate.
It receives an already-mapped root and selected media outbox rows, and returns the commit result.
It does not select ownership, map either domain root, acknowledge versions, merge canonical authors,
or delete outbox rows.

The valid RED was:

```text
AtomicMediaBundlePublisherTest > compileDebugUnitTestKotlin FAILED
Unresolved reference 'AtomicMediaBundlePublisher'.
```

Minimal GREEN moved the existing metadata preparation and atomic stage/PUT/receipt/commit sequence
without changing the wire draft. A follow-up failure test proves PUT failure writes no receipt and
never reaches commit; prepared local mime/size remain retryable, matching the previous sequence.

`CustomItemClientUuidTest` is explicitly characterization rather than a fabricated RED. It proves
non-custom records return null, valid custom payload IDs resolve the definition UUID, and malformed
IDs, old schema, missing definitions, and blank definition UUIDs retain their exact failure rules.

## Public behavior and regression matrix

`RealSyncPortTest.recordAndCarePlanPublishApplyTheSamePreparedMediaMetadataContract` exercises the
public `SyncPort.sync` seam with one Record photo and one CarePlan photo in the same run. Both drafts
receive the same prepared `image/jpeg` and `byte_size=1` contract, upload and commit their own bundle,
write deterministic receipts, acknowledge their distinct roots, and drain the outbox.

The complete `:sync` suite also re-runs the existing independent Record/CarePlan zero/one/three-photo,
upload-failure, committed retry, staged missing-media retry, commit-failure, and pull invisibility
coverage. No caller acknowledgement or cleanup was moved into the shared publisher.

## Final gates

```text
./gradlew :sync:testDebugUnitTest :sync:lintDebug :app:assembleDebug --no-daemon

BUILD SUCCESSFUL in 22s
478 actionable tasks: 61 executed, 417 up-to-date
```

The earlier focused matrix for `AtomicMediaBundlePublisherTest`, `CustomItemClientUuidTest`, and the
new dual-root `RealSyncPortTest` also passed. Final owned-file whitespace and cached-stage checks are
performed separately before commit so concurrent layout and preview work cannot enter this ticket.

## Scope limit

This ticket changes no NAS fields/schema, Replica wrapper, CareLog photo reconcile, database schema,
product UI, APK version, or release artifact. No emulator/device gate was run.
