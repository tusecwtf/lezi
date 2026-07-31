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
cd tools/lezi-sync && LEZI_SYNC_VERSION=0.3.0 ./build-image.sh

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
| `LEZI_BOOTSTRAP_SECRET` | only if no live container to inherit |

## Stages

1. **package-nas.sh** — `docker save` + render `docker-compose.yml` + `MANIFEST.json` + `SHA256SUMS` → `dist/lezi-sync-<ver>-nas/`
2. **push-and-deploy.sh** — scp package to `~/lezi-sync-releases/...` on NAS
3. **remote-deploy.sh** (on NAS) — `docker load` → inherit secret → initialize/validate persistent TLS → stop/rm old container → **zdocker compose up** → HTTPS `/health` + `/ready`

## Secret handling

- Never committed. Deploy writes `~/.../.env` mode `600` on NAS only.
- Source order: `LEZI_BOOTSTRAP_SECRET` env → else `docker inspect lezi-sync` env.
- App create/reclaim must keep using the same value.

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
