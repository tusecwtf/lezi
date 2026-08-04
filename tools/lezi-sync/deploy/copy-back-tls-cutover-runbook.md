# Copy-back + TLS cutover runbook (ticket 06)

**Status:** ops runbook only — **does not** execute the live maintenance window. The pre-TLS rollback
recreation step is intentionally a **cutover blocker** until an exact, audited executable recreation
helper is supplied and tested against the captured live contract; state restore currently stops at
local staging. Do not begin ticket 07 on the strength of prose/manual reconstruction.
**Architecture boundary:** [ADR-0013](../../../docs/adr/0013-offline-migrate-is-maintenance-window-cutover.md)
(`offline-migrate` = authorized maintenance-window offline cutover; **not** startup
migration; does **not** overturn NAS fresh-current / [ADR-0008](../../../docs/adr/0008-support-only-fresh-current-product-contracts.md)).
Ordinary CD does **not** run `offline-migrate`.
**Live execution + APK smoke:** ticket 07 (`.scratch/nas-v3-offline-migrate/issues/07-live-cutover-and-apk-smoke.md`).
**Do not claim the family NAS is cut over until ticket 07 evidence exists.**

Machine-readable step order: `offline_migrate::cutover::cutover_maintenance_steps()`.
CLI pointer: `lezi-sync offline-migrate copy-back-help` (alias `cutover-help`).
Copy-back step script: [`copy-back-nas-data.sh`](./copy-back-nas-data.sh).
Pre-TLS rollback state: [`backup-pre-tls-cutover-state.sh`](./backup-pre-tls-cutover-state.sh) /
[`restore-pre-tls-cutover-state.sh`](./restore-pre-tls-cutover-state.sh).
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
| Post-TLS container readiness | `docker exec lezi-sync lezi-sync healthcheck` (container-internal HTTP 8766; not published on the NAS host) |
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
5. CD package ready on the dev machine (`./build-image.sh`; default push creates a fresh package). Explicit `LEZI_SKIP_PACKAGE=1` reuse is permitted only after current helper/inventory/hash and actual pinned APK signer re-attestation. **Do not** run `push-and-deploy` until the operator confirms the replace window.
6. Record the **migration-time new root password** — it becomes `LEZI_BOOTSTRAP_SECRET` after cutover. The pre-cutover container secret is **void** for owner re-login (`OwnerReauth::NewRootPasswordAtMigration`).

`out/` must be copy-back-ready: current-schema `lezi.db` (full preflight shape, not version alone), regenerated `server.secret` (≥32 bytes), media authority files as migrated, **no** residual `lezi.db-wal` / `-shm` / `-journal`. **`tls/` is not required inside `out/`** (`AbsentOrCreateAtCutover`); only the explicitly confirmed cutover sets `LEZI_ALLOW_TLS_BOOTSTRAP=1` so `remote-deploy` / `init-tls.sh` may create the first identity under the data bind.

## Step 0 — Capture pre-cutover image + encrypted start state (required for rollback)

Measured fact: live image **id** can differ from the local TLS package even when both are tagged `0.3.0`. Rollback needs a real artifact, not “re-pull the same tag”.

The pre-TLS bootstrap secret and exact Docker start contract are just as important as the image tar.
The developer machine must have `age`, a non-empty public recipients file (default
`~/.config/lezi/age-recipients.txt`), and an absolute backup directory outside this repository. Keep
the private age identity offline and outside Git; it is used only by the local restore helper.

**Before** stop/rm/replace:

```bash
ssh -p 10000 13096920600@192.168.50.4 bash -s <<'EOF'
set -euo pipefail
mkdir -p /tmp/lezi-sync-releases/pre-cutover
read -r IMG IMAGE_REF < <(
  docker inspect lezi-sync --format '{{.Image}} {{.Config.Image}}'
)
[[ "${IMG}" =~ ^sha256:[0-9a-f]{64}$ ]]
printf '%s %s\n' "${IMG}" "${IMAGE_REF}" \
  | tee /tmp/lezi-sync-releases/pre-cutover/image-id.txt
# Save the exact id just recorded, not a second tag lookup that could drift.
docker save -o /tmp/lezi-sync-releases/pre-cutover/lezi-sync-pre-cutover.tar "${IMG}"
test "$(docker inspect lezi-sync --format '{{.Image}}')" = "${IMG}"
cd /tmp/lezi-sync-releases/pre-cutover
sha256sum lezi-sync-pre-cutover.tar >lezi-sync-pre-cutover.tar.sha256
ls -la /tmp/lezi-sync-releases/pre-cutover/
EOF
```

On the developer machine, pin the saved image id and stream the still-running container's exact
`docker inspect` state directly through validation into `age`:

