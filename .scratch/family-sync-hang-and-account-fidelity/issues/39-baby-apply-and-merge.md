# 39 — Baby apply dirty-hold and merge avatar policy

**What to build:** Creator local dirty baby profile is not clobbered by mid-pull remote apply that clears dirty. Owner merge does not rebind published avatar media into immutable-association 409; source avatars tombstone like delete.

**Blocked by:** Soft with 30 (apply wave).

**Status:** complete — accepted on `6b278242`

- [x] Dirty baby nickname survives remote-newer apply or is conflict-surfaced with dirty retained.
- [x] Merge rebinds records/plans without rebinding published avatar association.
- [x] JVM tests for baby dirty mid-apply and merge avatar tombstone.
