# 19 — Server: do not hold family lock across media upload stream

**What to build:** lezi-sync stops holding the per-family mutex for the entire
HTTP body stream of `put_bundle_media` (and similarly long media reads if
applicable). Other pull/push/commit for that family are not blocked for the
whole upload duration. Request idle/body timeouts exist so a stalled client
cannot brick the family lock forever.

**Blocked by:** None — can start immediately; **requires lezi-sync release**
(not Android-only).

**Status:** implemented — awaiting live acceptance on `6b278242`

- [x] Family lock acquired after body validation or narrowed to per-bundle /
      short critical sections, not whole stream.
- [x] Stalled upload cannot block pull/commit indefinitely.
- [x] Rust tests or integration proof concurrent pull during large put (or
      documented lock scope with timeout).
- [x] Deploy notes: server version bump; Android remains compatible.

**Residual:** static implementation, Rust concurrency coverage, and deploy notes are
accepted; production acceptance remains:

- [ ] NAS CD followed by a concurrent stalled/large media upload and pull/commit smoke.
