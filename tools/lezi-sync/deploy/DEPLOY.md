# lezi-sync NAS CD (SSH + zdocker)

## Decisions

| Item | Choice |
|---|---|
| Trigger | Dev machine scripts (package → scp → SSH deploy) |
| Compose engine on NAS | **zdocker** bundled `docker-compose` v2 (`/zspace/applications/services/zdocker/bin/docker-compose`) |
| Bootstrap secret | Live container is authoritative during ordinary CD; a matching mode-`600` persistent NAS file is seeded/verified before replace |
| Credential backup | `push-and-deploy.sh` must stream the root secret + TLS pair into a local `age`-encrypted off-repo backup before ordinary replace |
| Concurrency | One NAS-side owner-token lease spans package transfer, credential snapshot, replace, and any deferred post-start snapshot; competing export/CD fails closed |
| Package identity | Measured linux/amd64 image + full image id + pinned Lezi APK signer certificate + closed file inventory/SHA-256; extra/stale remote files are fatal |
| TLS identity | Ordinary CD never rotates it; generate once only on a verified fresh data root, then validate and reuse the exact pair on every replace |
| System `docker compose` | Not required / not installed |

## 0.3.5 deployment note

`0.3.5` narrows atomic-media locking so request-body validation and upload streaming no longer hold
the per-family mutex; a stalled upload therefore cannot indefinitely block pull or commit for the
same family. The sync wire remains compatible with supported Android clients (`min_supported_version_code = 6`).
This release also publishes the isolated LAN invite-install listener on HTTP 8767; HTTPS sync stays
on 8765 and container-only readiness stays on 8766. Ordinary CD must reuse the existing TLS identity,
use the live bootstrap secret as authority, and require its persistent NAS copy to match exactly.

## One-shot (from repo root)

```bash
# One-time prerequisite: install age and create the public recipients file
# described in "Credential backup and restore" below. Keep the identity offline.

# Build/rebuild the locally inspectable linux/amd64 image first
cd tools/lezi-sync && LEZI_SYNC_VERSION=0.3.5 ./build-image.sh

# Package + scp + remote deploy
./deploy/push-and-deploy.sh
```

Environment overrides:

