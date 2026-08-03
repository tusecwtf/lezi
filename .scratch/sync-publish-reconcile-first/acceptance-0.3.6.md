# 0.3.6 acceptance and NAS CD receipt

Date: 2026-08-04

## Product acceptance

- API 35 device migration passed for local-data contracts 1 -> 2 -> 3 and the
  contract-2 residual-outbox fixture. Room opened at schema 26, the outbox table
  was retired, the Record became publish-eligible, and media bytes were
  preserved exactly. The target-state retry also passed.
- The dual-client JVM acceptance starts at the migrated boundary (no outbox,
  dirty Record): Owner reconciles, plans, and pushes; Member then pulls the same
  Record from the shared fake backend.
- The final Release APK installed over the emulator app, cold-launched, and
  rendered the local timeline without a wipe. The emulator was not joined to a
  family, so no live-family mutation was performed; live peer visibility is
  evidenced by the dual-client acceptance above rather than a production-family
  client write.

## Gates

- Android: `./gradlew test lintDebug :app:assembleDebug :app:assembleRelease`
  passed; targeted migration and fresh-database instrumentation passed on
  `lezi_api35`.
- Rust: formatting, locked tests (286 tests), and Clippy with warnings denied
  passed.
- Isolated deploy-helper regressions and the signed APK metadata/package check
  passed.

## Release artifacts

- App: `com.lezi.babylog`, version name `0.3.6`, version code `13`.
- Release APK SHA-256:
  `63b0354c1083d4a761a0bc8207b794e0d7d5ad1132a8f689f3f71929a8ae3ebe`.
- Release signer SHA-256:
  `ce1438c8c50fe75f04f89ae2092631a46660480764cd52071cc5c08707462211`.
- Server image config/image id:
  `sha256:67eedd54c58810b075f6891386ea45449cab9eb98edd616adeb1815e6daa298c`
  (`linux/amd64`).
- Server image manifest:
  `sha256:fcc9cb74d68ce37ef4b0c8bb29d818b899fade69e6679b3debe9d91390f4265e`.

## NAS CD receipt

- `push-and-deploy.sh` created a fresh, closed-inventory package, verified its
  APK signer/image/platform/checksums locally and remotely, exported the live
  credentials directly into an off-repository age-encrypted backup, and
  replaced container `lezi-sync` while retaining the data bind.
- Stable remote package:
  `/tmp/lezi-sync-releases/lezi-sync-0.3.6-nas`.
- Encrypted backup:
  `/home/zhangtianshu/.config/lezi/backups/lezi-sync-0.3.6-credentials-20260803T173919Z-851878.age`
  with its `.sha256` sidecar.
- Post-deploy `/health` and `/ready` both reported `0.3.6`; independent Docker
  inspection reported `healthy` and the exact image id above.
- The LAN install page reported `0.3.6`, and its streamed APK SHA-256 exactly
  matched the local Release APK.
- TLS identity was reused. The deployment harness required exact pre/post raw
  certificate-file SHA-256 and SPKI equality. An independent TLS handshake also
  retained DER certificate SHA-256
  `9a34ba5bbb2442e45a7d9d056056b28503c4d67fbb3732e478a452d0a51d37f0`
  and SPKI SHA-256
  `bd07d8645ed3b7adead162eca454373aee4007b0a35aa7c62caf7d8ac0cb3215`.
