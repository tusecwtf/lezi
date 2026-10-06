# 01: 数据根持久化 generation

**What to build:** `{LEZI_DATA_DIR}/generation` load-or-create；同一数据根重启复用，旧 cursor pull 不 409。`config.generation` 覆盖仍走 drift/full_resync。

**Status:** done

- [x] `restart_reuses_persisted_generation_and_continues_cursor`
- [x] 现有 `pull_generation_drift_is_full_resync_not_empty_success` 保持
- [x] README / tech.md 合同
