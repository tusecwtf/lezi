# 07 — 维护窗实切与本地 APK 联调

**What to build:** 在约定维护窗按 06 执行真实 cutover：拷回升级 data、启动当前 TLS 后端；用本地/调试 APK 指向 `https://<NAS-LAN-IP>:8765`，TOFU 后以**新根密码**完成 owner 设备会话，确认历史权威记录与媒体可见，并新记一条可同步。证据写入本 tracker `evidence/`。

**Blocked by:** 06 — 拷回 NAS 与 TLS cutover runbook

**Status:** complete

## Acceptance criteria

- [x] 维护窗按 runbook 执行；现网 health/ready 为当前版本且 HTTPS（或记录实际探测 URL）
- [x] 本地 APK（debug 或约定构建）完成 endpoint 信任与 owner 登录
- [x] 迁移前存在的权威记录（抽样）在客户端可见；若有媒体则抽样可见
- [x] 新写一条记录可同步回家庭（第二设备可选，非必须）
- [x] `evidence/07/`（或约定路径）记录版本、时间窗、结果；失败则记录回滚结果

## Out of scope

- 全量双设备矩阵（除非维护窗有余力）
- 把私有脚本产品化为通用升级器

## Notes

### Public seams (self-confirmed)

| Seam | Behavior |
|------|----------|
| `live_cutover_evidence_required_files()` | `window.md`, `health.json`, `owner-api-smoke.md`, `apk-smoke.md`, `RESULT.md` |
| `live_cutover_apk_smoke_steps()` | TrustHttpsEndpointTofu → OwnerLoginWithNewRootPassword → HistoricalRecords → NewRecordSyncs |
| `live_cutover_help_text()` / `CliCommand::LiveCutoverHelp` | `live-cutover-help` \| `ticket-07-help` → exit 0; evidence path + runbook order; **never** invents live success |
| `deploy/live-cutover-probe.sh` | Probe actual protocol; write `health.json`; optional owner login (secrets redacted) |
| Evidence dir | `.scratch/nas-v3-offline-migrate/evidence/07/` |

### Live execution (2026-08-01)

- Copy-out via docker tar (host bind mode 700); migrate with display_name remediations (`爸爸` → `爸爸·设备2` on one membership).
- Cutover: stop → NAS backup → docker-assisted copy-back → TLS CD with `LEZI_FORWARD_BOOTSTRAP_SECRET=1`.
- Live: `https://192.168.50.4:8765` health/ready version `0.3.0`; SPKI `BB:A1:05:DE:…:9E:45`.
- APK emulator TOFU + owner login; timeline shows 年年 history; API write of formula smoke record committed.
- Evidence: `evidence/07/RESULT.md` **PASS**.

### Ops hardening shipped with this ticket

- `copy-out-nas-data.sh`: `LEZI_COPY_OUT_VIA_DOCKER=auto|1` for mode-700 binds.
- `copy-back-nas-data.sh`: docker-assisted stage/chown/swap when SSH user cannot write parent.
- `remote-deploy.sh`: docker install of app-update artifacts; health probe fallbacks when cert unreadable.

### FIX+AMEND (post-review)

- Probe: LAN HTTPS required for exit 0; container readiness via `docker exec lezi-sync lezi-sync healthcheck` (not host:8766).
- `json_escape` no trailing newline; version gate; owner pull with generation + client-version-code.
- `remote-deploy` health: no docker exec curl (image has none).
- Help composes ticket-06 cutover text; APK steps 1–2 required from real APK.
- Evidence PII redacted; `test-live-cutover-probe.sh` + copy-back docker-path smokes.
