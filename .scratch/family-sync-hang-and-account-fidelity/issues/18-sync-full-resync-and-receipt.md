# 18 — Member post-push full-resync catch and receipt CAS honesty

**What to build:** Member creator-ack post-push pull handles generation 409 with
the same full-resync recovery as other pulls (not hard-fail the cycle). Outbox
is not deleted when commit receipt CAS fails if the local entity is still dirty
or receipt was not written—avoid silent “pushed but no receipt” holes.

**Blocked by:** None — can start immediately (Android sync only).

**Status:** ready-for-agent

- [ ] Member post-push `pullAllPages` wrapped in full-resync recovery like other
      pull paths.
- [ ] On receipt CAS miss, do not markSynced/delete outbox when dirty still
      requires republish (or re-enqueue).
- [ ] JVM tests for 409 on member post-push path and CAS-miss retention.