| Variable | Default |
|---|---|
| `NAS_SSH` | `13096920600@192.168.50.4` |
| `NAS_SSH_PORT` | `10000` |
| `NAS_REMOTE_DIR` | Stable validated package path `/tmp/lezi-sync-releases/lezi-sync-<ver>-nas`（该机 `HOME=/home/` 不可写）。Push uploads into a fresh mode-`700` `.incoming-<nonce>` sibling, validates and deploys there, then promotes it to this stable path only after success. The parent must be a canonical non-symlink mode-`700` directory owned by the SSH user. |
| `LEZI_SYNC_VERSION` | from `Cargo.toml` |
| `LEZI_DATA_HOST_PATH` | `/tmp/zfsv3/sata1/13096920600/data/Docker/lezi/data`; must be a normalized portable absolute path because it is rendered into compose/manifest and derives the lock domain. Filesystem root and exact broad/system roots `/etc`, `/usr`, `/var`, `/home`, `/root`, `/tmp`, `/opt`, `/srv` are refused; use a product-specific child. |
| `LEZI_TLS_HOST` | `192.168.50.4`; IPv4 address or DNS name resolving to IPv4, included in the self-signed certificate SAN |
| `LEZI_LAN_APK_DOWNLOAD_ORIGIN` | `http://<LEZI_TLS_HOST>:8767`; invite-install origin, restricted to the same IPv4/DNS host and port 8767; IPv6 is not supported by this NAS publish path |
| `LEZI_SECRET_FILE` | NAS-side persistent root-secret file. Default: sibling of the data bind at `../config/lezi-sync.env` (`/tmp/zfsv3/sata1/13096920600/data/Docker/lezi/config/lezi-sync.env` on the family NAS). Overrides must be normalized absolute paths using only letters, digits, `.`, `_`, `/`, and `-` so the SSH login shell cannot reinterpret them. |
| `LEZI_ALLOW_SECRET_RECOVERY=1` | Explicit incident authorization to use the persistent file when the live container is absent. Ordinary CD leaves it unset. |
| `LEZI_ALLOW_SECRET_RESEED=1` | Explicit maintenance authorization to replace a conflicting persistent value; requires live container absent plus forwarded explicit secret. Never use for ordinary CD. |
| `LEZI_ALLOW_TLS_BOOTSTRAP=1` | One-time opt-in to create TLS files only on an operator-verified fresh data root. Requires an explicitly forwarded secret, and remote deploy aborts if a live container exists. Ordinary CD, rollback, and certificate tests on the family NAS leave it unset. |
| `LEZI_SKIP_PACKAGE=1` | Explicitly reuse the local package instead of default fresh packaging. The corresponding local Docker image must still match id/OS/architecture; requested data/TLS/origin inputs, current helpers, closed inventory/SHA, and the APK's signer, application id, version, metadata hash, and local-data-contract ledger are all re-attested. |
| `LEZI_NAS_PACKAGE_DIR` | Local package output/input. `package-nas.sh` canonicalizes it, rejects symlinks/broad targets, and requires basename `lezi-sync-<ver>-nas` before its replace-in-place build. |
| `LEZI_PACKAGE_BUILD_IMAGE=1` | `package-nas.sh` builds image if missing |
| `LEZI_BOOTSTRAP_SECRET` | only with `LEZI_FORWARD_BOOTSTRAP_SECRET=1` (cutover / no live container to inherit). Ordinary CD leaves this unset so remote-deploy inherits from the live container. |
| `LEZI_FORWARD_BOOTSTRAP_SECRET=1` | opt-in: SSH-forward local `LEZI_BOOTSTRAP_SECRET` into remote-deploy. Required for a verified fresh deployment and for offline-migrate cutover after stop/rm. Do **not** set for ordinary CD — a leftover local secret would otherwise rotate `owner_root_fingerprint` and revoke owner devices. After a fresh deployment unset it plus the secret/TLS-bootstrap flag; after cutover unset all four maintenance variables documented in the cutover runbook. |
| `LEZI_AGE_RECIPIENTS_FILE` | Public age recipients file; default `~/.config/lezi/age-recipients.txt`. Required by `push-and-deploy.sh`; it is not a decrypt key. |
| `LEZI_CREDENTIAL_BACKUP_DIR` | Encrypted backup directory; default `~/.config/lezi/backups/`, must be absolute, mode `700`, and outside the repository. |
| `LEZI_AGE_IDENTITY_FILE` | Absolute offline/private age identity used only by restore staging; must be a non-symlink mode-`600` file outside this repository. |
| `LEZI_EXPECTED_CERTIFICATE_SHA256` / `LEZI_EXPECTED_SPKI_SHA256` | Independent production identity pins required by restore. Use the separately verified values recorded under “TLS identity”, never values copied only from the candidate backup. |
| `LEZI_RELEASE_APK` | signed release APK path (default repo `app/build/outputs/apk/release/app-release.apk`) |
| `LEZI_APP_UPDATE_JSON` | app-update metadata JSON (default `deploy/app-update.json`) |
| `LEZI_APK_SIGNER` | Optional absolute/local `apksigner` executable override. Otherwise the Android SDK build-tools path is discovered. |

### One-time NAS filesystem preflight

The family NAS data bind lives below a root-owned ZFS directory. Before the first hardened CD, an
administrator must create the secret/lease sibling for the SSH account; neither the unprivileged SSH
user nor a Docker helper may weaken the ZFS parent to make this pass. Do this once in an interactive
NAS shell (never paste the sudo password into logs or chat):

```bash
config=/tmp/zfsv3/sata1/13096920600/data/Docker/lezi/config
sudo mkdir -p "${config}"
sudo chown "$(id -u):$(id -g)" "${config}"
sudo chmod 700 "${config}"
stat -c '%a %u:%g %n' "${config}"

mkdir -p /tmp/lezi-sync-releases
chmod 700 /tmp/lezi-sync-releases
stat -c '%a %u:%g %n' /tmp/lezi-sync-releases
```

