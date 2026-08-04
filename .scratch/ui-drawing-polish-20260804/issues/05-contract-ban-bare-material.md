# 05 — Contract: ban bare Material outside wrappers

**What to build:** The UI audit path contract fails the suite if designsystem
(or product modules) reintroduce bare Material controls outside an explicit
wrapper-implementation whitelist. Chrome language is locked by gate, not hope.

**Blocked by:** 04 — Remaining designsystem chrome.

**Status:** done

- [x] Contract scans designsystem main sources with a documented whitelist of wrapper bodies only
- [x] Product modules remain banned (existing rules kept or tightened)
- [x] Introducing a bare TextButton/OutlinedTextField/etc. outside whitelist fails tests
- [x] SummaryMetric vs RecordSummaryStrip division-of-labor guard still present
- [x] `./gradlew :designsystem:test` green
