# Greenfield product line (1.0.0)

Parallel rewrite of 乐记: Android `com.lezi.babylog.gf` + `lezi-gf-sync` on HTTPS **18765**.

| Path | Role |
|------|------|
| [MODULES.md](./MODULES.md) | Module names & DI defaults |
| [functional-parity-matrix.md](./functional-parity-matrix.md) | UV + G1–G10 one-to-one functional 对照 vs original 0.3.x |
| [apk-size-compare.md](./apk-size-compare.md) | Real APK size/identity 对照 (descriptive) |
| [cutover-mapping-checklist-draft.md](./cutover-mapping-checklist-draft.md) | Ticket 39 mapping draft |
| `../android/` | Independent Gradle project |
| `../sync-server/` | Independent Cargo project |
| `../.data/` | Gitignored local server data |
| `../scripts/boundary-scan.sh` | Ticket 41 isolation gate |

**Default endpoint:** `https://127.0.0.1:18765` — never family NAS.

```bash
# Server
cd greenfield/sync-server
LEZI_GF_DATA=../.data LEZI_GF_BOOTSTRAP_SECRET=greenfield-dev-bootstrap cargo run

# Android
cd greenfield/android
./gradlew :app:assembleDebug test
```