Expected modes are `700`; both paths must be real directories owned by the SSH user. Do not
recursively chown/chmod the data bind and never change `/data/tls` permissions for CD. A legacy
stable package that is mode `775`, contains `.env`, or lacks the current closed inventory is not
silently overwritten: first prove no deploy is active, restrict it to mode `700`, and move that exact
directory to a separately named legacy archive. Do not copy its `.env` into the new package.

## Stages

1. **package-nas.sh** — require a locally inspectable image and measure `.Os=linux` + `.Architecture=amd64` → verify the APK signature against tracked public pin `config/release-apk-signer-sha256.txt` → record Docker's complete `config.digest` (the identity produced by `docker load` and reported by the running container, not a local OCI manifest-list digest) → `docker save` the exact tar → render `docker-compose.yml` → add fail-closed app-update artifacts/current helpers → write `MANIFEST.json` + exact-inventory `SHA256SUMS` → `dist/lezi-sync-<ver>-nas/`.
2. **push-and-deploy.sh** — fresh-package by default (reuse only with explicit `LEZI_SKIP_PACKAGE=1`) → repeat local helper/inventory/checksum and APK-signer attestation → acquire the stable data-bind-derived NAS lease → create a new mode-`700` random-suffixed staging directory → scp and validate there before execution → stream/validate/encrypt the live credential snapshot → replace while retaining the lease. On success promote staging to the stable `NAS_REMOTE_DIR`; a prior exact package is removed only after the promotion validates. Failed staging is left for deliberate inspection/cleanup.
3. **remote-deploy.sh** (on NAS) — require the outer push lease (direct production execution is forbidden) → revalidate the exact package → resolve live/persistent secret sources → seed or byte-compare the persistent file → revalidate immediately before `docker load` → require loaded id and measured OS/architecture to match → validate persistent TLS and pin exact certificate SHA-256 + SPKI → install APK metadata → require stop and rm to succeed → start via Compose/docker → require the running container `.Image` to equal the manifest id → HTTPS `/health` + `/ready` with exact version → recheck both TLS digests.
4. A verified fresh/recovery flow with no live container cannot take a pre-replace live backup. It is allowed only with the existing explicit secret/recovery authorization and must complete an encrypted backup immediately after the new container becomes healthy.

### Package and deployment lease fail-closed behavior

`LEZI_SKIP_PACKAGE=1` never means “trust whatever is already in `dist/` or on the NAS”. Without it,
push always creates a fresh package from the current local image. With it, the package
must contain every and only the files declared by the current validator. `MANIFEST.json` version,
platform, measured image OS/architecture, image name, complete image config digest, APK signer pin, and tar filename must agree; every artifact except
`SHA256SUMS` itself must have exactly one checksum entry. A stale tar, legacy `.env`, symlink, extra
file, missing helper, checksum mismatch, signer mismatch, loaded/running-image mismatch, or `/health`
version mismatch aborts. Its manifest image config digest/OS/architecture must equal the locally inspectable
`lezi-sync:<ver>` image, and rendered data/TLS/invite inputs must equal the current command's expected
defaults or explicit overrides. scp never writes into a reusable directory: each attempt gets a new
`.incoming-<nonce>` directory. A failed attempt is retained only for inspection and must be removed
deliberately after confirming no deploy uses it; successful attempts atomically promote to the stable path.

The push wrapper creates
`<data-bind-parent>/config/.lezi-sync-credential-deploy.lock/owner-token` with modes `700/600` before scp
and holds it through backup, replacement, health, and a deferred first/recovery backup. The token is
a non-credential ownership nonce; operators must not set `LEZI_DEPLOY_LOCK_TOKEN` themselves.
The lock is derived from the package data bind, never from overridable `LEZI_SECRET_FILE`, so all
deploys for one NAS instance share it. Exporter calls share the same lock. Production
`remote-deploy.sh` refuses to run without the outer token because doing so would bypass the required
developer-side encrypted backup. There is no standalone production bypass; recovery must still enter
through the guarded `push-and-deploy.sh` workflow with its explicit recovery authorization.