```bash
export LEZI_EXPECTED_PRE_TLS_IMAGE_ID="$(
  ssh -p 10000 13096920600@192.168.50.4 \
    "awk 'NR == 1 { print \$1 }' /tmp/lezi-sync-releases/pre-cutover/image-id.txt"
)"
[[ "${LEZI_EXPECTED_PRE_TLS_IMAGE_ID}" =~ ^sha256:[0-9a-f]{64}$ ]]

export LEZI_ALLOW_PRE_TLS_CUTOVER_STATE_BACKUP=1
# Optional override; must remain absolute and outside this repository:
# export LEZI_PRE_TLS_STATE_BACKUP_DIR="$HOME/.config/lezi/pre-tls-cutover-backups"
bash tools/lezi-sync/deploy/backup-pre-tls-cutover-state.sh
# Copy the printed INDEPENDENT BACKUP SHA-256 PIN into a separate trusted
# password-manager/offline record. Do not derive the restore pin from the
# adjacent .sha256 sidecar.
unset LEZI_ALLOW_PRE_TLS_CUTOVER_STATE_BACKUP
```

The backup helper requires the container to be **running**, checks that its current image id equals
the separately recorded Step-0 id, validates one exact `LEZI_BOOTSTRAP_SECRET`, the executable,
entrypoint/command/environment, restart/network/port contract, and the writable `/data` bind, then
encrypts the complete canonical inspect document. Plaintext inspect output and the old secret never
land in a developer-side temporary file. The result is an off-repo `*.age` plus a basename-only
portable `*.age.sha256`; the directory is mode `700` and both files are mode `600`. Because anyone
with the public age recipient can author ciphertext, the helper also prints a ciphertext SHA-256 that
must be recorded independently; the co-located sidecar alone proves integrity, not provenance.

**Fail closed:** do not proceed to Step 1 unless the image tar plus its checksum, `image-id.txt`,
encrypted start-state backup plus its checksum and the independently stored ciphertext pin all exist,
and the helper exited zero. Do not print or attach the decrypted inspect/environment to ticket
evidence. Keep all five files plus the independent pin until post-cutover health + APK smoke
are accepted (ticket 07). Prefer also retaining a known-good **pre-TLS package directory** if one
exists on the NAS.

## Step 1 — Stop live container

On the NAS (data directory is **not** deleted). Prefer the named container used by CD (`container_name=lezi-sync`); compose project is `lezi`.

```bash
ssh -p 10000 13096920600@192.168.50.4 bash -s <<'EOF'
set -euo pipefail
if docker inspect lezi-sync >/dev/null 2>&1; then
  docker stop lezi-sync
  docker rm lezi-sync
fi
# Positive absence proof: docker ps must itself succeed. Do not treat an
# inspect/SSH/daemon/permission failure as evidence that the container is gone.
names="$(docker ps -a --filter 'name=^/lezi-sync$' --format '{{.Names}}')"
if [[ -n "${names}" ]]; then
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
# The persistent config sibling still contains the pre-cutover root password;
# explicitly authorize replacing it only after the old live container is absent.
export LEZI_ALLOW_SECRET_RESEED=1
# The migrated out/ tree has no TLS identity; authorize exactly this first generation.
export LEZI_ALLOW_TLS_BOOTSTRAP=1
# NEVER: leave unset hoping remote-deploy inherits the old container
# NEVER: export the pre-cutover container LEZI_BOOTSTRAP_SECRET
# After cutover: unset all four variables so ordinary CD validates live + persistent and cannot reseed/generate.
```

From `tools/lezi-sync` on the dev machine (after confirm):

```bash
export LEZI_BOOTSTRAP_SECRET='…migration-time new root password…'
export LEZI_FORWARD_BOOTSTRAP_SECRET=1
export LEZI_ALLOW_SECRET_RESEED=1
export LEZI_ALLOW_TLS_BOOTSTRAP=1
./build-image.sh
./deploy/push-and-deploy.sh
# Or, only if dist/ embeds the intended image plus current guarded helpers and valid SHA256SUMS:
# LEZI_SKIP_PACKAGE=1 ./deploy/push-and-deploy.sh
# After green health: unset LEZI_BOOTSTRAP_SECRET LEZI_FORWARD_BOOTSTRAP_SECRET LEZI_ALLOW_SECRET_RESEED LEZI_ALLOW_TLS_BOOTSTRAP
```

`push-and-deploy.sh` sends `LEZI_BOOTSTRAP_SECRET` over SSH **stdin** (not argv) only when both
`LEZI_BOOTSTRAP_SECRET` and `LEZI_FORWARD_BOOTSTRAP_SECRET=1` are set. It forwards the separate
`LEZI_ALLOW_SECRET_RESEED=1` and `LEZI_ALLOW_TLS_BOOTSTRAP=1` authorizations only when explicitly
set. Ordinary CD leaves all four flags unset: it validates live + persistent secret sources and
refuses to generate a missing certificate. This cutover needs all four because the container was
removed, the persistent sibling must move to the migration password, and migrated `out/` intentionally
has no TLS identity. With no live container, the required age credential backup runs immediately
after the new container becomes healthy rather than before replacement.

