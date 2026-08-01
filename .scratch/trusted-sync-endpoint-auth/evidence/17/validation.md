# Ticket 17 · Release 0.3.1 delivery · 2026-08-01

## Versions

| Surface | Value |
|---------|--------|
| Android `versionName` | `0.3.1` |
| Android `versionCode` | `7` |
| Rust `lezi-sync` | `0.3.1` (`CARGO_PKG_VERSION`) |
| Image tag | `lezi-sync:0.3.1` |
| Working tree base HEAD (pre-release commits) | `72c090e8556843cdec59198ccfb4ac578de71d55` |
| Working tree release commit | `aa7d3df32bc3a7d13155da5e1e364a86cffe3430` |
Version sources: `app/build.gradle.kts`, `tools/lezi-sync/Cargo.toml` + `Cargo.lock`,
`tools/lezi-sync/Dockerfile` / `build-image.sh` / `docker-compose.yml` defaults,
`tools/lezi-sync/deploy/app-update.json`.

## Gates (on version-bumped tree)

| Gate | Result |
|------|--------|
| `cargo fmt --all -- --check` | PASS |
| `cargo test --locked` | PASS (236 tests across suites) |
| `cargo clippy --all-targets --all-features -- -D warnings` | PASS |
| `./gradlew test lintDebug` | PASS (re-run after version bump; one flaky layout test re-ran green) |
| `./gradlew :app:assembleRelease --rerun-tasks` | PASS; signature verified |
| Emulator install + launch (`emulator-5554`) | PASS · `versionName=0.3.1` `versionCode=7` · pid alive |

Rust suite re-ran fully on `0.3.1`. Android JVM + lint re-ran on the version-bumped tree.

## Artifacts (gitignored)

Package: `dist/lezi-sync-0.3.1-nas/`

| Artifact | SHA-256 / id |
|----------|----------------|
| Release APK `app/build/outputs/apk/release/app-release.apk` (5,552,837 B) | `f387f7dab97b265ab98c39d1e2bcb5689cace42859a894211dcbd252f6423ed6` |
| Image `lezi-sync:0.3.1` | `sha256:9ae13015ec035f3a468ef126781d3880d00a70dbab5f4dc068050073a9524a63` |
| Image tar `lezi-sync-0.3.1-linux-amd64.tar` (35,062,272 B) | `80ceabb5333dfd0fb208c23dca6b088172de02f793080cef4d9dfc976fc346f3` |
| `app-update.json` pin | matches APK sha256; `version_code=7` `version_name=0.3.1` `min_supported=1` |
| Delivery summary copy | `dist/release-0.3.1/delivery.json` (+ APK/MANIFEST/SHA256SUMS copies) |

`MANIFEST.json` records `version=0.3.1`, image id above, `git_sha=72c090e` (packaged
before the release commit lands; final commit SHA supersedes for source pin).

## Final 0.3.1 server smoke (synthetic, not production NAS)

Container `lezi-031-smoke` · image `lezi-sync:0.3.1` · host HTTPS `https://127.0.0.1:18775`

| Check | Result |
|-------|--------|
| `GET /health` | `ok=true` `version=0.3.1` capabilities `atomic_bundle`,`record_membership_author` |
| `GET /ready` | `ok=true` `version=0.3.1` |
| Docker health | healthy |
| `lezi-sync healthcheck` | exit 0 |
| Owner login + `GET /v1/pull?cursor=0&generation=…` with client version 7 | 200 |

**Not run:** production NAS `push-and-deploy.sh` replace (requires explicit maintenance-window
confirm per `AGENTS.md`).

## Ticket 16 prerequisite

Dual-client Must closed by operator physical E2E:
[`../16/dual-client-closeout.md`](../16/dual-client-closeout.md).

## Status

Ticket 17 acceptance checklist complete for local rebuild + synthetic smoke + versioned
artifacts. Production CD remains a separate operator step.
