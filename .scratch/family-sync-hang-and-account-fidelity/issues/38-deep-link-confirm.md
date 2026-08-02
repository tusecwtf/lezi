# 38 — Untrusted deep-link confirm for fulfill and composer

**What to build:** Exported MainActivity does not open fulfill composer or switch current baby from unauthenticated intents without user confirmation. BROWSABLE care-plan links and widget OPEN_RECORD_COMPOSER are treated as untrusted navigation.

**Blocked by:** None.

**Status:** ready-for-agent

- [ ] Foreign app cannot silently setCurrentBaby + open composer.
- [ ] Fulfill deep link shows confirm (or same-app-only) before composer.
- [ ] Device test or instrumented intent spoof denied without confirm.
