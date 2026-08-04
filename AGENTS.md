# Repository Guidelines

## Project Structure & Module Organization

Lezi is a Kotlin/Jetpack Compose Android app with a Rust home-LAN sync service. `app/` owns application wiring; `core/` contains shared models, storage, and UI infrastructure; `domain/` exposes use cases; `designsystem/` holds reusable Compose tokens and components; and product screens live under `feature/<name>/`. Android sync code is in `sync/`; the Axum/SQLite NAS service is in `tools/lezi-sync/`. Keep unit tests beside each module in `src/test/` and device/Compose tests in `src/androidTest/`.

**Package locality (current):** after the 2026-08 audit remediation, put new code in the existing capability/call-flow subpackages — do not keep flattening already-partitioned module roots. Keep deep façades at the root (`domain` `CareLog`, `sync` `SyncPort`/`RealSyncPort`, lezi-sync `Store`). Do not add product-less source-layout/line-count StructureTests; behavior tests are the refactor contract. Landed map: `docs/prd/tech.md` §2.1.1.

Treat `docs/prd/` as product authority, `CONTEXT.md` as terminology authority, and `docs/adr/` as architecture history. Local specs and tickets live in `.scratch/`; GitHub Issues are not the tracker.

## Build, Test, and Development Commands

Use JDK 21 and configure the Android SDK in gitignored `local.properties`.

- `./gradlew :app:assembleDebug` builds the debug APK.
- `./gradlew :app:installDebug` installs it on a connected device/emulator.
- `./gradlew test` runs all JVM unit tests.
- `./gradlew lintDebug` runs Android lint.
- `./gradlew connectedDebugAndroidTest` runs instrumentation tests with a device available.
- `cd tools/lezi-sync && cargo fmt --all -- --check` checks Rust formatting.
- In that directory, `cargo test --locked` and `cargo clippy --all-targets --all-features -- -D warnings` are the server gates.

## lezi-sync server release (NAS CD)

Authoritative runbook: `tools/lezi-sync/deploy/DEPLOY.md`. Product overview: root `README.md` §「服务端发版」.

**Do not** build the server on the NAS (`cargo` / `docker build`). **Do not** install system `docker compose` on the NAS for this product path. **Do not** commit bootstrap secrets, real family data, image tarballs under `dist/`, or NAS `.env` files.

**TLS safety invariant:** ordinary NAS CD, rollback, container replacement, and restart must never
replace an existing `/data/tls/server.crt` + `/data/tls/server.key` identity. Validate and reuse the
complete pair from the container uid `10001` view. A missing half, invalid/expired certificate,
mismatched key, helper-container read failure, or pre/post-deploy SPKI mismatch must abort; never
delete, rename, chmod, regenerate, or copy over TLS files to make CD pass. Generating a certificate is
allowed only for the first deployment to a separately verified fresh data root where both files are
absent.

**Certificate-test isolation:** never run certificate creation, replacement, expiry, mismatch,
TOFU-change, or reconnect-certificate tests against the family NAS, its live container, or its data
bind. Run them on a developer-owned isolated `lezi-sync` instance with a `mktemp` data root and a
non-production port. The family NAS permits read-only certificate/SAN/fingerprint checks and exact
pre/post-CD certificate SHA-256 plus SPKI comparison only.

### Pipeline (locked decisions)

1. **Dev machine**: Rust gates → `./build-image.sh` → `linux/amd64` image `lezi-sync:<Cargo.toml version>`.
2. **Package**: `tools/lezi-sync/deploy/package-nas.sh` requires the locally inspectable linux/amd64 `lezi-sync:<ver>` image and writes gitignored `dist/lezi-sync-<ver>-nas/` with its complete image id, one manifest-selected tar, zdocker-friendly compose, current guarded helpers, and a closed inventory/checksum set. Never reuse an unattested old tar merely because it exists in `dist/`.
3. **Ship + protect + replace**: `tools/lezi-sync/deploy/push-and-deploy.sh` validates the exact package, pinned APK signer, and measured linux/amd64 image; acquires one data-bind-derived NAS owner-token lease; uploads to a fresh mode-`700` staging directory; streams the live root secret + TLS pair into developer-machine off-repo `age`; then replaces and promotes the validated package to the stable remote path. Ordinary CD aborts before stop/rm when backup fails; signer/image/platform/running-container drift, stale/extra artifacts, health-version drift, or a competing lease are fatal. Production `remote-deploy.sh` requires this outer token and must not be run directly.
4. **NAS engine**: prefer Zspace **zdocker** binary  
   `/zspace/applications/services/zdocker/bin/docker-compose` (Compose v2.33.x). Fallback is plain `docker run` if that binary is missing.
