# 0.3.9 protocol cutover acceptance and NAS CD receipt

Date: 2026-08-05

## Fixed implementation

- Implementation range: `ceb7d2f7..ff80edbf` from fixed point `95e97df9`.
- Final two-axis review: Standards PASS and Spec PASS; no blocking finding or scope creep remained.
- Production startup classified one retained legacy fulfillment as deferred and completed validation for one family / 67 authority entities before advertising
  `validated_deferred_fulfillment_v1`. No production SQLite edit, schema migration, fact synthesis, or data-root rewrite was performed.

## Gates

- Android: `./gradlew test lintDebug :app:assembleRelease` passed; the signed Release APK identity and metadata gate passed.
- Rust: format and Clippy with warnings denied passed; 153 library tests, 156 API tests, and 2 isolated TLS tests passed.
- Focused protocol tests cover generation/full-resync, deferred pull visibility, exact media-byte validation, late Record resolution, second-peer convergence, unknown-corruption fail-close, complete manifest evidence, candidate stamps, and anonymous retained evidence.
- `deploy/test-remote-deploy-app-update-atomic.sh` passed, including rollback to the prior APK/floor when the live 8767 proof fails. The older TLS deploy fake-package harness could not enter its scenario because it lacks the current closed-inventory package files; production package validation and the isolated TLS tests passed independently.

## Release artifacts

- Source/package SHA: `ff80edbf`.
- App: `com.lezi.babylog`, version `0.3.9`, version code `16`.
- Release APK SHA-256: `7d2aa4cb4f48ec9e6078b5c76c1831a39d8168174485c658362dedaa32477b5c`.
- Release signer SHA-256: `ce1438c8c50fe75f04f89ae2092631a46660480764cd52071cc5c08707462211`.
- Server config image id: `sha256:2e4e67ad610e93820de07dd4f0b74ec0723cddb13459322bf303d5c8fe38b7d8` (`linux/amd64`).
- Image tar SHA-256: `67a8ff13392e56cc75527f1d534b60b6d714eda6f67001c1a5c312ac7f5629a8`.

## NAS CD receipt

- Fresh closed package deployed and retained at `/tmp/lezi-sync-releases/lezi-sync-0.3.9-nas`.
- Direct-to-age encrypted credential backup:
  `/home/zhangtianshu/.config/lezi/backups/lezi-sync-0.3.9-credentials-20260805T045801Z-1949030.age` with its `.sha256` sidecar.
- Running container is healthy on the exact config image id. LAN HTTPS `/health` and `/ready` return `0.3.9`; internal `lezi-sync healthcheck` passes. Host HTTP 8765 is not accepted.
- LAN HTTP `/join` returns 200 and `/download/lezi.apk` hashes exactly to the Release APK.
- Existing TLS identity was reused. Raw certificate-file SHA-256 stayed
  `75023c71d8ca918a42fe4f058aab8faf85db3f02b9a69bfb6522951ce362da9e` before/after; independent DER certificate SHA-256 stayed
  `9a34ba5bbb2442e45a7d9d056056b28503c4d67fbb3732e478a452d0a51d37f0`; SPKI stayed
  `bd07d8645ed3b7adead162eca454373aee4007b0a35aa7c62caf7d8ac0cb3215`.
- The production generation is an opaque per-process value exposed only through authenticated sync responses. The retained device session was invalid on first 0.3.9 launch (its pre-upgrade auth state was not captured), so its live generation value could not be recorded without re-login; fixed-generation full-resync behavior is covered by the focused fixture.

## Client smoke and remaining blocker

- Existing `emulator-5554` was 0.3.8/versionCode 15. `adb install -r` installed the signed 0.3.9/versionCode 16 APK successfully without changing its original install time or clearing local data.
- Cold launch succeeded with no crash. Existing local baby/plan UI remained present, proving the Room-facing data survived the in-place APK update.
- The retained session displayed “登录已失效”; therefore it could not authenticate a first 0.3.9 sync. The second existing AVD had no Lezi installation and was stopped without modification.
- Consequently the live NAS still retains the one historical fulfillment as deferred. This is the required honest negative state: the server remains ready and unrelated data is not blocked, but no missing Record was fabricated. Ticket 04 stays blocked until a still-authorized retained administrator and a second joined client are available for the final live convergence smoke.
