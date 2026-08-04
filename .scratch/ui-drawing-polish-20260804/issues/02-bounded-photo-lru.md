# 02 — Bounded local photo LRU

**What to build:** Local record/plan photo previews use a conservative in-memory
LRU (thumbnail entry cap ~24 **and** ~16–32 MiB decoded budget; fullscreen at
most 1–2; recycle on eviction). Reopening the same thumb is faster without
unbounded memory; cancel must not poison the cache. Photo-loading PRD states the
bounded policy (replacing “no global cache”).

**Blocked by:** 01 — Expand motion + density token tables.

**Status:** done

- [x] Cache sits only behind the shared local photo load API
- [x] Key identity includes path, target, source size, orientation
- [x] Dual bounds enforced; fullscreen capped; eviction recycles bitmaps
- [x] JVM tests cover identity miss, budget eviction, cancel safety
- [x] `docs/prd/local-photo-loading.md` documents the bounded cache policy
- [x] `./gradlew :designsystem:test` (and related photo tests) green
