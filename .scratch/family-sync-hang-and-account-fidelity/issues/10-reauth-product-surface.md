# 10 — ReauthRequired product surface

**What to build:** When credentials are reauth-required, the account and data
pages do not look like a never-joined offline device. Users see a clear
re-login / re-apply path, retain understanding that a family relationship may
still exist (e.g. network settings / owner recovery eligibility), and log/
summary surfaces surface the failure rather than only generic Error or silent
Idle.

**Blocked by:** None — can start immediately; pairs with 09 for status copy.

**Status:** ready-for-agent

- [ ] Account primary CTA and chrome distinguish reauth from true unjoined.
- [ ] Joined-only actions are not silently offered as if session were healthy;
      recovery actions remain reachable where product allows.
- [ ] Log (and preferably summary/growth) banner or status treats ReauthRequired
      as actionable, not invisible.
- [ ] Semantics/unit coverage for reauth vs unjoined vs error.
