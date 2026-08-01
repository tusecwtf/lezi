# lezi-sync NAS CD (SSH + zdocker)

## Decisions

| Item | Choice |
|---|---|
| Trigger | Dev machine scripts (package → scp → SSH deploy) |
| Compose engine on NAS | **zdocker** bundled `docker-compose` v2 (`/zspace/applications/services/zdocker/bin/docker-compose`) |
| Bootstrap secret | **Inherit** from running `lezi-sync` container env |
| TLS identity | Generate once under the persistent `/data/tls`; validate and reuse on every replace |
| System `docker compose` | Not required / not installed |

## One-shot (from repo root)

```bash
# Optional: rebuild image first
cd tools/lezi-sync && LEZI_SYNC_VERSION=0.3.2 ./build-image.sh

# Package + scp + remote deploy
./deploy/push-and-deploy.sh
```

Environment overrides:

| Variable | Default |
|---|---|
| `NAS_SSH` | `13096920600@192.168.50.4` |
| `NAS_SSH_PORT` | `10000` |
| `NAS_REMOTE_DIR` | `/tmp/lezi-sync-releases/lezi-sync-<ver>-nas`（该机 `HOME=/home/` 不可写） |
| `LEZI_SYNC_VERSION` | from `Cargo.toml` |
| `LEZI_DATA_HOST_PATH` | `/tmp/zfsv3/sata1/13096920600/data/Docker/lezi/data` |
| `LEZI_TLS_HOST` | `192.168.50.4`; DNS name or IP included in the self-signed certificate SAN |
| `LEZI_FORCE_PACKAGE=1` | rebuild package even if present |
| `LEZI_SKIP_PACKAGE=1` | only scp+deploy existing `dist/lezi-sync-*-nas` |
| `LEZI_PACKAGE_BUILD_IMAGE=1` | `package-nas.sh` builds image if missing |
| `LEZI_BOOTSTRAP_SECRET` | only with `LEZI_FORWARD_BOOTSTRAP_SECRET=1` (cutover / no live container to inherit). Ordinary CD leaves this unset so remote-deploy inherits from the live container. |
| `LEZI_FORWARD_BOOTSTRAP_SECRET=1` | opt-in: SSH-forward local `LEZI_BOOTSTRAP_SECRET` into remote-deploy. Required for offline-migrate cutover after stop/rm. Do **not** set for ordinary CD — a leftover local secret would otherwise rotate `owner_root_fingerprint` and revoke owner devices. Unset both after cutover. |
| `LEZI_RELEASE_APK` | signed release APK path (default repo `app/build/outputs/apk/release/app-release.apk`) |
| `LEZI_APP_UPDATE_JSON` | app-update metadata JSON (default `deploy/app-update.json`) |

## Stages

1. **package-nas.sh** — `docker save` + render `docker-compose.yml` + **fail-closed app-update artifacts** + `MANIFEST.json` + `SHA256SUMS` → `dist/lezi-sync-<ver>-nas/`
2. **push-and-deploy.sh** — scp package to `~/lezi-sync-releases/...` on NAS
3. **remote-deploy.sh** (on NAS) — `docker load` → inherit secret → initialize/validate persistent TLS → **copy APK + metadata into data bind** → stop/rm old container → **zdocker compose up** → HTTPS `/health` + `/ready`

## Self-hosted app update (release APK)

Packaging is **fail-closed**: missing release APK, missing metadata, or sha256 mismatch aborts with non-zero exit. No “empty update channel” package is produced.

| Input | Default | Override |
|---|---|---|
| Release APK | `app/build/outputs/apk/release/app-release.apk` | `LEZI_RELEASE_APK` |
| Metadata JSON | `tools/lezi-sync/deploy/app-update.json` | `LEZI_APP_UPDATE_JSON` |

Metadata contract (`app-update.json`, snake_case):

```json
{
  "package_name": "com.lezi.babylog",
  "version_code": 8,
  "version_name": "0.3.2",
  "min_supported_version_code": 6,
  "sha256": "<64 lowercase hex of the APK file>",
  "release_notes": "可选"
}
```

- `package_name` must be `com.lezi.babylog` (release applicationId only; **not** debug suffix).
- `version_code` must be a positive 32-bit integer (`1..=2147483647`); `min_supported_version_code`
  must be `0..=2147483647` and **must not exceed** `version_code` (package-nas and lezi-sync
  both reject out-of-range codes and the deadlock case `min_supported > version_code` so
  clients are never forced above the package on the channel).
- `sha256` must match `sha256sum` of the APK byte-for-byte (64 lowercase hex).
- Raise `min_supported_version_code` only for **breaking** client contracts; clients below that
  value get `code=client_update_required` on authoritative sync paths, but can still call the
  app-update routes with a valid session.
- Package layout: `app-update/app-release.apk` + `app-update/app-update.json`.
- On deploy, files are installed to the data bind as `/data/app-release.apk` and `/data/app-update.json` (container uid `10001`).
- Authenticated clients only (Bearer device session): `GET /v1/app-update` (JSON) and
  `GET /v1/app-update/apk` (`application/vnd.android.package-archive`; integrity re-checked
  server-side). No anonymous / public CDN URL for the family APK.