`SIGKILL`, host loss, or an interrupted SSH path can intentionally leave a stale lease. Never delete
it merely because a retry says “already in progress”. First prove no `push-and-deploy`, credential
export, or `remote-deploy` process is active and no container replacement is underway; then inspect
the exact mode-`700` lock directory and remove only its mode-`600` `owner-token` followed by `rmdir`
of that one lock directory. Do not recursively remove the secret config directory.

## Self-hosted app update (release APK)

Packaging is **fail-closed**: missing release APK/metadata, invalid signature, a signing certificate
different from tracked public pin `config/release-apk-signer-sha256.txt`, or SHA-256 mismatch aborts.
The private keystore stays local and ignored; only its public certificate digest is tracked. No
“empty update channel” package is produced. Push repeats the full APK gate—signer, manifest
application/version, local-data-contract metadata, metadata JSON, and file hash—even for
`LEZI_SKIP_PACKAGE=1`, because 8767 anonymously serves this APK on the trusted LAN.

| Input | Default | Override |
|---|---|---|
| Release APK | `app/build/outputs/apk/release/app-release.apk` | `LEZI_RELEASE_APK` |
| Metadata JSON | `tools/lezi-sync/deploy/app-update.json` | `LEZI_APP_UPDATE_JSON` |
| APK signature verifier | Android SDK `build-tools/*/apksigner` | `LEZI_APK_SIGNER` |
| Expected signer | `config/release-apk-signer-sha256.txt` | no runtime override; update only during an authorized signing-key rotation |

Metadata contract (`app-update.json`, snake_case):

```json
{
  "package_name": "com.lezi.babylog",
  "version_code": 12,
  "version_name": "0.3.5",
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
- Joined clients use authenticated `GET /v1/app-update` (JSON) and
  `GET /v1/app-update/apk` (`application/vnd.android.package-archive`; integrity re-checked
  server-side). Separately, the LAN-only invite-install listener anonymously serves the same
  verified APK at `http://<LEZI_TLS_HOST>:8767/download/lezi.apk`; it exposes no family API.
- Product channel is **self-hosted sideload** (Android `PackageInstaller`), **not** Google Play
  In-App Updates. See [`docs/prd/tech.md`](../../../docs/prd/tech.md) §4.2.

Invite-install smoke after deploy is separate from readiness: `curl -fsS
http://<LEZI_TLS_HOST>:8767/join` must return the branded page, and
`curl -fsS -o /tmp/lezi-invite.apk http://<LEZI_TLS_HOST>:8767/download/lezi.apk` must return the
same SHA-256 as metadata. Never use 8767 as a health probe, and never publish it to the internet.

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
LEZI_RELEASE_APK=/nonexistent/app-release.apk ./deploy/package-nas.sh
# expect non-zero exit

# success path (the exact local Docker image must be inspectable; tar-only reuse is refused)
LEZI_RELEASE_APK=../../app/build/outputs/apk/release/app-release.apk \
  LEZI_APP_UPDATE_JSON=deploy/app-update.json \
  ./deploy/package-nas.sh
