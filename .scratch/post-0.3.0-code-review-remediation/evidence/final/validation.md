# Final fixed-point validation · Post-0.3.0 remediation

Fixed code HEAD: `64be97da10618d080bfd3371561c9932e3afde00`

Scope: Post-0.3.0 tickets 01–09 and APK page-acceptance ticket 01. The two documented
accepted residuals did not meet their reopen triggers and remain outside implementation scope.

## Repository-wide gates

- `./gradlew test lintDebug :app:assembleDebug` — PASS (`BUILD SUCCESSFUL in 14s`; 1,370
  actionable tasks: 218 executed, 2 from cache, 1,150 up-to-date).
- `./gradlew connectedDebugAndroidTest` — PASS on the API 35 `lezi_api35` AVD
  (`BUILD SUCCESSFUL in 2m 33s`; 936 actionable tasks: 87 executed, 849 up-to-date).
  All 74 executed device tests passed: designsystem 7, core database 6, feature log 45,
  feature family 2, and feature settings 14; zero skipped and zero failed.
- `tools/lezi-sync: cargo fmt --all -- --check` — PASS.
- `tools/lezi-sync: cargo clippy --all-targets --all-features -- -D warnings` — PASS.
- `tools/lezi-sync: cargo test --locked` — PASS: store/unit 38/38, HTTP API/current-wire
  86/86, documentation tests 0.
- `git diff --check` — PASS.

The validated Debug APK is `app/build/outputs/apk/debug/app-debug.apk`, 28,555,666 bytes,
SHA-256 `c8320aacb11e04dffcb2c21845dc563776a76fbd122f69a5ee01ac12892d2da6`.

## Snackbar device acceptance

- API 35 saved a real Pee record and displayed the Snackbar at `[32,1670][1048,1796]`.
- The fixed quick dock was at `[21,1859][1059,2053]`; the surfaces did not overlap and retained
  a 63 px vertical gap.
- While the Snackbar remained visible, tapping More at `(950,1950)` opened the Add Record sheet,
  confirming the dock remained reachable.
- Screenshot: [`../../../apk-0.3.0-page-acceptance-remediation/evidence/01/snackbar-above-dock.png`](../../../apk-0.3.0-page-acceptance-remediation/evidence/01/snackbar-above-dock.png),
  SHA-256 `72df58372480c32ffb1bb289ebe7a65d83e11c1f53b2dc81529bc1b1c487fa35`.

## Evidence boundary

- This is a Debug fixed-point validation, not a new signed Release APK receipt.
- The device gate used an emulator and an isolated local Rust server/current-wire path. It does
  not claim a physical phone, physical NAS, camera QR scan, or spoken TalkBack run.
- At gate execution, the only non-code workspace differences were this tracker-index closure work
  and the user-owned untracked `AGENTS.md`; neither changed the validated product code tree.
