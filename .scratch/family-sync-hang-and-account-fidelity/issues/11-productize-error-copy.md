# 11 — Productize sync, HTTP, and DR error copy

**What to build:** Family wizard, members, network settings, and disaster
recovery never show raw `HTTP {code}` payloads, internal recoveryStatus enums,
or server detail blobs to end users. Errors map to short Chinese action copy
(check home network, retry, re-login, contact admin, etc.). Operators can still
log details at debug level.

**Blocked by:** None — can start immediately.

**Status:** complete — accepted on `6b278242`

- [x] `familySyncError` / HTTP formatter path used by UI does not pass through
      raw HTTP status + body for product surfaces.
- [x] Disaster recovery UI never prints raw `recoveryStatus` machine strings for
      unknown states; uses a safe fallback phrase.
- [x] Snapshot tests or string assertions: no `HTTP `, no bare internal status
      tokens in user-visible family feedback.
- [x] Unjoined-but-remembered endpoint: account can show continue/forget style
      recovery consistent with onboarding (or ticket documents deferral if folded
      into 03).
