# Repository Guidelines

## Public GitHub mirror (snapshot only)

GitHub holds a privacy-filtered snapshot of `master` — never real history.
Publishing is automatic and needs no extra command: pushing `master` to
`origin` (Gitea) triggers the local `pre-push` hook, which runs the untracked
`.scratch/github-snapshot-push.sh` — drop `.scratch/`, rewrite known local
identifiers (account numbers, home paths, LAN hosts) to synthetic values, run
the fail-closed privacy scan, then push one synthetic commit via an explicit
URL. A scan failure aborts the push itself (bypass once with
`LEZI_SKIP_PUBLIC_SNAPSHOT=1`); a GitHub network failure only warns and the
mirror catches up on the next push. Manual runs still work:
`.scratch/github-snapshot-push.sh [--check]`. Direct `git push github` stays
disabled at the remote push URL — the histories share no ancestor, so a normal
push would publish full history. Real operator values never live in tracked
files — they sit in untracked `tools/lezi-sync/deploy/env.local`. Local
history and the private Gitea remote keep full fidelity.

## Project Structure & Module Organization

Lezi is a Kotlin/Jetpack Compose Android app with a Rust home-LAN sync service. `app/` owns application wiring; `core/` contains shared models, storage, and UI infrastructure; `domain/` exposes use cases; `designsystem/` holds reusable Compose tokens and components; and product screens live under `feature/<name>/`. Android sync code is in `sync/`; the Axum/SQLite NAS service is in `tools/lezi-sync/`. Keep unit tests beside each module in `src/test/` and device/Compose tests in `src/androidTest/`.

**Package locality (current):** after the 2026-08 audit remediation, put new code in the existing capability/call-flow subpackages — do not keep flattening already-partitioned module roots. Keep deep façades at the root (`domain` `CareLog`, `sync` `SyncPort`/`RealSyncPort`, lezi-sync `Store`). Do not add product-less source-layout/line-count StructureTests; behavior tests are the refactor contract. Landed map: `docs/spec/architecture.md` §3.

Treat `docs/spec/` as product authority, `CONTEXT.md` as terminology authority, and `docs/adr/` as architecture history. Local specs and tickets live in `.scratch/`; GitHub Issues are not the tracker.

## Build, Test, and Development Commands

Use JDK 21 and configure the Android SDK in gitignored `local.properties`.

- `./gradlew :app:assembleDebug` builds the debug APK.
- `./gradlew :app:installDebug` installs it on a connected device/emulator.
- `./gradlew test` runs all JVM unit tests.
- `./gradlew lintDebug` runs Android lint.
- `./gradlew connectedDebugAndroidTest` runs instrumentation tests with a device available.
- `cd tools/lezi-sync && cargo fmt --all -- --check` checks Rust formatting.
- In that directory, `cargo test --locked` and `cargo clippy --all-targets --all-features -- -D warnings` are the server gates.
- Dual-side live proof (loopback, not NAS): `cd tools/lezi-sync && cargo build -p lezi-sync` then `LEZI_SYNC_BIN="$PWD/target/debug/lezi-sync"` and the two Gradle `--tests` lines from the replacement section. Requires openssl, curl, sqlite3.

## lezi-sync server release

**VPS deployment tooling was removed at the user's request (2026-09-06, after
the 2026-09-05 suspension).** The public-VPS plan is abandoned; do not execute,
recreate, or modify VPS deployment infrastructure. The deleted scripts live only
in git history, and the frozen `.scratch/vps-public-authority` ticket is retained
local-only (untracked; see `.gitignore`); neither is authorization to resume
that path.

**NAS rollback and maintenance:** [`tools/lezi-sync/deploy/DEPLOY.md`](tools/lezi-sync/deploy/DEPLOY.md).
Real target values live in untracked `tools/lezi-sync/deploy/env.local`
(`NAS_SSH`, `NAS_SSH_PORT`, `LEZI_DATA_HOST_PATH`, `LEZI_TLS_HOST`) — source it
before NAS commands; tracked files carry only synthetic examples
(`nas-operator@192.168.77.10:10000`). Keep NAS scripts on the real family
target. Permission repair is operator-executed; container replacement
still requires a separately confirmed window.

**Do not** build the server on remote hosts (`cargo` / `docker build`).
**Do not** install system `docker compose` on the NAS for this product path.
**Do not** commit bootstrap secrets, real family data, image tarballs under `dist/`, or NAS `.env` files.

### NAS rollback CD (frozen)

Authoritative NAS runbook: `tools/lezi-sync/deploy/DEPLOY.md`.

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
   `/tmp/zfsv3/sata1/nas-account/data/Docker/lezi/data`) → `/data`. Stop/rm container must **not** delete that directory. Container user `10001:10001`; host dir must be writable by that uid.
