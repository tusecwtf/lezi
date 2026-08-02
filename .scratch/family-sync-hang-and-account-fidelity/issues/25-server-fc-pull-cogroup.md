# 25 — Server: co-group fulfillment_candidate pull deps

**What to build:** Pull pages that include fulfillment_candidate always co-group
required care_plan and record (and their existing media deps) so clients never
spin forever on unresolved FC with frozen cursor. New devices and full resync
converge.

**Blocked by:** None — can start immediately; **lezi-sync release**.

**Status:** ready-for-agent

- [ ] FC entities pull with plan+record co-group so client apply can resolve.
- [ ] Regression: page with only FC after plan/record advanced revs still
      co-groups live parents or re-emits them.
- [ ] Rust/API tests prove client-style unresolved set empties.
