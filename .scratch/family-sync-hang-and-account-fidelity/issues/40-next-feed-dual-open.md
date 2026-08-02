# 40 — Dual open next-feed markers across devices

**What to build:** Family has at most one open next-feed CarePlan after sync. Offline multi-device complete→reschedule that creates different client_uuids is healed on apply/pull (like open-sleep heal), not only on the next local scheduleNextFeedCarePlan call. Dual open markers do not leave dual alarms / dual fulfill intent.

**Blocked by:** Soft with 32 (family-global single-open pattern).

**Status:** ready-for-agent

- [ ] Two devices offline complete+reschedule with divergent seeds → after sync one open remains.
- [ ] Pull/apply path heals extra open next-feed markers, not only local schedule.
- [ ] Same-UUID concurrent create remains no-op (existing); this ticket is divergent UUID only.