7. **Publish**: host `0.0.0.0:8765` for LAN HTTPS sync and `0.0.0.0:8767` for isolated LAN HTTP invite-install; never map either port to the public internet without a deliberate firewall exception. Container-internal HTTP 8766 is not published.
8. **Remote package dir**: default  
   `/tmp/lezi-sync-releases/lezi-sync-<ver>-nas` because Zspace SSH `HOME` is often `/home/` (not writable). Prefer a persistent path via `NAS_REMOTE_DIR` when available.

### Commands agents should use

```bash
cd tools/lezi-sync
cargo fmt --all -- --check
cargo test --locked
cargo clippy --all-targets --all-features -- -D warnings
# NAS rollback only, after user confirms:
# ./build-image.sh && ./deploy/push-and-deploy.sh
```

**NAS rollback probe** (measured 2026-07-31: family NAS was still plaintext HTTP on 8765; current NAS tree/CD targets HTTPS 8765 + **container-internal** HTTP readiness on 8766 — compose does **not** publish host:8766).


```bash
# Real host comes from tools/lezi-sync/deploy/env.local; synthetic example below.
# Pre-TLS / still-HTTP live (worked on measured NAS):
curl -fsS http://192.168.77.10:8765/health
curl -fsS http://192.168.77.10:8765/ready
ssh -p 10000 nas-operator@192.168.77.10 'curl -fsS http://127.0.0.1:8765/health'

# Post-TLS client-facing (required for APK TOFU):
# curl --cacert <data-bind>/tls/server.crt -fsS https://192.168.77.10:8765/health
# mode-700 bind: docker exec lezi-sync cat /data/tls/server.crt > /tmp/lezi.crt
# or curl -k -fsS https://192.168.77.10:8765/health
# Container-internal readiness only (optional; image has no curl):
# ssh -p 10000 nas-operator@192.168.77.10 'docker exec lezi-sync lezi-sync healthcheck'
# Do NOT: ssh … 'curl http://127.0.0.1:8766/health' — nothing listens on host:8766.
# HTTPS against a plaintext 8765 yields TLS "wrong version number" — treat as drift, not success.
# Ticket 07 probe: tools/lezi-sync/deploy/live-cutover-probe.sh
```

| Variable | Role |
|---|---|
| `NAS_SSH` / `NAS_SSH_PORT` | SSH target and port; real values only via untracked `tools/lezi-sync/deploy/env.local` (synthetic example `nas-operator@192.168.77.10`, port `10000`) |
| `NAS_REMOTE_DIR` | Unpack + deploy directory on NAS |
| `LEZI_DATA_HOST_PATH` | Host bind for `/data` when packaging |
| `LEZI_SECRET_FILE` | NAS-side persistent bootstrap file; defaults to data bind sibling `../config/lezi-sync.env`; overrides use a normalized portable absolute-path character set |
| `LEZI_ALLOW_SECRET_RECOVERY=1` | Explicit incident authorization to use the persistent file when no live container exists; ordinary CD leaves unset |
| `LEZI_ALLOW_SECRET_RESEED=1` | Explicit maintenance-only replacement of a conflicting file; live container must be absent and a new secret explicitly forwarded |
| `LEZI_FORWARD_BOOTSTRAP_SECRET=1` | Explicit fresh/cutover-only SSH-stdin forwarding of `LEZI_BOOTSTRAP_SECRET`; ordinary CD leaves unset |
| `LEZI_TLS_HOST` | Certificate SAN host (default `192.168.77.10`) |
| `LEZI_ALLOW_TLS_BOOTSTRAP=1` | One-time TLS generation on an operator-verified fresh data root only; ordinary CD/rollback/tests must leave unset |
| `LEZI_SKIP_PACKAGE=1` | Explicitly reuse an existing local package; default push repackages. Reuse still requires matching local image config digest/OS/architecture and re-attests data/TLS/origin, helpers/inventory/hashes, plus APK signer, application/version, metadata, and local-data contract. |
| `LEZI_PACKAGE_BUILD_IMAGE=1` | `package-nas.sh` builds image if missing |
| `LEZI_PACKAGE_APP_UPDATE_CHECK_ONLY=1` | Validate release APK + `app-update.json` only (no docker save); CI smoke: `./deploy/test-package-nas-app-update.sh` |
| `LEZI_AGE_RECIPIENTS_FILE` | Public age recipients file; default `~/.config/lezi/age-recipients.txt`; required by production push/CD |
| `LEZI_CREDENTIAL_BACKUP_DIR` | Absolute off-repo encrypted backup directory; default `~/.config/lezi/backups/` |
| `LEZI_AGE_IDENTITY_FILE` | Offline/private age identity for restore staging only; never needed by CD or stored in the repo |
| `LEZI_EXPECTED_CERTIFICATE_SHA256` / `LEZI_EXPECTED_SPKI_SHA256` | Independent production pins required for restore staging; never trust only digests carried inside the same ciphertext |

