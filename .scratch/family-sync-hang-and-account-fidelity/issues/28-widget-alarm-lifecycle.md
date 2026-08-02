# 28 — Widget and care-plan alarm lifecycle

**What to build:** Local clear and family wipe clear widget prefs/snapshots so
the launcher does not show prior-family baby stats. Deleting a baby cancels its
care-plan alarms and rebinds/removes widgets. Posted “到点” notifications cancel
when plans complete/skip/delete. Alarm request codes do not collide for long-lived
plan ids. Enabling local reminders requests or durable-surfaces notification
permission on API 33+. In-place APK upgrade / `MY_PACKAGE_REPLACED` / install
SUCCESS re-arms reminders and refreshes widgets (shared rehydrate entry with
ticket 37 calendar path).

**Blocked by:** None — can start immediately; soft-serial with 37 on package-replaced.

**Status:** ready-for-agent

- [ ] Local clear / leave family wipes widget state or forces unconfigured redraw.
- [ ] Baby delete cancels that baby’s plan alarms; no ghost due notif.
- [ ] Complete/skip/delete plan cancels drawer notification id.
- [ ] Request codes unique across plan id space (not 16-bit modulo only).
- [ ] POST_NOTIFICATIONS request or settings deep-link when reminders enabled.
- [ ] MY_PACKAGE_REPLACED / install SUCCESS → reschedule + widget refresh.
