# 01 — Make abandon-waiting honest and non-blocking

**What to build:** From the waiting-for-admin surface (and the candidate-server
reconnect path), the user can abandon a pending member request on this device
with visible busy feedback. The local pending slot clears even when the NAS is
offline or a foreground sync is slow; if the local slot is already empty, abandon
succeeds as a no-op and the UI leaves the waiting state (no zombie waiting dialog).
Remote cancel stays best-effort. Copy distinguishes “abandon this device’s wait”
from “keep offline / only dismiss UI”. The user can submit a new join request
afterward without being stuck on the previous pending.

**Blocked by:** None — can start immediately.

**Status:** ready-for-agent

- [ ] Waiting dialog shows busy/disabled actions while abandon runs; re-taps do not
      silently no-op without feedback.
- [ ] Abandon always leaves a non-waiting UI when local pending is gone; failure copy
      never claims the request is still kept unless local pending is still present.
- [ ] Empty-pending abandon is success (idempotent), including coordinator/gateway.
- [ ] Candidate reconnect cancel clears local attempt immediately; remote cancel does
      not block the network-settings busy flag when the NAS is unreachable.
- [ ] After abandon, a new member request can be submitted on the same device without
      a stuck local slot.
- [ ] Domain/sync unit coverage for idempotent abandon, offline remote cancel, and
      reconnect local-first cancel; UI/semantics cover busy abandon and leave-waiting.