# expect dist/lezi-sync-<ver>-nas/app-update/{app-release.apk,app-update.json}
```

## Secret handling

- Plaintext `.env`, private keys, decrypted bundles, real family data, and age identities are never
  committed. A private GitHub repository is not a secret store.
- Root `.gitignore` blocks common deploy-secret artifacts. The trackable `.env.example` must keep
  its bootstrap value empty. Ignore rules are defense in depth; an accidentally committed value still
  requires immediate rotation and history incident handling.
- Canonical NAS file: `${LEZI_SECRET_FILE}` or, by default, the data bind's sibling
  `../config/lezi-sync.env`. For the family NAS that is
  `/tmp/zfsv3/sata1/13096920600/data/Docker/lezi/config/lezi-sync.env`. Despite the `/tmp/zfsv3`
  prefix this is the NAS persistent ZFS-backed tree; `/tmp/lezi-sync-releases/` is disposable.
- The dedicated `config/` directory must be a non-symlink mode-`700` directory. The secret file must
  be a non-symlink regular mode-`600` file containing exactly one line:
  `LEZI_BOOTSTRAP_SECRET=<value>`. The parser never `source`s or evaluates it.
- `remote-deploy.sh` does not create a secret-bearing release-directory `.env`. After validation it
  passes the value only as the Compose/docker client process environment, using a bare container-env
  pass-through so `$`, quotes, spaces, `#`, backslashes, and `=` are not reinterpreted. A leftover
  legacy release `.env` aborts before image load/replacement and must be inspected and removed
  deliberately; it is never a source of truth.
- App create/reclaim must keep using the same value. **Never print** the value from `docker inspect`,
  files, SSH commands, logs, or report paste; explicit maintenance forwarding uses SSH stdin rather
  than process argv.

| State before deploy | Result |
|---|---|
| Live container valid, persistent file absent | Use live value and atomically seed the file before `docker load` or stop/rm |
| Live container + byte-identical file | Use live value |
| Live/file mismatch, malformed file, unsafe mode, symlink, or missing/short live value | Abort before container replacement; never auto-fix or print either value |
| Existing stopped/exited container + matching valid file | Export reports “no live snapshot”; only explicit `LEZI_ALLOW_SECRET_RECOVERY=1` may continue, and remote deploy rechecks the stopped container's configured secret byte-for-byte before using the file |
| Existing stopped/exited container + missing/mismatched file | Abort; recovery authorization never overrides disagreement |
| No live container + valid file | Abort unless the operator explicitly sets `LEZI_ALLOW_SECRET_RECOVERY=1`; then use the file without rewriting it |
| No live container + explicit secret + no file | Verified fresh/cutover path may seed it; explicit secret is sent over SSH stdin |
| No live container + explicit secret conflicts with file | Abort unless the separately authorized maintenance flow also sets `LEZI_ALLOW_SECRET_RESEED=1`; ordinary CD never sets it |

## Credential backup and restore

The credential backup contains the bootstrap secret plus the exact
`/data/tls/server.crt` + `/data/tls/server.key` pair. It is separate from, and does not replace,
database/media backups or NAS snapshots. Certificate and private key are always backed up and
restored as one atomic identity; never restore only one half.

### One-time developer-machine setup

Install `age` using the developer machine's trusted package source,
then create an identity on offline media or in a password manager. Only its public recipient is kept
on the development machine:

```bash
install -d -m 700 "$HOME/.config/lezi"
age-keygen -o /path/on/offline-media/lezi-age-identity.txt
age-keygen -y /path/on/offline-media/lezi-age-identity.txt \
  > "$HOME/.config/lezi/age-recipients.txt"
chmod 644 "$HOME/.config/lezi/age-recipients.txt"
```

Never put the identity, recipients file, `.age` backups, or decrypted staging under the repository.
Back up the age identity separately; encrypted files are unrecoverable without it. A recipients file
may list two independently stored recovery identities so loss of one medium does not destroy the only
recovery path; neither private identity belongs on the routinely deployed NAS or in Git.

### CD backup behavior

`push-and-deploy.sh` is fail-closed:

1. One owner-token lease already spans scp through replacement. Under that lease and before ordinary
   stop/rm, `export-nas-credentials.sh` validates exactly one live secret entry, live/file equality,
   and the live TLS pair as one non-interleavable snapshot.
2. `validate-credential-bundle.sh` buffers the six-line stream only in process memory and validates
   schema/base64, root-secret minimum contract, certificate expiry, keypair match, exact certificate
   SHA-256, and SPKI before forwarding any bytes to `age`. SSH banners, truncated/legacy exporters,
   and digest mismatches therefore cannot produce a “backup ready” result.
