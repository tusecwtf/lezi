# 21 — Recover member claim after server-side claimed without local session

**What to build:** If the NAS has already claimed a member request (status
`claimed`) but this device never durable-saved credentials (crash, timeout after
server commit, parse failure), the client must attempt claim replay with the
pending secret instead of treating Claimed as a terminal “凭据已使用” and clearing
the local slot. User can finish join without re-applying when the server still
allows replay.

**Blocked by:** None — can start immediately (coordinates with 01/02 on pending
slot honesty).

**Status:** complete — accepted on `6b278242`

- [x] Status `claimed` with local pending secret triggers claim/replay path, not
      immediate terminal clear + re-apply-only copy.
- [x] Only after durable session save (or true non-replayable conflict) is pending
      cleared as terminal.
- [x] Reconnect claim path matches the same recovery rule.
- [x] JVM tests: approved→claim server-ok / client die / next poll claimed →
      session recovered.
