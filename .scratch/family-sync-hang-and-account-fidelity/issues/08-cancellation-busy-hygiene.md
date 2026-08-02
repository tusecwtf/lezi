# 08 — Wizard/sync cancellation never sticks Submitting or Syncing

**What to build:** When a wizard submit/check job or synchronize is cancelled
mid-flight, UI never remains permanently Submitting; wizard `begin` works again.
Members roster loading clears on cancel. SyncStatus does not stay Syncing solely
because a cancelled synchronize never reached Idle/Error. Network-settings
`launchBusy` finally is ticket **07** (do not re-own host busy here).

**Blocked by:** Soft-after 01 on wizard abandon paths; can start immediately for
sync status / members loading.

**Status:** ready-for-agent

- [ ] Cancel while Submitting → Editing or RetryableFailure; begin works again.
- [ ] Members roster refresh cancel/finally clears loading.
- [ ] Cancelled synchronize does not leave orphan “正在同步…”.
- [ ] Tests force CancellationException mid-wizard-submit and mid-sync status.