### Agent workflow: cheapest proof, NAS only when required

**Current alignment — 0.5.4 everywhere (2026-09-30 coordinated CD).** Android tree 0.5.4 / versionCode 34; `tools/lezi-sync` crate and family NAS 0.5.4; wire 0.4.0 / schema 13 / floor 21. Client-ahead with frozen wire is allowed (it produced the 2026-09 split; the 2026-09-30 maintenance window closed it). Do not propose NAS, bump `Cargo.toml`, or refresh `app-update.json` solely so versionName matches.

**Live server ≠ NAS.** Dual-side live proof is `IsolatedLeziSyncServer` (mktemp data root, openssl loopback cert, tree `lezi-sync` binary). Certificate creation/replacement/TOFU tests stay on that harness; never against the family NAS, its container, or its data bind.

Classify the diff with the **first matching** row. Run only that row’s proof. Do not stack `./gradlew test` + Rust three-gates + NAS as a default.

| Match first | Detect (any path in the diff) | Run | Do not run |
|---|---|---|---|
| A docs | only comments / markdown / this file with no runtime effect | nothing runtime | cargo, IsolatedLeziSyncServer, NAS |
| B Android non-sync | no `tools/lezi-sync/`, no `sync/`, no wire files below | `./gradlew :<changed-module>:testDebugUnitTest` (fallback `./gradlew :<changed-module>:test`) | cargo, IsolatedLeziSyncServer, NAS. Optional: phone against **existing** NAS, no CD, only if the UI actually talks to the family server |
| C Android sync, wire frozen | `sync/` or domain CareLog sync path; **not** `tools/lezi-sync/src`; **not** wire files | changed-module JVM tests. If any of `sync/src/main/.../engine/`, `.../backend/`, `.../heartbeat/`, `RealSyncPort.kt` changed: Isolated dual-side. Need a tree binary: `cd tools/lezi-sync && cargo build -p lezi-sync` only (not fmt/test/clippy) | full Rust gates; NAS CD. APK vs NAS version drift beyond a coordinated window needs a ticket |
| D server, wire frozen | `tools/lezi-sync/src`, `Cargo.toml`, or `Cargo.lock`; **not** wire files | Rust three-gates under `tools/lezi-sync/`: `cargo fmt --all -- --check`, `cargo test --locked`, `cargo clippy --all-targets --all-features -- -D warnings`. Then Isolated dual-side against the **current** Android tests | full `./gradlew test` unless Android files also changed. Propose NAS only if the change has runtime effect on the family container (handlers/store/`main`/`lib`, or deploy scripts that will execute on NAS). Test-only Rust / comments: no NAS |
| E wire / schema / floor | `docs/spec/contracts/causal-sync-wire.md`, `docs/spec/contracts/sync-trusted-endpoint.md`, `config/conflict-v2-golden.json`, `config/conflict-v2-golden.schema.json`, handshake/`requireExactKeys`/serde closed keys, `user_version` / `DATABASE_SCHEMA_VERSION`, `min_supported_version_code` / `minimum_sync_version_code`, handshake `capabilities` | Rust three-gates + `cargo test --locked --test conflict_v2_contract_fixture` + `./gradlew :sync:testDebugUnitTest --tests com.lezi.babylog.sync.backend.ConflictV2GoldenCorpusTest --tests com.lezi.babylog.sync.conflict.ConflictSnapshotParserCorpusTest` + Isolated dual-side. Breaking wire: raise floor first per `docs/spec/platform.md` §4.2.1 | skip Isolated. After local proof, propose NAS |
| F deploy scripts only | `tools/lezi-sync/deploy/` without `src/` | matching `bash tools/lezi-sync/deploy/test-*.sh` already in `.github/workflows/lezi-sync.yml` (at least `test-package-nas-app-update.sh`, `test-nas-release-identity.sh` if identity/app-update touched) | IsolatedLeziSyncServer. Propose NAS only if the script’s live CD behavior changed and owner will run it |

**Wire files** means the E-row paths plus any edit that changes a closed JSON key set or capability string even if docs were not updated — treat as E.

#### Isolated dual-side (required by C/D/E)

Prereqs on the dev machine: openssl, curl, sqlite3, JDK 21. Never set `NAS_SSH`, `NAS_REMOTE_DIR`, `LEZI_DATA_HOST_PATH` on this child. Always build **this tree** and pin `LEZI_SYNC_BIN`; do not rely on `~/.cache/cargo-target` newest-mtime.

```bash
cd tools/lezi-sync
cargo build -p lezi-sync
export LEZI_SYNC_BIN="$PWD/target/debug/lezi-sync"
cd ../..
./gradlew :sync:testDebugUnitTest --tests com.lezi.babylog.sync.RealServerQuietRoundSmokeTest --tests com.lezi.babylog.sync.media.RealServerMediaReceiptFaultSeamTest
./gradlew :domain:testDebugUnitTest --tests 'com.lezi.babylog.domain.CareLogRealServerSeam*'
```

