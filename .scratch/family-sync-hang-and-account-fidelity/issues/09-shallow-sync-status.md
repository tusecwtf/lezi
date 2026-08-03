# 09 — Shared shallow sync status with outbox pending count

**What to build:** Account, log, summary, and growth show the same product-level
shallow sync result line promised by the trusted-sync UI contract: success with
relative/short time, local-only with **pending outbox count** when items are
waiting, temporary failure with pull-to-retry guidance, and waiting-for-admin
when applicable. No full datetime dump as the only success form; no silent
“looks idle” while backlog exists.

**Blocked by:** None — can start immediately (may share projectors with 03/10).

**Status:** complete — accepted on `6b278242`

- [x] One domain/sync projector (or equivalent) feeds account + data pages.
- [x] Pending outbox/local-change count appears when joined and items await
      publish (exact wording per PRD/design).
- [x] Summary/Growth do not use raw global Syncing alone as the only refresh
      chrome without user intent (prefer local refreshing flag like log where
      needed).
- [x] Unit tests for label matrix: Idle+pending, Idle+none, Syncing, Error,
      Reauth, unjoined, waiting approval.
- [x] No tokens, HTTP codes, or server IDs in the shallow line.