5. **Secret**: during ordinary CD the live `LEZI_BOOTSTRAP_SECRET` is authoritative and must byte-match the persistent NAS sibling file `config/lezi-sync.env` (directory `700`, file `600`); seed a missing file atomically before replace. Compose and docker-run receive the exact validated value only through process-environment pass-through—never dotenv/YAML interpolation or a secret-bearing argv. No live container means fail unless explicit persistent-file recovery or explicit fresh/cutover secret flow is authorized; never silently overwrite or rotate.
6. **Data**: bind-mount host path (default  
   `/tmp/zfsv3/sata1/13096920600/data/Docker/lezi/data`) → `/data`. Stop/rm container must **not** delete that directory. Container user `10001:10001`; host dir must be writable by that uid.
7. **Publish**: host `0.0.0.0:8765` for LAN HTTPS sync and `0.0.0.0:8767` for isolated LAN HTTP invite-install; never map either port to the public internet without a deliberate firewall exception. Container-internal HTTP 8766 is not published.
8. **Remote package dir**: default  
   `/tmp/lezi-sync-releases/lezi-sync-<ver>-nas` because Zspace SSH `HOME` is often `/home/` (not writable). Prefer a persistent path via `NAS_REMOTE_DIR` when available.

### Commands agents should use

```bash
cd tools/lezi-sync
cargo fmt --all -- --check
cargo test --locked
cargo clippy --all-targets --all-features -- -D warnings
# after user confirms CD (see workflow below):
./build-image.sh
./deploy/push-and-deploy.sh
```

**Probe the live protocol before trusting any single health URL** (measured 2026-07-31: family NAS was still plaintext HTTP on 8765; current tree/CD targets HTTPS 8765 + **container-internal** HTTP readiness on 8766 — compose does **not** publish host:8766).

```bash
# Pre-TLS / still-HTTP live (worked on measured NAS):
curl -fsS http://192.168.50.4:8765/health
curl -fsS http://192.168.50.4:8765/ready
ssh -p 10000 13096920600@192.168.50.4 'curl -fsS http://127.0.0.1:8765/health'

# Post-TLS client-facing (required for APK TOFU):
# curl --cacert <data-bind>/tls/server.crt -fsS https://192.168.50.4:8765/health
# mode-700 bind: docker exec lezi-sync cat /data/tls/server.crt > /tmp/lezi.crt
# or curl -k -fsS https://192.168.50.4:8765/health
# Container-internal readiness only (optional; image has no curl):
# ssh -p 10000 13096920600@192.168.50.4 'docker exec lezi-sync lezi-sync healthcheck'
# Do NOT: ssh … 'curl http://127.0.0.1:8766/health' — nothing listens on host:8766.
# HTTPS against a plaintext 8765 yields TLS "wrong version number" — treat as drift, not success.
# Ticket 07 probe: tools/lezi-sync/deploy/live-cutover-probe.sh
```

