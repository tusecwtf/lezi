# 17 — Settings clear-records and custom-item durable busy

**What to build:** Clear-records two-step confirm and custom-item add/update/
reorder/hide cannot double-fire or lose busy chrome across configuration
change. Save/reorder are single-flight; clear busy lives in ViewModel (or
equivalent process-stable state), not only composition `remember`.

**Blocked by:** None — can start immediately.

**Status:** complete — accepted on `6b278242`

- [x] Clear-records: busy/step survive rotation; second confirm cannot start
      parallel clear while first runs.
- [x] Custom item add/update: busy-bound primary like delete.
- [x] Hide/reorder: serialized RMW so rapid taps do not clobber sortOrder/hide.
- [x] Forced about install path does not demote non-dismissible Forced UX to a
      soft Message that drops force chrome on failure (or root shell remains
      sole authority and about re-projects Forced).
- [x] Tests for clear busy durability and custom-item single-flight.
