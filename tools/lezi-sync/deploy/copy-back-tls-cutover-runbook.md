# Copy-back + TLS cutover runbook (ticket 06)

**Status:** ops runbook only — **does not** execute the live maintenance window.  
**Live execution + APK smoke:** ticket 07 (`.scratch/nas-v3-offline-migrate/issues/07-live-cutover-and-apk-smoke.md`).  
**Do not claim the family NAS is cut over until ticket 07 evidence exists.**

Machine-readable step order: `offline_migrate::cutover::cutover_maintenance_steps()`.  
CLI pointer: `lezi-sync offline-migrate copy-back-help` (alias `cutover-help`).  
Copy-back step script: [`copy-back-nas-data.sh`](./copy-back-nas-data.sh).  
CD narrative: [`DEPLOY.md`](./DEPLOY.md) and root `AGENTS.md` § lezi-sync NAS CD.

## Fixed step order (do not reorder)

These five labels are the shared contract with `cutover_step_labels()` / CLI help:

1. stop live container
2. confirm dual backup (local copy-out + NAS-side)
3. copy-back upgraded out/ to NAS data bind
4. start current TLS deploy (CD)
5. health/ready by actual protocol

Full numbered form used by help/script headers:

- `1. stop live container`
- `2. confirm dual backup (local copy-out + NAS-side)`
- `3. copy-back upgraded out/ to NAS data bind`
- `4. start current TLS deploy (CD)`
- `5. health/ready by actual protocol`

## Default control plane

| Role | Default |
|------|---------|
| SSH | `ssh -p 10000 13096920600@192.168.50.4` |
| Data bind host path | `/tmp/zfsv3/sata1/13096920600/data/Docker/lezi/data` |
| Post-TLS LAN endpoint | `https://192.168.50.4:8765` |
| Post-TLS loopback (on NAS only) | `http://127.0.0.1:8766/health` and `/ready` |
| Pre-TLS drift probe | `http://192.168.50.4:8765/health` (plaintext may still answer until cutover) |
| TLS SAN host | `LEZI_TLS_HOST=192.168.50.4` |
| Container / compose project | `lezi-sync` / `lezi` |
| Expected migrated schema | `PRAGMA user_version` = current `DATABASE_SCHEMA_VERSION` (11) |
| Data bind uid | `10001:10001` (container user; must own `lezi.db` after copy-back) |

Override only via env (`NAS_SSH`, `NAS_SSH_PORT`, `LEZI_DATA_HOST_PATH`, `LEZI_TLS_HOST`, …).

## Preconditions (before the maintenance window)

From tickets 01–05 (local only):

1. Copy-out: `bash tools/lezi-sync/deploy/copy-out-nas-data.sh` → timestamped local `backup/` (prefer `LEZI_COPY_OUT_RO=1`).
2. Dry-run: `lezi-sync offline-migrate dry-run --in "$BACKUP_DIR"` (password flag or `LEZI_MIGRATE_NEW_ROOT_PASSWORD`).
3. Migrate: `lezi-sync offline-migrate migrate --in "$BACKUP_DIR" --out "$OUT_DIR"`.
4. Validate: `lezi-sync offline-migrate validate --out "$OUT_DIR"` → `validate ok`.
5. CD package ready on the dev machine (`./build-image.sh` + package, or known-good `dist/`). **Do not** run `push-and-deploy` until the operator confirms the replace window.
6. Record the **migration-time new root password** — it becomes `LEZI_BOOTSTRAP_SECRET` after cutover. The pre-cutover container secret is **void** for owner re-login (`OwnerReauth::NewRootPasswordAtMigration`).

`out/` must be copy-back-ready: current-schema `lezi.db` (full preflight shape, not version alone), regenerated `server.secret` (≥32 bytes), media authority files as migrated, **no** residual `lezi.db-wal` / `-shm` / `-journal`. **`tls/` is not required inside `out/`** (`AbsentOrCreateAtCutover`); `remote-deploy` / `init-tls.sh` creates identity under the data bind.

## Step 0 — Capture pre-cutover image (required for rollback)

Measured fact: live image **id** can differ from the local TLS package even when both are tagged `0.3.0`. Rollback needs a real artifact, not “re-pull the same tag”.

