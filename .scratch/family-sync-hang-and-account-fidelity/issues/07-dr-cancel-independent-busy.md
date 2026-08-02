# 07 — Network host busy hygiene (DR cancel + finally)

**What to build:** Family network settings `launchBusy` never silently no-ops
cancel while start holds busy. Disaster-recovery cancel remains reachable during
upload (preempt or separate channel). Any cancelled or completed action clears
busy in `finally`. Interrupted start does not leave zombie “uploading” product
state. Roster/general timeouts stay ticket 04b.

**Blocked by:** None — can start immediately; soft-serial with 04b on the same host.

**Status:** ready-for-agent

- [ ] During DR start/upload busy, cancel remains reachable and is not a silent
      return.
- [ ] Successful cancel clears checkpoint/status; failure is retryable.
- [ ] `launchBusy` always clears busy on cancel/exception (finally).
- [ ] Start interrupted by cancel does not leave zombie uploading chrome.
- [ ] Host/unit tests: busy start + cancel → terminal cancelled/idle; cancel
      mid-busy clears busy flag.