| Variable | Role |
|---|---|
| `NAS_SSH` / `NAS_SSH_PORT` | SSH target (default `13096920600@192.168.50.4`, port `10000`) |
| `NAS_REMOTE_DIR` | Unpack + deploy directory on NAS |
| `LEZI_DATA_HOST_PATH` | Host bind for `/data` when packaging |
| `LEZI_SECRET_FILE` | NAS-side persistent bootstrap file; defaults to data bind sibling `../config/lezi-sync.env`; overrides use a normalized portable absolute-path character set |
| `LEZI_ALLOW_SECRET_RECOVERY=1` | Explicit incident authorization to use the persistent file when no live container exists; ordinary CD leaves unset |
| `LEZI_ALLOW_SECRET_RESEED=1` | Explicit maintenance-only replacement of a conflicting file; live container must be absent and a new secret explicitly forwarded |
| `LEZI_FORWARD_BOOTSTRAP_SECRET=1` | Explicit fresh/cutover-only SSH-stdin forwarding of `LEZI_BOOTSTRAP_SECRET`; ordinary CD leaves unset |
| `LEZI_TLS_HOST` | Certificate SAN host (default `192.168.50.4`) |
| `LEZI_ALLOW_TLS_BOOTSTRAP=1` | One-time TLS generation on an operator-verified fresh data root only; ordinary CD/rollback/tests must leave unset |
| `LEZI_SKIP_PACKAGE=1` | Explicitly reuse an existing local package; default push repackages. Reuse still requires matching local image config digest/OS/architecture and re-attests data/TLS/origin, helpers/inventory/hashes, plus APK signer, application/version, metadata, and local-data contract. |
| `LEZI_PACKAGE_BUILD_IMAGE=1` | `package-nas.sh` builds image if missing |
| `LEZI_PACKAGE_APP_UPDATE_CHECK_ONLY=1` | Validate release APK + `app-update.json` only (no docker save); CI smoke: `./deploy/test-package-nas-app-update.sh` |
| `LEZI_AGE_RECIPIENTS_FILE` | Public age recipients file; default `~/.config/lezi/age-recipients.txt`; required by production push/CD |
| `LEZI_CREDENTIAL_BACKUP_DIR` | Absolute off-repo encrypted backup directory; default `~/.config/lezi/backups/` |
| `LEZI_AGE_IDENTITY_FILE` | Offline/private age identity for restore staging only; never needed by CD or stored in the repo |
| `LEZI_EXPECTED_CERTIFICATE_SHA256` / `LEZI_EXPECTED_SPKI_SHA256` | Independent production pins required for restore staging; never trust only digests carried inside the same ciphertext |

### Agent workflow: Rust gates → CD → 联调

**Measure before you document or declare success.** When work touches `tools/lezi-sync/` **or** Android/sync wire that must be proven against a live server, **do not stop at unit tests**. After Rust gates pass, continue to NAS CD and a minimal frontend–backend smoke. Skip CD/联调 for pure docs or comments with no runtime effect.

**1. Rust gates (dev machine)** — `cargo fmt --all -- --check`, `cargo test --locked`, and `cargo clippy --all-targets --all-features -- -D warnings` under `tools/lezi-sync/`.

**2. Propose CD — wait for confirmation before deploy.**
Explain that the next step will build a `linux/amd64` image, package (or reuse `dist/`), scp to the NAS, create the required local off-repo `age`-encrypted credential backup, and **stop/rm + replace** container `lezi-sync` (compose project `lezi`). The data bind is kept; a live family may briefly lose sync during replace. Confirm `age` plus the public recipients file are configured before requesting the window; never request or copy the decrypt identity into the repository.

Also state the **protocol cutover risk**: a measured family NAS ran **plaintext HTTP on 8765** (no `/data/tls`, no host `8766`). Only a separately confirmed first TLS cutover on a verified fresh/no-TLS data root may create the one persistent identity. Every later `remote-deploy.sh` run must reuse that exact identity while keeping readiness on **container-internal** HTTP `8766` (not published on the host). Clients must switch endpoint scheme and may need TOFU/SPKI confirmation only for the first cutover or an independently authorized certificate-rotation maintenance window. **Do not** run `./deploy/push-and-deploy.sh` until the user confirms.

Default control plane (override only via env):

| Role | Address / value |
|---|---|
| SSH | `ssh -p 10000 13096920600@192.168.50.4` |
| Remote package dir | `/tmp/lezi-sync-releases/lezi-sync-<ver>-nas` |
| Pre-TLS live health (measured) | `http://192.168.50.4:8765/health` and `/ready` (SSH: `http://127.0.0.1:8765/...`) |
| Post-TLS LAN endpoint / health | `https://192.168.50.4:8765` (cert under data-bind `tls/`) |
| Post-TLS client health | `https://192.168.50.4:8765/health` and `/ready` (required) |
| Post-TLS container readiness | `docker exec lezi-sync lezi-sync healthcheck` (internal :8766; not published on host) |
| TLS SAN host | `LEZI_TLS_HOST=192.168.50.4` |
| Data bind | default `LEZI_DATA_HOST_PATH` → `/tmp/zfsv3/sata1/13096920600/data/Docker/lezi/data` |

After confirmation:

```bash
./build-image.sh
./deploy/push-and-deploy.sh
```

Before ordinary CD, record the live certificate fingerprint and SPKI with read-only commands. The
push script must also complete its direct-to-age credential export before stop/rm. After
CD, recompute both and require exact equality before declaring success. If the live identity cannot be
read through `docker exec` as uid `10001`, stop before container replacement; do not infer that it is
absent from the SSH user's inability to traverse the mode-`700` bind.

