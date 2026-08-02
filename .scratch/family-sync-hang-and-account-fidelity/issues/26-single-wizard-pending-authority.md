# 26 — Single pending-waiting authority across onboarding and account

**What to build:** Waiting-for-admin UI is driven from durable
`pendingMemberLogin` (or one shared store), retracts when pending is null, and
does not prefer a zombie controller Waiting over overview null. Onboarding and
account do not leave two independent machines that disagree after root routing
swaps Member out of onboarding. Member first-sync recovery remains reachable
after onboarding unmount (or is explicitly owned by account/global retry).

**Blocked by:** Soft-after 01 (abandon honesty); can start in parallel with care.

**Status:** ready-for-agent

- [ ] pending == null always leaves waiting chrome (both entries).
- [ ] Onboarding abandon is not optimistic-dismiss-only; matches success-gated
      leave-waiting.
- [ ] Member + RetryRequired after onboarding join has a recovery path without
      relying on destroyed onboarding Completed state.
- [ ] Dual collector / dual controller harm is eliminated or documented with
      forced reset on hide.