3. The validated plaintext streams directly into local `age`; it is never written plaintext on the
   developer machine. Output defaults to `~/.config/lezi/backups/`, mode `700`, with each `.age`
   and portable basename-only `.sha256` sidecar mode `600`.
4. Missing `age`, missing recipients, exporter/validator failure, source mismatch, or encryption
   failure aborts ordinary CD before replacement.
5. A verified fresh/recovery flow has no live container to export. Only an already explicit
   `LEZI_FORWARD_BOOTSTRAP_SECRET=1` or `LEZI_ALLOW_SECRET_RECOVERY=1` flow may continue, and it must
   create the encrypted backup immediately after the new container is healthy.

The wrapper can also be run manually after a current package has been copied to `NAS_REMOTE_DIR`:

```bash
cd tools/lezi-sync
./deploy/backup-nas-credentials.sh
cd "$HOME/.config/lezi/backups"
sha256sum -c "<backup>.age.sha256"
```

### Restore validation (does not modify NAS)

`age` recipient encryption gives confidentiality and ciphertext integrity, but the public recipient
does not authenticate who authored the backup. Therefore decrypt only during an authorized incident
and require both independently recorded production pins below. The identity must be an absolute,
non-symlink, mode-`600` file outside the repository. The command refuses an existing target and
validates the env format, certificate expiry, keypair, bundle digests, independent pins, and optional
SAN host:

```bash
cd tools/lezi-sync
LEZI_AGE_IDENTITY_FILE=/path/on/offline-media/lezi-age-identity.txt \
LEZI_EXPECTED_TLS_HOST=192.168.50.4 \
LEZI_EXPECTED_CERTIFICATE_SHA256=75023c71d8ca918a42fe4f058aab8faf85db3f02b9a69bfb6522951ce362da9e \
LEZI_EXPECTED_SPKI_SHA256=bd07d8645ed3b7adead162eca454373aee4007b0a35aa7c62caf7d8ac0cb3215 \
  ./deploy/restore-nas-credentials.sh \
    "$HOME/.config/lezi/backups/<backup>.age" \
    /var/tmp/lezi-credential-recovery-YYYYMMDD
```

The staging result contains plaintext `lezi-sync.env`, the TLS pair, and a public fingerprint
manifest under a mode-`700` directory. The script never contacts or changes the NAS. Installing that
staging result is a separate, explicitly authorized maintenance operation: stop the container, prove
the target secret/TLS files are absent (not merely unreadable), install the entire pair together,
re-run validation, and require the restored SHA-256/SPKI to match both the independent production
pins above and `MANIFEST.txt`. Never overwrite an
established identity to make CD pass. Remove the plaintext staging directory after the incident using
the operator's approved secure cleanup procedure.

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
| 当前开窗门 | pre-TLS rollback 的 exact container recreation 尚无审计过的可执行 helper；runbook 将其列为 blocker，未补齐前不得开始 live cutover |
| Cutover secret | 仅维护窗：迁移期新根密码 → `LEZI_BOOTSTRAP_SECRET`；可用 `LEZI_FORWARD_BOOTSTRAP_SECRET=1`；**普通 CD 勿设** |
| 发布二进制 | 可含该子命令 ≠ 滚动 schema 兼容产品承诺 |

产品 README 摘要：[`../README.md`](../README.md) § 离线 v3→current 切割。

## TLS identity

Last recorded public production identity after the accepted `0.3.5` CD (update only from a verified
post-CD read; this table is evidence/pinning, not a substitute for the encrypted key-pair backup):

| Field | Recorded value |
|---|---|
| TLS SAN host | `192.168.50.4` |
| Exact `server.crt` SHA-256 | `75023c71d8ca918a42fe4f058aab8faf85db3f02b9a69bfb6522951ce362da9e` |
| SPKI SHA-256 | `bd07d8645ed3b7adead162eca454373aee4007b0a35aa7c62caf7d8ac0cb3215` |
| Recorded release | `lezi-sync:0.3.5` |
| Recorded date | `2026-08-03` |

