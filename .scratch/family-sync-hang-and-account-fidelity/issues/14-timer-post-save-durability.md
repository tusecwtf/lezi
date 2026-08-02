# 14 — Timer post-save durability across back and clear

**What to build:** Completing a nursing save cannot lose post-commit work or
next-feed offer because the user hits top-bar/system back, and cannot leave a
ghost timer session after a successful record if DataStore clear fails. Back is
gated while saving or while a next-feed offer is pending (consistent with ticket
12 product policy). Live timer state does not rewrite disk after local clear /
family join invalidates the session.

**Blocked by:** 12 — Timer leave vs discard policy (share one back policy).

**Status:** ready-for-agent

- [ ] Back/pop while `completionUi.saving` is blocked or defers until
      NonCancellable succeed+clear finishes.
- [ ] Back during next-feed either finishes/skips the offer durably or keeps
      stage outside nav-scoped loss.
- [ ] Post-success timer clear failure retries or sticky-flags until JSON is
      empty; no “memory empty / disk full session” ghost after leave.
- [ ] After local clear (or session invalidation), in-memory timer cannot
      re-persist a stale baby/session without user starting fresh.
- [ ] STARTING-before-markActive clear path does not leave orphan FGS without
      durable session (stop-by-token or equivalent).
- [ ] Tests: cancel/pop mid-save after domain commit; clear failure recovery;
      clear then no ghost re-persist.
