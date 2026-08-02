# 06 — Busy-bound destructive family confirms

**What to build:** Leave family, logout this device, revoke device, delete baby,
and merge baby keep their confirm UI open with busy labels until the mutation
settles. Users cannot re-fire the same action by reopening chrome mid-flight;
dismiss is blocked while busy; success/failure is shown before the dialog goes
away (or via a durable message that still owns the outcome).

**Blocked by:** None — can start immediately.

**Status:** ready-for-agent

- [ ] Leave / logout / revoke: confirm stays up with busy; no fire-and-forget
      dismiss-then-async without feedback.
- [ ] Delete baby / merge baby: same busy contract; double confirm cannot start
      two merges/deletes.
- [ ] Delete family already has a deleting flag — dismiss while deleting is
      blocked or outcome is still visible.
- [ ] Host-level in-flight gate or mutex so a second call is ignored with UI
      feedback.
- [ ] Feature tests cover busy-disabled confirm and single mutation.