- Ordinary CD, rollback, container replacement, and restart are **not certificate-rotation paths**.
  They must preserve `/data/tls/server.crt` byte-for-byte and retain the same matching mode-`600`
  `/data/tls/server.key` identity.
- `init-tls.sh` may create that pair only when the operator explicitly sets
  `LEZI_ALLOW_TLS_BOOTSTRAP=1` for the first deployment to a verified fresh data root where both
  files are absent. Ordinary CD leaves the flag unset. If both files are absent from an established/configured family
  data root, treat that as an incident and stop; do not use CD to create a replacement identity.
- Existing-file checks and validation must run from the helper-container uid `10001` view. The NAS
  SSH user not being able to traverse a mode-`700` bind does **not** mean the identity is absent.
- A missing half, invalid/expired certificate, mismatched key, helper-container read failure, or
  pre/post-CD exact certificate SHA-256 or SPKI mismatch fails closed before success is reported.
  The certificate digest covers the exact `server.crt` bytes, so same-key reissuance is also rejected.
  Never delete, rename, chmod,
  regenerate, or copy over TLS files to make deployment pass.
- The private key is absent from the image, Git, package directory, logs, `MANIFEST.json`, and `SHA256SUMS`.
- Its only developer-machine copy is inside the `age` ciphertext described above; the independent
  production pins provide restore provenance for the matching private key. The
  exporter streams it directly from the live container and never writes a plaintext local backup.
- Deployment prints only the public SPKI SHA-256 fingerprint so it can be compared with the Android TOFU screen.

### Certificate tests

- Do **not** run certificate creation, replacement, expiry, mismatch, TOFU-change, or reconnect-
  certificate tests against a real family NAS, its live `lezi-sync` container, or its data bind.
- Deploy a developer-owned isolated service instead. Use a `mktemp` data root, a non-production port,
  and credentials/family data created only for that test; destroy only that isolated fixture afterward.
- On a family NAS, certificate work is read-only: inspect certificate metadata/SAN, compute the public
  certificate and SPKI fingerprints, and compare the exact certificate SHA-256 and SPKI before and
  after ordinary CD. The explicitly configured encrypted credential export is also read-only, but is
  an operational backup—not a certificate replacement/test path.
- Any intentional certificate rotation is a separately authorized maintenance operation with its own
  backup, rollback, client re-trust, and re-login plan. It is never folded into image CD or a test run.

## Rollback

```bash
# Dev machine: first make the deliberately selected old image available locally.
docker load -i /path/to/retained/lezi-sync-<old>-linux-amd64.tar

# Repackage that old image with the CURRENT guarded exporter/remote-deploy.
cd tools/lezi-sync
LEZI_SYNC_VERSION=<old> LEZI_SYNC_IMAGE=lezi-sync:<old> \
  ./deploy/package-nas.sh

# This again validates package hashes and creates a fresh pre-replace age backup.
LEZI_SYNC_VERSION=<old> LEZI_NAS_PACKAGE_DIR=../../dist/lezi-sync-<old>-nas \
LEZI_SKIP_PACKAGE=1 ./deploy/push-and-deploy.sh
```

Never execute a legacy package's bundled `remote-deploy.sh` directly: it may predate the credential
backup/live-file guards, and direct execution bypasses `push-and-deploy.sh` in every version. A guarded
image rollback reuses the same live/persistent secret and TLS identity; it is not credential restore.
Confirm the old server is schema/wire compatible with the current data before the window. The data
directory remains a bind mount; stop/rm does **not** delete family data.

## Web UI (optional)

After SSH deploy, the container is managed by compose project name `lezi` with `container_name: lezi-sync`. The zdocker **Compose projects** list is a separate SQLite registry; SSH compose up may not create a UI project row. Containers page should still show `lezi-sync`. To also register in the UI, import the packaged `docker-compose.yml` once (same image/data); avoid running two stacks on port 8765.

## Preconditions on NAS

- SSH key login, user in `docker` group
- Data dir owned by `10001:10001`
- Port 8765 free after old container stop
- `curl` available for health checks