**Before** stop/rm/replace:

```bash
ssh -p 10000 13096920600@192.168.50.4 bash -s <<'EOF'
set -euo pipefail
mkdir -p /tmp/lezi-sync-releases/pre-cutover
docker inspect lezi-sync --format '{{.Image}} {{.Config.Image}}' | tee /tmp/lezi-sync-releases/pre-cutover/image-id.txt
# Save the exact running image (id from inspect), not only the tag name:
IMG=$(docker inspect lezi-sync --format '{{.Image}}')
docker save -o /tmp/lezi-sync-releases/pre-cutover/lezi-sync-pre-cutover.tar "${IMG}"
ls -la /tmp/lezi-sync-releases/pre-cutover/
EOF
```

Keep that tar (and image-id.txt) until post-cutover health + APK smoke are accepted (ticket 07). Prefer also retaining a known-good **pre-TLS package directory** if one exists on the NAS.

## Step 1 — Stop live container

On the NAS (data directory is **not** deleted). Prefer the named container used by CD (`container_name=lezi-sync`); compose project is `lezi`.

```bash
ssh -p 10000 13096920600@192.168.50.4 bash -s <<'EOF'
set -euo pipefail
if docker inspect lezi-sync >/dev/null 2>&1; then
  docker stop lezi-sync
  docker rm lezi-sync
fi
# Fail closed: container must be gone (no || true).
if docker inspect lezi-sync >/dev/null 2>&1; then
  echo "error: lezi-sync still present after stop/rm" >&2
  exit 1
fi
echo "container absent ok"
EOF
```

If you use zdocker compose, stopping/removing `lezi-sync` is enough for the published process; do not leave a second stack writing the same data bind.

## Step 2 — Dual backup confirmation

| Backup | What | Pass criterion |
|--------|------|----------------|
| Local | Ticket-05 copy-out `backup/` of **v3** data | Still present; preferably chmod a-w; has `lezi.db` with `user_version=3` |
| NAS-side | Second snapshot of `LEZI_DATA_HOST_PATH` **while container is stopped** | Separate path under e.g. `/tmp/lezi-sync-releases/pre-cutover-data-…` with `lezi.db` `user_version=3` |

```bash
# Example NAS-side snapshot (container already stopped):
ssh -p 10000 13096920600@192.168.50.4 bash -s <<'EOF'
set -euo pipefail
SRC=/tmp/zfsv3/sata1/13096920600/data/Docker/lezi/data
DST=/tmp/lezi-sync-releases/pre-cutover-data-$(date -u +%Y%m%dT%H%M%SZ)
mkdir -p "$(dirname "$DST")"
cp -a "$SRC" "$DST"
sqlite3 "$DST/lezi.db" 'PRAGMA user_version;' | grep -qx 3
echo "NAS backup=$DST"
EOF
```

Copy-back script requires:

```bash
export LEZI_CONFIRM_CONTAINER_STOPPED=1
export LEZI_CONFIRM_DUAL_BACKUP=1
export LEZI_NAS_BACKUP_PATH=/tmp/lezi-sync-releases/pre-cutover-data-…   # path from above
```

Live copy-back **SSHs** to assert the container is absent and that `LEZI_NAS_BACKUP_PATH/lezi.db` is `user_version=3` — not honor-system alone.

## Step 3 — Copy-back upgraded `out/`

Authoritative script: [`copy-back-nas-data.sh`](./copy-back-nas-data.sh).

```bash
export LEZI_OUT_DIR=/path/to/validated-out   # ticket-05 migrate --out (already validate ok)
export LEZI_CONFIRM_CONTAINER_STOPPED=1
export LEZI_CONFIRM_DUAL_BACKUP=1
export LEZI_NAS_BACKUP_PATH=/tmp/lezi-sync-releases/pre-cutover-data-…
# Optional plan-only (no SSH write):
# export LEZI_COPY_BACK_DRY_RUN=1
# Optional: LEZI_SYNC_BIN=/path/to/lezi-sync  if not on PATH
bash tools/lezi-sync/deploy/copy-back-nas-data.sh
```

Script behavior (fail-closed):

