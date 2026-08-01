# 05 — 拷出、dry-run、校验 CLI

**What to build:** 一条运维可跟的本机流水线：**从 NAS 拷出** data 到本地 backup（只读保留）、对副本 **dry-run/正式迁移**、用当前二进制 **校验** 产出。默认不改 NAS；报告含计数与失败原因。为 06 的拷回提供「已校验的 out/ 目录」约定。

**Blocked by:** 02 — 本机离线库迁移器 v3→当前；03 — 媒体文件与 publications 映射；04 — 根密码重置与当前服务可启动

**Status:** complete

> Implementation is complete on the worktree (`cli.rs`, `copy-out-nas-data.sh`, main/lib wiring, tests). Per operator policy **skip_commit** for this tracker, the surface is **not** required to be on master before 06/07 — agents must use the dirty worktree / local build. 06 may proceed.

## Acceptance criteria

- [x] 文档/脚本步骤：SSH/scp 或 rsync 从 NAS 数据卷拷出完整 data（含 db、media、secret 等）到带时间戳的本地 backup
- [x] 迁移只读 backup、写入独立 out/（或显式 --in/--out），永不就地改 backup
- [x] dry-run 或 migrate：成功打印将迁对象计数；失败非零退出 + 报告（validate 打印 `validate ok` / 失败原因，不重复计数）
- [x] 校验步骤：当前 lezi-sync **预检**（`Store::preflight_existing_schema` + `server.secret ≥ 32`）对 out/ 通过；完整 `/ready` 需 TLS/进程配置（票 04 测试 + 票 06 cutover）
- [x] 明确：本票不停止现网容器、不拷回

## Out of scope

- 维护窗替换现网（见 06）

## Notes

### Public seams (self-confirmed)

| Seam | Behavior |
|------|----------|
| `parse_args` / `CliCommand` | argv → `migrate` \| `dry-run` \| `validate` \| `copy-out-help` \| `help`; unknown flags / short password → usage |
| `run(CliCommand) -> CliOutcome` | exit `0` ok / `1` migrate\|validate fail / `2` usage; stdout counters on success migrate/dry-run only |
| `migrate --in --out --new-root-password` | refuse equal/nested in/out; require empty out; `migrate_v3_data_dir` then validate; post-validate fail cleans migrator outputs + `NOT copy-back-ready` |
| `dry-run --in` | temp out + counts + preflight; cleanup failure → non-zero |
| `validate --out` | preflight + `server.secret` length ≥ `SERVER_SECRET_BYTES` |
| `copy-out-help` | points at authoritative `deploy/copy-out-nas-data.sh` + env table |
| `lezi-sync offline-migrate …` / `offline_migrate_main` | main binary dispatch; password flag or `LEZI_MIGRATE_NEW_ROOT_PASSWORD` |

### Implementation

- `tools/lezi-sync/src/offline_migrate/cli.rs`
- `tools/lezi-sync/src/main.rs` — `offline-migrate` subcommand
- `tools/lezi-sync/src/lib.rs` — `pub fn offline_migrate_main`
- `tools/lezi-sync/deploy/copy-out-nas-data.sh` — authoritative copy-out (hot-copy notes, user_version check, fail-closed RO)

### Ops pipeline (this ticket only)

```text
copy-out (script/help) → local backup/ (read-only)
  → dry-run --in backup/          # counts + fail-closed; no durable out
  → migrate --in backup/ --out out/   # out must be empty & independent of in
  → validate --out out/           # preflight+secret gate for ticket 06
# does NOT stop live container; does NOT copy back
```

### Tests

```bash
cargo test --locked offline_migrate::cli
cargo test --locked offline_migrate
cargo test --locked
```
