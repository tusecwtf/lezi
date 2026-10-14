# Authentication revocation consistency

This change preserves wire 0.4.0, SQLite schema 13, Room 29, version floor 21,
and local-data contract 6. It does not change privacy redaction, restore,
source-relation semantics, device visibility, or the existing owned HTTP locks.

## Cache boundary

Every cloned Store shares a generation guarded by the auth-cache read/write lock.
Terminal invalidation increments the generation while clearing the cache. Delayed
cache fills and activity writes retry if that generation changed. Database and
cached activity timestamps never regress. A fresh cache hit already in flight may
finish its read-only snapshot; it cannot refill the cache, and authenticated writes
must separately validate their actor inside their immediate transaction.

Tests pause after the exact cache read, complete revocation, issue fresh requests,
then resume the old request. The matrix covers miss, fresh hit, and activity-write
paths against explicit device removal, owner takeover, member deletion, and refresh
replay. The matrix also checks unrelated devices, persistent terminal reasons, and
reopening the store. Additional tests cover clock rollback and unrelated invalidation.

## Authenticated write inventory

The shared transaction-time check validates family existence, active membership,
current role, and active device. These existing entry points invoke it before
mutation or request-expiry housekeeping:

- Identity: pending login requests; approve, bind, reject login requests; create
  login grants; create, approve, reject, cancel rename requests; add member;
  rename membership/device/family; revoke device; delete membership/family.
- Causal: commit, conflict-detail receipt creation, conflict resolution, and
  conflict branch withdrawal.
- Bundles: stage and commit.
- Media: mark bundle media prepared; reserve causal upload; stage verified
  preimage; bind empty preimage.
- Source relations: author declaration and owner group resolution.

This is not a claim that all reads acquire a transaction-time authorization check.
Authenticated read-only snapshots retain their existing HTTP authentication.
Bootstrap, owner-root login, refresh and login claims use their own credential
checks. Internal publication finalization, consumed-media promotion, expiry/GC,
restore, and conflict-maintenance work are not new user-authorized writes and
retain their existing durability and maintenance rules.

A stale role or revoked device with takeover/replay/ambiguous credential history
produces ordinary unauthorized, preserving client reauthentication behavior. Only
unambiguous explicit device removal produces `device_removed`. Missing family is
checked before missing membership, preserving `family_deleted` versus
`membership_deleted`. HTTP serialization tests pin these cleanup classifications.

## Deterministic regression evidence

The pre-fix `authenticate` implementation from the baseline was executed with
only the same test barrier calls added. Eight of twelve terminal matrix cases
failed (all miss/activity paths); the four fresh-hit cases passed, as expected
for already-started read-only snapshots. The rollback test also failed. Restoring
the generation-aware implementation passes all fourteen cache tests. This is
runtime failure evidence, not an API-compilation failure or a sleep-based race.

Test fixtures now register actual memberships/devices instead of invented actor
IDs. The existing conflict-detail statement budget increases by exactly two
family-scoped authorization queries (5/4 to 7/6 for first/existing receipts),
remaining constant as branch count increases from one to sixteen.
