# Lezi greenfield 1.0.0

Parallel rewrite of 乐记 (full product line), isolated from the production `0.3.x` stack.

| | |
|--|--|
| Android | `android/` · `applicationId` **com.lezi.babylog.gf** · version **1.0.0** |
| Server | `sync-server/` · crate **lezi-gf-sync** · version **1.0.0** |
| HTTPS | **18765** (never production 8765 / family NAS by default) |
| Data | `.data/` (gitignored) |
| Docs | `docs/MODULES.md`, `docs/cutover-mapping-checklist-draft.md` |
| Boundary | `scripts/boundary-scan.sh` |

## Quick start

```bash
# Server
cd greenfield/sync-server
LEZI_GF_DATA=../.data LEZI_GF_BOOTSTRAP_SECRET=greenfield-dev-bootstrap cargo run

# Android
cd greenfield/android
./gradlew :app:installDebug test
```

## Architecture

Capability verticals (`care`, `family`, `settings`) + `syncsession` (sole wire client) + thin `kernel`, assembled only in `:app` composition root. See `docs/MODULES.md`.

## Gates

```bash
cd greenfield/sync-server && cargo fmt --all -- --check && cargo test --locked && cargo clippy --all-targets --all-features -- -D warnings
cd greenfield/android && ./gradlew test
./greenfield/scripts/boundary-scan.sh
```
