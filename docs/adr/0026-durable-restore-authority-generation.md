---
status: accepted
---

# Durable restore authority with schema13 compatibility

The current approved candidate retains exact released server schema13 SQL objects and Room29. App0.5.5/code35 remains the next source release with local contract7. The approved zero-minute ordinary nursing-plan intent is a new value-domain revision, so software upgrades remain paired: wire0.5.0, hard floor35 independent of update metadata, and exact handshake `["causal_sync_v2","nursing_plan_intent_v1"]`. Authoritative data routes require the nursing capability even if a caller claims a newer version. New Android health/setup also requires it, rejecting old servers. The separate `restore_authority_v1` capability and `restore_authority:"v1"` request/verified-response discriminator protect upgraded restoration only.

RawEntity MIME remains valid as explicit null or an exact string of zero through255 Unicode scalar values. The unchanged schema13 canonical representation accepts only a nonempty string of at most128 UTF8 bytes. The user explicitly accepted this lossless compatibility boundary: restoration of otherwise valid raw values outside that canonical domain stops before upload/activation and preserves all input. Never normalize, trim, default, omit the photo, label the raw data corrupt, or claim this implements the broader exceptional-data requirement. The Android snapshot preflight and server manifest/store defenses independently enforce the boundary.

The experimental schema14/wire0.5.0 all-MIME design and its proof remain preserved at comparison checkpoint `5c7c4bc`; it was not deployed. This decision supersedes that candidate, not historical release identities or golden corpora. There is no converter, startup backfill, automatic head repair, new table/column, or optional association index in the schema13 candidate.

An authenticated committed restore batch establishes five canonical initial accepted versions atomically with activation. `accepted` includes acceptance of this privileged restore operation, with new Owner/device provenance and a synthetic operation ID; no normal causal request is fabricated. Initial parents are empty. Fulfillment evidence remains immutable evidence, not a sixth root.

A released app34 pending restore checkpoint has no immutable local snapshot and cannot be automatically upgraded into new authority. Resume stops while preserving the checkpoint, token and original data. The user may explicitly cancel and restart an uncommitted batch; an already activated legacy batch requires separately controlled remediation. No automatic cancel or head creation is implied.

The restoring phone persists immutable manifest and pinned bytes before upload. Lost responses replay that batch; current rows are never substituted for its captured dataset. Before commit it records uncertain intent. New credentials remain nonpushable until an atomic Room switch binds current roots to the exact restored baselines, preserves newer facts/photos/tombstones and replaces old mutation/conflict state. A Room completion marker makes cross-store recovery idempotent. Old byte references remain until safely replaced; cleanup must not erase the only surviving current bytes.

## Deterministic identifiers

UUIDv5 baseline namespace `300c6a8b-5aab-51b6-bef0-53a13885188b`; synthetic operation namespace `b823b4c7-7ccf-5ded-b7f7-d190cbb05d69`. Name bytes concatenate each tuple member's decimal UTF8 byte length, colon, then exact UTF8 bytes: `(batch UUID, entity type, entity UUID)`. UUID inputs must already be canonical lowercase hyphenated strings. Root types are `baby`, `record`, `care_plan`, `custom_item`, `wake_observation`. No delimiters are added beyond the length framing. IDs identify content ancestry only; authentication/authorization still requires the committed batch receipt and exact durable snapshot.

Batch `11111111-1111-4111-8111-111111111111`, type `record`, entity `22222222-2222-4222-8222-222222222222` gives baseline `e0bec011-0509-5e15-98e1-0f7e7a49ee87` and operation `048708c5-d2e5-5e67-bab6-de063addc130`.

## Release boundary

This code approval is not deployment authorization. Existing compatible13 databases open unchanged; fresh servers use exactly the same SQL shape. Historical11/12→13 copy-out remains historical13. Already headless restored roots and unprovable legacy causal histories fail closed with precise diagnostics; no startup repair or arbitrary parent selection is permitted. A legacy committed restore receipt cannot be relabeled as new authority without verified matching baselines.

Compatibility means existing valid13 data upgrades without a database conversion. It does not promise old-binary rollback after new-domain writes: released0.5.4 rejects unmarked zero-duration nursing plans. Keep old input backups and verify that old runtime rejects that new domain without rewriting it. Old→new→old success tests apply only to their shared historical domain; there is no automatic downgrade, fallback normalization, or per-family mode.

A real signed APK35 and matching verified update metadata/package remain necessary before release. The sync floor35 protects the approved new plan value domain; keeping SQL13 does not make old software understand that domain. The real34 channel artifact and signed identity remain unchanged. Permanent Android upgrade support starts at contract1/Room24 and remains continuous through contract7/Room29.

## Unavailable restore capability and local artifact retirement

A restore-scoped401 (including the server's anti-enumeration missing-batch response),404 or410 means that capability cannot resume. It does not prove a dispatched commit failed. Preference `commit_uncertain`/`committed`, uncertain Room phases, and acknowledged target authority retain their exact recovery evidence.

For a never-dispatched prepared snapshot, the existing Room journal records local `retiring` phase atomically with the existing `RestoreArtifactRetirement` group ownership. Only afterward are preference replay IDs/token cleared. Startup finishes that local terminal decision before attempting authority-switch recovery; terminal snapshots cannot be loaded for upload/commit. An interrupted preference clear cannot revive the batch or change which family may resume it. No server wire/error or database schema change is introduced.

The existing replacement-aware artifact owner remains the sole garbage-collection authority. A live sole-copy group stays explicitly referenced; equal length with a different digest is not replacement proof. Verified canonical/replacement bytes or absence of a live fact permits reference retirement. A later filesystem unlink failure leaves an unreferenced spool for ordinary recovery/sweep, never missing bytes behind a retained live reference. Repeated expired batches with replaceable bytes retire their full snapshots and group references; this does not authorize erasing unknown historical/uncertain journals or changing the shared spool capacity.
