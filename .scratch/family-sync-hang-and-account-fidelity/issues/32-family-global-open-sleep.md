# 32 — Multi-device open sleep: family-global wake

**What to build:** Concurrent 睡下 on two devices does not leave the family “still sleeping” after one device confirms 醒来. Product rule is family-global: wake closes all open sleeps for the baby (or equivalent single logical open identity), not only the local client_uuid.

**Blocked by:** None.

**Status:** complete — accepted on `6b278242`

- [x] Two open UUIDs; one wake → no remaining open sleep on either replica after sync.
- [x] Heal/wrong-end residual documented or improved without reintroducing dual open.
- [x] Cross-device offline→online matrix test or equivalent JVM fixtures.
