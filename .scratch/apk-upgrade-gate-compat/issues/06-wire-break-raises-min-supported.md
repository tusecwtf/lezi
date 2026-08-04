# 06 — 破坏性 wire 变更必须先抬 min_supported

**What to build:** When a change breaks the closed family wire (new entity type, closed key set change, record/care_plan `schema_version` bump, or server allowlist that older clients cannot parse), the release process requires raising `min_supported_version_code` and shipping a verified installable package **before** new clients can publish the new shape. Operators and agents have a short checklist (and optional lightweight reminder) so multi-device home LAN does not get silent pull stalls on older phones without a force-update path. No skip-unknown dual-read wire; ADR-0008 stays.

**Blocked by:** None — can start immediately.

**Status:** done

- [x] Written checklist in product/deploy docs: wire-breaking change → raise min + package APK before enabling new writes
- [x] Relationship of current floor (versionCode baseline) to “wire frozen within supported range” is explicit
- [x] Optional: lightweight CI or PR note for wire-touching paths (non-blocking reminder acceptable)
- [x] No runtime dual-compat protocol introduced in this ticket