- Requires confirm env flags + (live) `LEZI_NAS_BACKUP_PATH`.
- Requires `sqlite3` (no “warn and continue”).
- Runs `lezi-sync offline-migrate validate --out` (full preflight shape + secret), not only `PRAGMA user_version`.
- Proves `user_version` equals shipped current schema (11); override needs `LEZI_ALLOW_USER_VERSION_OVERRIDE=1`.
- Refuses residual out/ WAL/SHM/journal.
- Live transport is **rsync only** (scp refused — no merge leftovers / stale WAL).
- Remote: container absent probe; NAS backup v3 probe.
- Stages to a sibling dir, then **directory rename swap** into the bind path (previous tree kept as `*.pre-copy-back.$$`).
- **chown 10001:10001** and verifies `lezi.db` owner is 10001 (fail if not).
- Does **not** start the container, does **not** run TLS init, does **not** claim cutover success.

Never copy the **backup’s** old `server.secret` over the migrated secret. Never treat unvalidated `out/` as live data.

## Step 4 — Start current TLS deploy (CD)

Requires **explicit operator confirmation** (family may briefly lose sync; protocol may move HTTP→HTTPS).

### Mandatory bootstrap secret (cutover-specific)

Because Step 1 already **removed** the live container, `remote-deploy` **cannot** inherit a live env.  
Even if a container were still present, **inheriting the pre-cutover secret is wrong**: migrate wrote `families.owner_root_fingerprint` for the **migration-time new root password**. Starting with the old secret rewrites the fingerprint back and voids owner re-login with the migration password (`OwnerReauth::NewRootPasswordAtMigration`).

**Fail-closed for this cutover:**

```bash
# MUST be the same secret used for offline-migrate --new-root-password / LEZI_MIGRATE_NEW_ROOT_PASSWORD
export LEZI_BOOTSTRAP_SECRET='…migration-time new root password (≥16 chars)…'
# Opt-in SSH forward (required): ordinary CD never forwards a local secret over SSH.
export LEZI_FORWARD_BOOTSTRAP_SECRET=1
# NEVER: leave unset hoping remote-deploy inherits the old container
# NEVER: export the pre-cutover container LEZI_BOOTSTRAP_SECRET
# After cutover: unset LEZI_BOOTSTRAP_SECRET and LEZI_FORWARD_BOOTSTRAP_SECRET so ordinary CD inherits from live.
```

From `tools/lezi-sync` on the dev machine (after confirm):

```bash
export LEZI_BOOTSTRAP_SECRET='…migration-time new root password…'
export LEZI_FORWARD_BOOTSTRAP_SECRET=1
./build-image.sh
./deploy/push-and-deploy.sh
# Or, only if dist/ already embeds the intended image:
# LEZI_SKIP_PACKAGE=1 ./deploy/push-and-deploy.sh
# After green health: unset LEZI_BOOTSTRAP_SECRET LEZI_FORWARD_BOOTSTRAP_SECRET
```

`push-and-deploy.sh` **forwards** `LEZI_BOOTSTRAP_SECRET` into remote `remote-deploy.sh` only when **both** `LEZI_BOOTSTRAP_SECRET` and `LEZI_FORWARD_BOOTSTRAP_SECRET=1` are set. Ordinary CD (flag unset) never injects a local secret over SSH — remote-deploy inherits from the live container. **This cutover must set both** (container already removed in Step 1; migration password must not be the pre-cutover inherit).

`remote-deploy.sh` on the NAS will:

- `docker load` the image tar  
- use forwarded `LEZI_BOOTSTRAP_SECRET` (migration password) — **not** pre-cutover inherit for this path  
- `init-tls.sh` under the data bind (`tls/` create-or-reuse)  
- install app-update artifacts  
- stop/rm + compose up project `lezi` (container already absent after Step 1)  
- probe HTTPS health/ready inside the deploy script  

**Protocol cutover risk:** a measured live NAS ran **plaintext HTTP on 8765**. Current tree publishes **HTTPS on 8765**, adds loopback **HTTP on 8766**, and creates persistent TLS under the data bind. Clients must switch scheme and may need TOFU/SPKI.

## Step 5 — Health / ready (probe actual protocol)

