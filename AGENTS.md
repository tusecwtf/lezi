# Repository Guidelines

## Project Structure & Module Organization

Lezi is a Kotlin/Jetpack Compose Android app with a Rust home-LAN sync service. `app/` owns application wiring; `core/` contains shared models, storage, and UI infrastructure; `domain/` exposes use cases; `designsystem/` holds reusable Compose tokens and components; and product screens live under `feature/<name>/`. Android sync code is in `sync/`; the Axum/SQLite NAS service is in `tools/lezi-sync/`. Keep unit tests beside each module in `src/test/` and device/Compose tests in `src/androidTest/`.

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

### Pipeline (locked decisions)

1. **Dev machine**: Rust gates → `./build-image.sh` → `linux/amd64` image `lezi-sync:<Cargo.toml version>`.
2. **Package**: `tools/lezi-sync/deploy/package-nas.sh` writes gitignored `dist/lezi-sync-<ver>-nas/` (image tar, zdocker-friendly compose, `MANIFEST.json`, `remote-deploy.sh`).
3. **Ship + replace**: `tools/lezi-sync/deploy/push-and-deploy.sh` scp’s the package over SSH and runs `remote-deploy.sh` on the NAS.
4. **NAS engine**: prefer Zspace **zdocker** binary  
   `/zspace/applications/services/zdocker/bin/docker-compose` (Compose v2.33.x). Fallback is plain `docker run` if that binary is missing.
5. **Secret**: inherit `LEZI_BOOTSTRAP_SECRET` from the live `lezi-sync` container env; write NAS-local `.env` mode `600` only. Fail if neither env nor live container provides a ≥16-char secret.
6. **Data**: bind-mount host path (default  
   `/tmp/zfsv3/sata1/13096920600/data/Docker/lezi/data`) → `/data`. Stop/rm container must **not** delete that directory. Container user `10001:10001`; host dir must be writable by that uid.
7. **Publish**: host `0.0.0.0:8765` for LAN; never map 8765 to the public internet without a deliberate firewall exception.
8. **Remote package dir**: default  
   `/tmp/lezi-sync-releases/lezi-sync-<ver>-nas` because Zspace SSH `HOME` is often `/home/` (not writable). Prefer a persistent path via `NAS_REMOTE_DIR` when available.

### Commands agents should use

```bash
cd tools/lezi-sync
cargo test --locked
cargo clippy --all-targets --all-features -- -D warnings
./build-image.sh
./deploy/push-and-deploy.sh
# verify from LAN or SSH:
# curl -fsS http://127.0.0.1:8765/health
# curl -fsS http://127.0.0.1:8765/ready
```

| Variable | Role |
|---|---|
| `NAS_SSH` / `NAS_SSH_PORT` | SSH target (default `13096920600@192.168.50.4`, port `10000`) |
| `NAS_REMOTE_DIR` | Unpack + deploy directory on NAS |
| `LEZI_DATA_HOST_PATH` | Host bind for `/data` when packaging |
| `LEZI_FORCE_PACKAGE=1` | Rebuild package even if `dist/` exists |
| `LEZI_SKIP_PACKAGE=1` | Deploy existing package only |
| `LEZI_PACKAGE_BUILD_IMAGE=1` | `package-nas.sh` builds image if missing |

Production replace stops/removes the existing `lezi-sync` container then `compose up` project `lezi`. Confirm maintenance window before running against a live family NAS. Rollback: re-run `remote-deploy.sh` from a previous package directory on the NAS (after that version’s image tar is still loadable).

Schema is fresh-only / current `user_version`; mismatched DBs fail closed—do not invent migrations in deploy scripts.

## Coding Style & Naming Conventions

Use four-space Kotlin indentation, trailing commas in multiline declarations, and standard Kotlin naming: `UpperCamelCase` for types and composables, `lowerCamelCase` for functions and properties. Keep packages under `com.lezi.babylog`. Put reusable UI in `designsystem`; features should depend on domain seams instead of injecting DAOs directly. Format Rust with `cargo fmt` and keep Clippy warning-free.

## Testing Guidelines

Android tests use JUnit 4, Truth, coroutine-test, and AndroidX Compose tooling. Name JVM and instrumentation classes `*Test`; use `*DeviceTest` for hardware/emulator-specific coverage. Add regressions in the changed module and include UI/device evidence for visual or interaction changes. No numeric coverage threshold is declared, but changed behavior must be exercised.

## Commit & Pull Request Guidelines

Follow `type(scope): imperative summary`, for example `fix(timeline): use DST-safe three-day axis`. Explain rationale and verification in nontrivial commit bodies. Target PRs at `master`, use `.github/pull_request_template.md`, link the relevant `.scratch/` ticket, and update PRD/ADR documents for behavior or wire changes. Include screenshots for UI work and report tests, emulator/device checks, or Docker health checks performed. Never commit signing files, tokens, real family/SSID data, APKs, or generated build directories (`dist/` is gitignored; do not force-add release tarballs).