- Product channel is **self-hosted sideload** (Android `PackageInstaller`), **not** Google Play
  In-App Updates. See [`docs/prd/tech.md`](../../../docs/prd/tech.md) §4.2.

Optional path overrides on the server process: `LEZI_APP_UPDATE_METADATA_PATH`, `LEZI_APP_UPDATE_APK_PATH`.

Quick local checks (from `tools/lezi-sync`):

```bash
# Lightweight CI smoke (no docker save): missing APK / wrong sha fail; matching inputs pass
./deploy/test-package-nas-app-update.sh

# App-update inputs only (set LEZI_PACKAGE_APP_UPDATE_CHECK_ONLY=1 on package-nas.sh)
LEZI_PACKAGE_APP_UPDATE_CHECK_ONLY=1 \
  LEZI_RELEASE_APK=/nonexistent/app-release.apk \
  LEZI_APP_UPDATE_JSON=deploy/app-update.json \
  ./deploy/package-nas.sh
# expect non-zero exit

# fail-closed: missing APK (full package path still aborts early)
LEZI_FORCE_PACKAGE=1 LEZI_RELEASE_APK=/nonexistent/app-release.apk ./deploy/package-nas.sh
# expect non-zero exit

# success path (image or reusable dist tar present)
LEZI_FORCE_PACKAGE=1 \
  LEZI_RELEASE_APK=../../app/build/outputs/apk/release/app-release.apk \
  LEZI_APP_UPDATE_JSON=deploy/app-update.json \
  ./deploy/package-nas.sh
# expect dist/lezi-sync-<ver>-nas/app-update/{app-release.apk,app-update.json}
```

## Secret handling

- Never committed. Deploy writes `~/.../.env` mode `600` on NAS only.
- Source order: `LEZI_BOOTSTRAP_SECRET` env → else `docker inspect lezi-sync` env.
- App create/reclaim must keep using the same value.
- **Never print** bootstrap secrets from `docker inspect`, logs, or report paste.

## offline-migrate 架构边界（非普通 CD）

`lezi-sync offline-migrate` 是已授权**维护窗**中的 v3→current **离线切割** CLI，
**不是**服务启动/runtime 自动迁移，也**不**推翻 NAS fresh-current / fail-closed
（[ADR-0008](../../../docs/adr/0008-support-only-fresh-current-product-contracts.md)）。
架构 disposition 见
[ADR-0013](../../../docs/adr/0013-offline-migrate-is-maintenance-window-cutover.md)。

| 规则 | 说明 |
|------|------|
| 普通 CD | `package-nas` / `push-and-deploy` / 容器重启 **不得执行** `offline-migrate` |
| 启动合同 | 现网进程只打开精确 current schema；旧库 fail closed，无自动迁移 |
| 切割流水线 | 显式 CLI、停服、固定源 v3→current、独立临时 `out/`、`validate` 后再 copy-back |
| 权威 runbook | [`copy-back-tls-cutover-runbook.md`](./copy-back-tls-cutover-runbook.md)（步骤、双备份、回滚、secret 转发） |
| Cutover secret | 仅维护窗：迁移期新根密码 → `LEZI_BOOTSTRAP_SECRET`；可用 `LEZI_FORWARD_BOOTSTRAP_SECRET=1`；**普通 CD 勿设** |
| 发布二进制 | 可含该子命令 ≠ 滚动 schema 兼容产品承诺 |

产品 README 摘要：[`../README.md`](../README.md) § 离线 v3→current 切割。

## TLS identity

- `init-tls.sh` creates `/data/tls/server.crt` plus mode-`600` `server.key` only when both are absent.
- Container replacement and ordinary restart reuse those files and therefore the same SPKI.
- A missing half, invalid/expired certificate, or mismatched key fails closed; deployment never silently rotates identity.
- The private key is absent from the image, Git, package directory, logs, `MANIFEST.json`, and `SHA256SUMS`.
- Deployment prints only the public SPKI SHA-256 fingerprint so it can be compared with the Android TOFU screen.

## Rollback

```bash
# On NAS: load previous tar and re-run that package's remote-deploy.sh
cd ~/lezi-sync-releases/lezi-sync-<old>-nas
./remote-deploy.sh
```

Data directory is a bind mount; stop/rm container does **not** delete family data.

## Web UI (optional)

After SSH deploy, the container is managed by compose project name `lezi` with `container_name: lezi-sync`. The zdocker **Compose projects** list is a separate SQLite registry; SSH compose up may not create a UI project row. Containers page should still show `lezi-sync`. To also register in the UI, import the packaged `docker-compose.yml` once (same image/data); avoid running two stacks on port 8765.

## Preconditions on NAS

- SSH key login, user in `docker` group
- Data dir owned by `10001:10001`
- Port 8765 free after old container stop
- `curl` available for health checks
