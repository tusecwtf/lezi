# 22 — Crash-safe refresh token rotation

**What to build:** A successful server refresh that rotates tokens must not leave
an honest client with only the previous refresh on disk such that the next
request is classified as refresh_replay and the device is revoked. Rotation is
crash-safe (grace previous hash and/or durable pending handoff before discarding
the old refresh).

**Blocked by:** None — can start immediately; may need lezi-sync grace (document
if server change required).

**Status:** ready-for-agent

- [ ] Kill/process death after server accepts refresh but before durable new
      refresh save does not permanently self-revoke via refresh_replay for that
      single-client crash window.
- [ ] Concurrent dual-refresh still fail-closed as designed (no widen of intentional
      multi-device theft detection).
- [ ] JVM and/or Rust tests for rotate-then-crash recovery path.
