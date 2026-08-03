# 27 — Bound avatar display and prepareUpload memory

**What to build:** Avatar display never full-decodes unbounded remote JPEGs.
Sync `prepareUpload` samples/scales with an OOM-safe path so large but
policy-legal photos cannot kill the process mid-push. Fail soft to typed
prepare failure and retry, not process death.

**Blocked by:** None — can start immediately; complements 15–16.

**Status:** complete — accepted on `6b278242`

- [x] Avatar composable uses bounded decode (max edge / pixel budget) like
      record preview.
- [x] prepareUpload peak decode is capped; OOM caught; bitmaps recycled from
      first allocation.
- [x] Tests or device smoke: large avatar file does not OOM UI; large record
      photo fails prepare closed without killing process.
