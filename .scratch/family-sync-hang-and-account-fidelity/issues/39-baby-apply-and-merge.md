# 39 — Baby apply dirty-hold and merge avatar policy

**What to build:** Creator local dirty baby profile is not clobbered by mid-pull remote apply that clears dirty. Owner merge does not rebind published avatar media into immutable-association 409; source avatars tombstone like delete.

**Blocked by:** Soft with 30 (apply wave).

**Status:** ready-for-agent

- [ ] Dirty baby nickname survives remote-newer apply or is conflict-surfaced with dirty retained.
- [ ] Merge rebinds records/plans without rebinding published avatar association.
- [ ] JVM tests for baby dirty mid-apply and merge avatar tombstone.
