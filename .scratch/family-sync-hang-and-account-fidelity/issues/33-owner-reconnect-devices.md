# 33 — Owner reconnect without ghost devices or sticky login id

**What to build:** Network-settings owner reconnect does not silently mint unbounded active Owner devices for the same phone recovery path. Login request id lifecycle allows recovery after crash and forbids permanent conflict when takeover/login mode flips. Same device name reconnect is workable (replace or revoke-old).

**Blocked by:** None.

**Status:** implemented — awaiting live acceptance on `6b278242`

- [x] Reconnect same device does not leave an extra active Owner device by default.
- [x] Durable login request id retry-safe; conflict surfaces recoverable Chinese copy.
- [x] Takeover vs login mode cannot permanently brick against a committed server row without a new id path.

**Residual:** request-id lifecycle and conflict recovery are accepted by automated tests;
real-account acceptance remains:

- [ ] Reconnect the real Owner phone and verify no ghost active Owner device is created.