`remote-deploy.sh` on the NAS will:

- validate/reseed the persistent bootstrap file under explicit maintenance authorization
- `docker load` the image tar
- use stdin-forwarded `LEZI_BOOTSTRAP_SECRET` (migration password) — **not** pre-cutover inherit for this path
- `init-tls.sh` under the data bind (one-time `tls/` creation authorized by `LEZI_ALLOW_TLS_BOOTSTRAP=1`)
- install app-update artifacts
- stop/rm + compose up project `lezi` (container already absent after Step 1)
- probe HTTPS health/ready inside the deploy script

**Protocol cutover risk:** a measured live NAS ran **plaintext HTTP on 8765**. Current tree publishes
LAN **HTTPS on 8765**, isolated LAN invite-install **HTTP on 8767**, and container-only readiness
**HTTP on 8766**, and creates persistent TLS under the data bind. Clients must switch scheme and may
need TOFU/SPKI; neither 8765 nor 8767 may be exposed publicly.

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

1. **Before touching the NAS**, decrypt and validate the Step-0 state into a brand-new local staging
   directory. The independent expected id comes from the saved `image-id.txt` and must identify the
   image tar selected for rollback:

```bash
export LEZI_AGE_IDENTITY_FILE=/absolute/offline/path/lezi-age-identity.txt
export LEZI_EXPECTED_PRE_TLS_IMAGE_ID=sha256:…   # first field from Step-0 image-id.txt
export LEZI_EXPECTED_PRE_TLS_BACKUP_SHA256=…     # independent Step-0 ciphertext pin, not sidecar-derived
bash tools/lezi-sync/deploy/restore-pre-tls-cutover-state.sh \
  /absolute/off-repo/path/lezi-sync-pre-tls-cutover-state-….age \
  /absolute/off-repo/new-empty-rollback-staging
```

   Restore refuses a missing/non-portable checksum, independent ciphertext-pin mismatch,
   unsafe/symlink identity, image-id mismatch, invalid
   start contract, or existing output path. It creates only local mode-`700` staging containing
   mode-`600` `docker-inspect.json`, `bootstrap-secret`, and `MANIFEST.txt`; it never connects to or
   changes the NAS. Treat the whole staging directory as secret and securely remove it after rollback
   acceptance.

2. Stop/rm `lezi-sync` if running (keep host data path parent).

3. Restore **NAS data bind contents from the copy-out v3 backup** (local ticket-05 `backup/` or `LEZI_NAS_BACKUP_PATH`) — **not** from migrated `out/`. Prefer the script’s `*.pre-copy-back.$$` tree only if it is still the pre-migration data (before a bad swap overwrite).

4. Re-start the **pre-cutover image** from the **Step 0** `docker save` tar (or a retained pre-TLS package), **not** “same tag 0.3.0” alone. **This repository does not yet ship the executable exact-recreation helper, so this step blocks starting the cutover:**

```bash
ssh -p 10000 13096920600@192.168.50.4 bash -s <<'EOF'
set -euo pipefail
cd /tmp/lezi-sync-releases/pre-cutover
sha256sum -c lezi-sync-pre-cutover.tar.sha256
docker load -i /tmp/lezi-sync-releases/pre-cutover/lezi-sync-pre-cutover.tar
read -r EXPECTED_IMAGE_ID _ <image-id.txt
test "$(docker image inspect "${EXPECTED_IMAGE_ID}" --format '{{.Id}}')" = "${EXPECTED_IMAGE_ID}"
# Do NOT execute a legacy package's remote-deploy: it may predate current
# credential guards and always bypasses the current pre-replace backup wrapper.
# STOP HERE. Loading and checking the image is not service restoration.
# Do not improvise docker run from memory or execute a legacy remote-deploy.
EOF
```

This exceptional schema/protocol rollback cannot use the ordinary current TLS harness. Until a
reviewed helper can restore the v3 data atomically and recreate/start/probe the container from the
validated contract without exposing the secret in argv/logs, **the maintenance window is not
rollback-ready and must not start**. Treat its
pre-cutover secret and exact start configuration as maintenance-window inputs. The recovered
`docker-inspect.json` is the rollback authority: the audited recreation must preserve its image id,
user, complete environment, entrypoint + command, working directory, writable `/data` source,
published plaintext `8765`, restart policy, and network mode. Compare those fields before starting;
never guess them from memory or recover them from an unreviewed legacy deploy script. The separately
restored `bootstrap-secret` is the exact old value and must be delivered without shell sourcing,
dotenv interpolation, argv exposure, or log output.

5. Probe until usable:

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
