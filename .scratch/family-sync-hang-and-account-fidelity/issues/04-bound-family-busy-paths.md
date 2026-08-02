# 04 — Bound remaining family busy paths

**What to build:** Family actions that hold busy flags fail closed within a
user-reasonable wall-clock bound (product default: session-establish ~20–30s;
roster/network probe ~ same order as HTTP 8s×N with a hard host ceiling). After
ticket 02, session-establish busy excludes first full replica sync. Roster
refresh keeps prior data on timeout; network-settings non-DR actions clear busy
with retryable Chinese errors. DR long upload is out of scope here (ticket 07).

**Blocked by:**

- **04a (session-establish bounds):** 02 — Finish join before first full sync.
- **04b (roster / network timeouts):** None — can start immediately; soft-serial
  with 07 on `FamilyNetworkSettingsHost`.

**Status:** ready-for-agent

- [ ] 04a: Create/owner/check submit chrome ends by success, retryable error, or
      timeout ≤ T without waiting full multi-page media sync.
- [ ] 04b: Roster refresh timeout → not loading forever; prior roster retained +
      retryable error.
- [ ] 04b: Network-settings probe/reconnect (non-DR) timeout → busy false +
      actionable copy.
- [ ] DR long steps not “timed out” as false success (owned by 07).
- [ ] Tests: hung listFamilyMembers / never-answering probe → not-busy + retry.
