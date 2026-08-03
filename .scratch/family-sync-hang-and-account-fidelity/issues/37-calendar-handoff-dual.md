# 37 — Calendar handoff dual-notify and rehydrate

**What to build:** Editing a calendar-ready plan does not leave a window where Lezi and system calendar both fire. ABSENT event cleanup scrubs orphan reminders when required. Package-replace rehydrate is owned by ticket **28** (share entry point if needed; do not re-implement alone).

**Blocked by:** Soft with 28.

**Status:** complete — accepted on `6b278242`

- [x] Plan edit clears ready only with pending (or cancel under guard) so dual fire cannot occur.
- [x] ABSENT event cleanup scrubs orphan reminders when product requires.
- [x] If 28 rehydrate entry is shared, calendar path uses it without forking a second package-replaced handler.