Row E additionally runs the golden commands in the table **before** this block. Row D runs Rust three-gates **before** this block (`cargo build` is then incremental).

Missing binary, `openssl`/`curl` skip, or IsolatedLeziSyncServer failure → `cargo build -p lezi-sync`, export `LEZI_SYNC_BIN`, retry. Do **not** propose NAS to recover. `capability_mismatch`, `requireExactKeys` / “字段不完整或包含未知字段”, or schema `user_version` fail-closed → fix the matching side locally; NAS will not teach a different contract.

#### Propose NAS — only when the table says so

Confirm `age` plus the public recipients file before requesting the window. NAS `push-and-deploy.sh` remains the guarded entry. Explain that it will build a `linux/amd64` image, scp to the family NAS, age-backup secret+TLS, and **stop/rm + replace** container `lezi-sync`. **Do not** run it until the user confirms; do not retarget it to another host.

NAS control plane (override only via env; defaults stay on the family NAS):

| Role | Address / value |
|---|---|
| SSH | `ssh -p 10000 nas-operator@192.168.77.10` |
| Remote package dir | `/tmp/lezi-sync-releases/lezi-sync-<ver>-nas` |
| Pre-TLS live health (measured) | `http://192.168.77.10:8765/health` and `/ready` (SSH: `http://127.0.0.1:8765/...`) |
| Post-TLS LAN endpoint / health | `https://192.168.77.10:8765` (cert under data-bind `tls/`) |
| Post-TLS client health | `https://192.168.77.10:8765/health` and `/ready` (required) |
| Post-TLS container readiness | `docker exec lezi-sync lezi-sync healthcheck` (internal :8766; not published on host) |
| TLS SAN host | `LEZI_TLS_HOST=192.168.77.10` |
| Data bind | default `LEZI_DATA_HOST_PATH` → `/tmp/zfsv3/sata1/nas-account/data/Docker/lezi/data` |

After NAS confirmation only:

```bash
./build-image.sh
./deploy/push-and-deploy.sh
```

NAS still requires pre/post certificate SHA-256 and SPKI equality. If the live NAS identity cannot be read through `docker exec` as uid `10001`, stop before container replacement.

Packaging is fail-closed: a signed `app/build/outputs/apk/release/app-release.apk` must match `tools/lezi-sync/deploy/app-update.json` `sha256` and tracked public signer digest `config/release-apk-signer-sha256.txt`. Never invent `LEZI_BOOTSTRAP_SECRET`. Never print bootstrap secrets or private keys. Never commit signing keys, secrets, NAS `.env`, age identity/ciphertext backups, or `dist/` tarballs.

After replacement: LAN HTTPS `https://192.168.77.10:8765` (data-bind cert or `curl -k`) `/health` and `/ready`. Do **not** SSH-curl host `http://127.0.0.1:8766`. Client smoke only paths touched by the change, pointed at the origin that health proved. Report gates, deployed version/package, protocol, and client smoke (or the blocker).

NAS `push-and-deploy.sh` remains gated by propose-then-confirm and takes its target from `NAS_SSH` / `env.local` (no tracked real default); VPS deployment is removed.

Schema is fresh-only / current `user_version`; mismatched DBs fail closed—do not invent migrations in deploy scripts.

## Coding Style & Naming Conventions

Use four-space Kotlin indentation, trailing commas in multiline declarations, and standard Kotlin naming: `UpperCamelCase` for types and composables, `lowerCamelCase` for functions and properties. Keep packages under `com.lezi.babylog`. Put reusable UI in `designsystem`; features should depend on domain seams instead of injecting DAOs directly. Format Rust with `cargo fmt` and keep Clippy warning-free.

## Testing Guidelines

Android tests use JUnit 4, Truth, coroutine-test, and AndroidX Compose tooling. Name JVM and instrumentation classes `*Test`; use `*DeviceTest` for hardware/emulator-specific coverage. Add regressions in the changed module and include UI/device evidence for visual or interaction changes. No numeric coverage threshold is declared, but changed behavior must be exercised.

## Commit & Pull Request Guidelines

Follow `type(scope): imperative summary`, for example `fix(timeline): use DST-safe three-day axis`. Explain rationale and verification in nontrivial commit bodies. Target PRs at `master`, use `.github/pull_request_template.md`, link the relevant `.scratch/` ticket, and update spec (`docs/spec/`) / ADR documents for behavior or wire changes. Include screenshots for UI work and report tests, emulator/device checks, or Docker health checks performed. Never commit signing files, tokens, real family/SSID data, APKs, or generated build directories (`dist/` is gitignored; do not force-add release tarballs).
