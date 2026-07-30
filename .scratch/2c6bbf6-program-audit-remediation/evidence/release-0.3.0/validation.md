# 0.3.0 final validation

Date: 2026-07-30 (Asia/Shanghai)

Validated product HEAD: `eed73cdb9ce6a4c3184728a8dae1e6bd3d5a9247`

This is the fixed product tree used to build the final APK. The later tracker-only closure commit
does not alter production source, build configuration, tests, or the APK.

## Fixed-point regression gates

- Android JVM/lint/Debug gate: `./gradlew test lintDebug assembleDebug --no-parallel` passed
  (1,387 tasks). An earlier parallel lint invocation hit an Android Lint FIR internal crash;
  the isolated lint task and the complete no-parallel gate both passed.
- Rust server: `cargo fmt --check`, `cargo clippy --locked -- -D warnings`, and
  `cargo test --locked` passed (36 unit tests and 85 API tests).
- API 35 all-module device regression: `./gradlew connectedDebugAndroidTest --no-parallel`
  passed 72 tests with zero failures/errors across app, database, family, log, and onboarding.
- The device suite exposed a legacy test type mismatch (`Int` expected versus persisted `Long`);
  the assertion was corrected in `d0b206a`, then the database slice and the complete device gate
  passed.

After the version/configuration change, `:sync:testDebugUnitTest :app:assembleDebug` passed and
the Debug manifest reported `versionCode=6`, `versionName=0.3.0-debug`.

## Exact Release gate and artifact

`./gradlew test lintRelease assembleRelease --no-parallel` passed at the validated product HEAD
(1,436 tasks), including JVM regressions, all Release lint tasks, R8/shrinking, packaging, and the
repository signature check.

- Build output: `app/build/outputs/apk/release/app-release.apk`
- Delivery: `dist/lezi-0.3.0-release.apk`
- Package: `com.lezi.babylog`
- `versionCode`: `6`
- `versionName`: `0.3.0`
- Size: `5,438,235` bytes
- SHA-256: `4adec7e4d5177125d3cd046e70d71155e3d99026bd80a0cf0aca6dce6f8e0712`
- Build and delivery files are byte-identical (`cmp` passed).

`apksigner verify --verbose --print-certs` passed. APK Signature Scheme v2 and v3 are enabled;
the signing certificate SHA-256 is
`ce1438c8c50fe75f04f89ae2092631a46660480764cd52071cc5c08707462211` (RSA 2048).

## Final Release device smoke

The Release APK was freshly installed on an API 35 x86_64 Android emulator; there was no existing
`com.lezi.babylog` package. Installation succeeded, and a cold launch of
`com.lezi.babylog/.MainActivity` completed with `Status: ok` and `TotalTime: 332 ms`.

Installed-package evidence reports `versionCode=6`, `versionName=0.3.0`,
`apkSigningVersion=3`, `minSdk=26`, and `targetSdk=35`. `MainActivity` was top-resumed and no
`AndroidRuntime` error appeared after launch.

The fresh onboarding page exposed `欢迎使用乐记`, `新建家庭`, and `加入家庭`. Page smoke opened
both paths:

- Create showed server host/port, Wi-Fi, nickname, optional family name, bootstrap secret,
  create action, and return action.
- Join showed the three-step network/invite/share explanation, server host/port, two Wi-Fi names,
  invitation flow, and Join/Cancel actions.

The complete API 35 connected suite above remains the automated regression evidence for the
post-onboarding product pages.

## Current-wire and device convergence

The 0.3.0 Rust Release binary was started with a fresh temporary data root on loopback. Live
responses were:

```json
{"capabilities":["atomic_bundle","record_membership_author"],"ok":true,"version":"0.3.0"}
{"ok":true,"version":"0.3.0"}
```

The temporary process and data root were removed after `/health` and `/ready` passed. Full
current-wire restart, tombstone, historical custom record/plan, fulfillment, and two-photo atomic
bundle convergence is retained in [`../06/current-wire-smoke.md`](../06/current-wire-smoke.md).
Two independent API 35 Android processes previously completed the matching edit/push/ack,
invite/full-pull, and cold-restore flow in
[`../06/two-android-device-smoke.md`](../06/two-android-device-smoke.md).

This evidence validates the repository's current NAS server implementation and current wire on a
local host. It does not claim deployment to a physical NAS, a physical-phone run, camera scanning,
or spoken TalkBack; those target-environment limits remain explicit in the PRD.

## Closure

- doc-code-gap-remediation: 7/7 complete
- p1-redundancy-ui-debt: 10/10 complete
- record-layout-edit-remediation: 12 local tickets + canonical P1/02 complete
- program audit remediation: 26/26 findings fixed; 24 local + 2 canonical layout tickets complete
- Active frontier: none
