# 06 — 拷回 NAS 与 TLS cutover runbook

**What to build:** 可执行的维护窗 runbook：停现网容器 → 再次确认 NAS 侧备份 → 将本机**已校验**的 out/ **拷回** NAS 数据路径 → 按当前 CD 启 TLS 镜像 → 健康检查清单 → 失败时用拷出备份恢复 v3 服务。家人通知：HTTPS endpoint、TOFU、新根密码、全员重登。

**Blocked by:** 05 — 拷出、dry-run、校验 CLI

**Status:** complete

## Acceptance criteria

- [x] Runbook 步骤顺序固定：停服 → NAS/本地双备份确认 → 拷回升级 data → 启当前 TLS 部署 → health/ready（按实际协议探测）
- [x] 写明默认路径：NAS data bind、SSH、LAN `https://<NAS>:8765`、回滚命令要点
- [x] 回滚：恢复拷出的 v3 data + 旧镜像/旧启动方式，服务回到维护前可用态
- [x] 清单含：根密码已轮换告知、旧 APK/旧 HTTP 不可用说明、成员重登路径
- [x] 不在未执行 07 前声称现网已切成功

## Out of scope

- 实际点维护窗执行（见 07）
- 修改通用产品 ADR 为自动迁移

## Notes

### Public seams (self-confirmed)

| Seam | Behavior |
|------|----------|
| `cutover_maintenance_steps()` | Fixed order: Stop → DualBackup → CopyBack → StartTls → ProbeHealthByActualProtocol |
| `cutover_help_text()` / `CliCommand::CopyBackHelp` | `copy-back-help` \| `cutover-help` → exit 0; order, defaults, rollback, family checklist; **never** claims live success |
| `COPY_BACK_RUNBOOK` / `COPY_BACK_SCRIPT` | Repo paths to authoritative md + step-3 script |
| `deploy/copy-back-nas-data.sh` | Fail-closed copy-back only: requires confirm flags + current `user_version` + secret length; dry-run skips network |
| Runbook md | Full maintenance narrative + rollback + family notify; defers evidence to ticket 07 |

### Implementation

- `tools/lezi-sync/src/offline_migrate/cutover.rs` — step contract + help text + script/runbook contract tests
- `tools/lezi-sync/src/offline_migrate/cli.rs` — `copy-back-help` / `cutover-help`
- `tools/lezi-sync/deploy/copy-back-tls-cutover-runbook.md` — authoritative human runbook (Step 0 image save; mandatory `export LEZI_BOOTSTRAP_SECRET` = migration password; no inherit)
- `tools/lezi-sync/deploy/copy-back-nas-data.sh` — step 3: rsync-only, sqlite3 required, full validate, remote container/NAS-backup probes, staging+rename, uid 10001 fail-closed
- `tools/lezi-sync/deploy/push-and-deploy.sh` — forwards local `LEZI_BOOTSTRAP_SECRET` into remote-deploy when set
- `tools/lezi-sync/deploy/test-copy-back-nas-data.sh` — shell fail-closed smoke

### Fixed order

```text
1. stop live container
2. confirm dual backup (local copy-out + NAS-side)
3. copy-back upgraded out/ to NAS data bind
4. start current TLS deploy (CD)
5. health/ready by actual protocol
# ticket 07 executes + records evidence; 06 never claims live success
```

### Tests

```bash
cargo test --locked cutover
cargo test --locked copy_back
cargo test --locked offline_migrate
cargo test --locked
./deploy/test-copy-back-nas-data.sh
```
