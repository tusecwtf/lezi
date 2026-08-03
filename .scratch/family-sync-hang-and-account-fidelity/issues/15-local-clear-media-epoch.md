# 15 — Local clear media sweep and mutation epoch

**What to build:** “本机清空” removes not only row-backed media paths but also
orphan draft/import files under product media dirs (scope-aware), and domain
writers do not silently re-populate mid-clear. Clear remains crash-recoverable;
privacy expectation matches product copy.

**Blocked by:** None — can start immediately.

**Status:** complete — accepted on `6b278242`

- [x] Clear finish (or post-finish pass) sweeps known media roots for unreferenced
      files, or durable draft-path ledger is reclaimed on clear.
- [x] Record/photo writers are excluded or fail-closed for the clear epoch (not
      only calendar reminder guard).
- [x] No path-gate ↔ syncMutex deadlock introduced.
- [x] Tests: draft import file without MediaAsset is gone after clear; concurrent
      attach during clear does not leave inconsistent half-family data.
