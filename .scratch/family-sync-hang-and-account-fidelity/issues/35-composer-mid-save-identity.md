# 35 — Composer mid-save process-death identity

**What to build:** After domain commit of a composer save, process death cannot re-offer the same draft as unsaved and produce a second fact. Save path has durable client identity / consume-on-commit comparable in spirit to timer completionClientUuid.

**Blocked by:** Soft coordinate with 13 (same save path).

**Status:** complete — accepted on `6b278242`

- [x] Kill after domain success before post-save chrome → no duplicate record on re-save.
- [x] Restored state is completed post-save stage or clean closed sheet, not dirty draft of the committed fact.
- [x] Unit/process-death style tests.