Packaging is fail-closed: a signed `app/build/outputs/apk/release/app-release.apk` must match `tools/lezi-sync/deploy/app-update.json` `sha256` and tracked public signer digest `config/release-apk-signer-sha256.txt` (or set APK/metadata paths; the signer pin has no routine override). `LEZI_SKIP_PACKAGE=1` skips rebuilding only: push still verifies the actual APK signer, selected image must be deliberate, helpers must byte-match, and every package hash must pass locally/remotely. If server/deploy code changed, run `build-image.sh` and use default fresh packaging. Never invent `LEZI_BOOTSTRAP_SECRET` (ordinary CD validates live + persistent sources). Never print bootstrap secrets/private keys from `docker inspect`, files, SSH argv, logs, or reports. Never commit signing keys, secrets, NAS `.env`, age identity/ciphertext backups, or `dist/` tarballs.

**3. 前后端联调 (minimal smoke after deploy)**

1. **Server — probe actual protocol, do not assume.** Prefer post-TLS **LAN HTTPS** first (`https://192.168.50.4:8765/...` with the data-bind cert or `curl -k` when mode-700 hides the cert). Optional corroboration: `docker exec lezi-sync lezi-sync healthcheck` (container-internal :8766). Do **not** SSH-curl host `http://127.0.0.1:8766` — compose does not publish it. If HTTPS fails with TLS wrong-version and HTTP `8765` still answers, report **protocol drift** (live image not the TLS stack). Expect healthy/ready; when `/health` reports a version, it should match the deployed image.
2. **Client**: device or emulator on the same LAN as the NAS. Emulator → NAS uses the real LAN IP, **not** `10.0.2.2` (`10.0.2.2` is only for host-local compose). Install the client if needed (`./gradlew :app:installDebug` or an existing APK). Point the endpoint at the **scheme that health proved** (`https://192.168.50.4:8765` after TLS CD; plaintext only if still on the pre-TLS image). Complete TOFU/SPKI when the trusted-HTTPS path applies. Smoke **only paths touched by the change** (e.g. setup-status, create/join, push-pull, app-update)—not a full dual-device matrix unless the ticket requires it.
3. **Report** gates, deployed version/package, which health URLs worked, protocol (HTTP vs HTTPS), and client smoke result (or the blocker).

Production replace requires stop and rm to succeed, starts compose project `lezi`, and proves the running container image id equals the manifest. That replace is gated by the propose-then-confirm rule above. For an ordinary same-schema image rollback, repackage the deliberately selected old image with the **current** guarded deploy helpers, then run `push-and-deploy.sh`; it creates a fresh encrypted credential backup before replacement. Never execute a legacy package's bundled `remote-deploy.sh` directly. The exceptional pre-TLS/schema cutover rollback cannot use this current TLS harness; follow its dedicated runbook and do not begin that cutover unless its independently authenticated state pin and an executable, audited recreation procedure are complete.

Schema is fresh-only / current `user_version`; mismatched DBs fail closed—do not invent migrations in deploy scripts.

## Coding Style & Naming Conventions

Use four-space Kotlin indentation, trailing commas in multiline declarations, and standard Kotlin naming: `UpperCamelCase` for types and composables, `lowerCamelCase` for functions and properties. Keep packages under `com.lezi.babylog`. Put reusable UI in `designsystem`; features should depend on domain seams instead of injecting DAOs directly. Format Rust with `cargo fmt` and keep Clippy warning-free.

## Testing Guidelines

Android tests use JUnit 4, Truth, coroutine-test, and AndroidX Compose tooling. Name JVM and instrumentation classes `*Test`; use `*DeviceTest` for hardware/emulator-specific coverage. Add regressions in the changed module and include UI/device evidence for visual or interaction changes. No numeric coverage threshold is declared, but changed behavior must be exercised.

## Commit & Pull Request Guidelines

Follow `type(scope): imperative summary`, for example `fix(timeline): use DST-safe three-day axis`. Explain rationale and verification in nontrivial commit bodies. Target PRs at `master`, use `.github/pull_request_template.md`, link the relevant `.scratch/` ticket, and update PRD/ADR documents for behavior or wire changes. Include screenshots for UI work and report tests, emulator/device checks, or Docker health checks performed. Never commit signing files, tokens, real family/SSID data, APKs, or generated build directories (`dist/` is gitignored; do not force-add release tarballs).
