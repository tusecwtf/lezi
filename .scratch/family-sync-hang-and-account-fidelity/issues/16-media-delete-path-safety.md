# 16 — Engine media delete and avatar path safety

**What to build:** Sync engine discarded/staged path deletes use the same
path-gate + active-ref claim as reference-aware GC (no TOCTOU delete of bytes
domain just attached). Baby avatar replace/remove goes through MediaAsset
tombstone + reclaim only—no dual feature-level delete of live paths that leave
active rows pointing at missing files.

**Blocked by:** None — can start immediately.

**Status:** complete — accepted on `6b278242`

- [x] `cleanupDiscardedLocalMedia` / unowned staged cleanup revalidate under path
      gate before FS delete.
- [x] Avatar save/replace/delete feature path does not bypass ref-aware reclaim
      for MediaAsset-owned paths.
- [x] deleteBaby avatar tombstone remains single authority for baby removal.
- [x] Tests: concurrent attach vs discarded cleanup; avatar replace leaves no
      active MediaAsset with missing file without a follow-up capture/tombstone.
