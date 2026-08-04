# 05 — 灾难恢复也走客户端版本门槛

**What to build:** Owner disaster-restore write paths enforce the same honest client version floor as authoritative sync when a verified min_supported channel exists. An outdated official client cannot stage/commit a restore under version skew; it gets the same force-upgrade signal as pull/bundle. Empty-family restore contract and restore protocol pin stay; current clients that meet the floor are unchanged.

**Blocked by:** None — can start immediately.

**Status:** done

- [x] Below-min or missing client version header on restore write paths → `client_update_required` (same wire code as sync gate)
- [x] Client at or above min can still complete empty-family restore as today
- [x] App-update download paths remain ungated by min so force upgrade is not deadlocked
- [x] Server tests for restore + version header cases
