# 05 — Gate baby-create and add-baby busy

**What to build:** Onboarding “开始记录” and Settings “添加宝宝” cannot create two
babies from rapid re-taps. Primary actions show in-flight busy, ignore further
taps until success or a retryable error, and leave the user on a single new baby
(or a clear failure).

**Blocked by:** None — can start immediately.

**Status:** complete — accepted on `6b278242`

- [x] Onboarding create-baby primary is disabled/busy while create runs; second
      tap does not enqueue another `addBaby`.
- [x] Settings add-baby confirm is busy-bound the same way.
- [x] Failure restores a tappable retry without leaving a half-created second baby.
- [x] Unit or host tests cover double-tap → single create.
