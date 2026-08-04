# 04 — setup 探测容忍服务端加字段

**What to build:** A lezi-sync setup-status response may gain **additional** JSON fields without existing clients treating the endpoint as non-lezi. Known required fields and the required capability set still decide Ready vs Incompatible. Missing required shape or insufficient capabilities still fail closed honestly (Incompatible / not Ready), not as “not a lezi server” solely because of extra keys.

**Blocked by:** None — can start immediately.

**Status:** done

- [x] Probe with protocol 1, full required capabilities, and one extra unknown field → Ready (empty or configured family as before)
- [x] Missing required capabilities or wrong protocol → still Incompatible (or equivalent non-Ready), not misclassified solely by extra keys
- [x] Exact current three-field responses remain Ready (no regression)
- [x] Client tests cover additive fields and capability failure
