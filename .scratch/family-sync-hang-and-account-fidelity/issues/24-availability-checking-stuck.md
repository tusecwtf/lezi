# 24 — Availability never sticks in Checking without retry

**What to build:** Server availability state does not remain Checking forever
after a cancelled probe. LocalWrite-driven sync can probe again; a forced
recovery path always exists. False Available is demoted after transport sync
failure within product policy; network-recovered probes are rate-limited so
Wi‑Fi flap cannot burn battery.

**Blocked by:** None — can start immediately.

**Status:** ready-for-agent

- [ ] Cancel mid-probe restores previous availability or Unavailable with a
      nextProbeAt; never durable Checking with no schedule.
- [ ] LocalWrite while formerly Checking eventually probes or surfaces retry.
- [ ] Transport sync failure demotes or forces re-probe (not indefinite Available
      lease while SyncStatus.Error).
- [ ] NetworkRecovered probes are debounced/rate-limited under flapping.
- [ ] Tests for cancel-while-Checking and LocalWrite recovery.