**Client-facing success requires LAN HTTPS** (APK TOFU path). Compose publishes only `0.0.0.0:8765`; `LEZI_INTERNAL_PORT=8766` is **container-local only** and is **not** reachable via host `curl http://127.0.0.1:8766`.

Authoritative ticket-07 probe: [`live-cutover-probe.sh`](./live-cutover-probe.sh) (requires LAN HTTPS + version match; optional `docker exec lezi-sync lezi-sync healthcheck`).

```bash
# 1) LAN HTTPS (required) — prefer data-bind cert when host-readable:
curl --cacert /path/to/data-bind/tls/server.crt \
  -fsS https://192.168.50.4:8765/health
curl --cacert /path/to/data-bind/tls/server.crt \
  -fsS https://192.168.50.4:8765/ready
# mode-700 bind: cert often unreadable on SSH user → curl -k to the same URLs,
# or: docker exec lezi-sync cat /data/tls/server.crt > /tmp/lezi-server.crt

# 2) Container-internal readiness (optional corroboration; image has no curl):
ssh -p 10000 13096920600@192.168.50.4 \
  'docker exec lezi-sync lezi-sync healthcheck'

# Do NOT: ssh … 'curl http://127.0.0.1:8766/health'  # host has nothing on 8766
```

If HTTPS fails with TLS “wrong version number” and `http://192.168.50.4:8765/health` still answers → **protocol drift** (live image is not the TLS stack). Report drift; **do not** claim cutover success.

When `/health` reports a version, it should match the deployed image / `LEZI_EXPECTED_VERSION`.

## Rollback — restore pre-maintenance usable v3 service

Goal: family service returns to the **maintenance-before** usable state (measured: v3 schema + pre-cutover image / HTTP surface).

1. Stop/rm `lezi-sync` if running (keep host data path parent).
2. Restore **NAS data bind contents from the copy-out v3 backup** (local ticket-05 `backup/` or `LEZI_NAS_BACKUP_PATH`) — **not** from migrated `out/`. Prefer the script’s `*.pre-copy-back.$$` tree only if it is still the pre-migration data (before a bad swap overwrite).
3. Re-start the **pre-cutover image** from the **Step 0** `docker save` tar (or a retained pre-TLS package), **not** “same tag 0.3.0” alone:

```bash
ssh -p 10000 13096920600@192.168.50.4 bash -s <<'EOF'
set -euo pipefail
docker load -i /tmp/lezi-sync-releases/pre-cutover/lezi-sync-pre-cutover.tar
# Re-run previous package remote-deploy if available, OR docker run with the
# pre-TLS config that served http://192.168.50.4:8765 (plaintext :8765).
# Use the pre-cutover LEZI_BOOTSTRAP_SECRET only when restoring the pre-cutover stack.
EOF
```

4. Probe until usable:

```bash
curl -fsS http://192.168.50.4:8765/health
curl -fsS http://192.168.50.4:8765/ready
# or on-NAS: curl -fsS http://127.0.0.1:8765/health
```

Do not leave a half-written migrated tree as the live data bind after a failed open. Schema is fail-closed: a current-image open against a restored v3 DB (or vice versa) must not be “fixed” by inventing migrations in deploy scripts.

## Family notify checklist

Before telling the household the window is done (still separate from ticket-07 evidence):

| Item | Message |
|------|---------|
| Root password | Rotated at migration; **owner** signs in with the ops-chosen new password (`LEZI_BOOTSTRAP_SECRET` = migration password). Old root password is void. |
| Old clients | **Old APK** and **plaintext HTTP** clients are not supported after TLS + schema cutover. Install the current client build. |
| Endpoint | Use `https://192.168.50.4:8765` (LAN). Complete **TOFU / SPKI** trust on the trusted-HTTPS path. |
| Members | **All members re-login** via current request/approve or login-grant flows. Pre-migration credentials, invites, and device sessions are void (no silent restore). |

See also `migrator::REAUTH_OPS_NOTE` / migrate CLI success output.

## Ticket boundary

| Ticket | Owns |
|--------|------|
| **06 (this)** | Fixed runbook order, default paths, copy-back script gates, rollback notes, family checklist text |
| **07** | Real maintenance-window execution, live health evidence, APK TOFU + owner login + sample records, `evidence/` |

**Never** mark the production family cut over complete from ticket 06 alone.
