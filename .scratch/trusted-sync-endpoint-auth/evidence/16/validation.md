# Ticket 16 partial fixed-point acceptance · 2026-07-31

## Fixed points and devices

- Product code HEAD: `a4dbe07143d7dc51ac0e24111653902866adf4ec`.
- Evidence/artifact HEAD: `5f9aa3cc13a4ace207d4f15946ed80559705563e`; this adds only a
  deterministic terminal-cleanup test wait and does not change packaged production sources.
- Client A: `emulator-5554`, API 35, Android SDK x86_64, app 0.3.0 (versionCode 6).
- Client B: `emulator-5556`, API 35, Android SDK x86_64, app 0.3.0 (versionCode 6).
- Server acceptance used local `linux/amd64` Docker containers and synthetic families only. No
  real family data, production secret, or live NAS was used.

## Android repository and artifact gates

- `./gradlew test lintDebug` — PASS at `5f9aa3c` (`BUILD SUCCESSFUL`; 1,317 actionable
  tasks). The first full run exposed a test-only race: it observed the deliberately safe
  session-clear-before-marker-clear order too early. Commit `5f9aa3c` waits for the durable marker's
  completion; focused Debug/Release tests and the full gate then passed.
- `./gradlew connectedDebugAndroidTest` — PASS on `emulator-5554` (API 35, x86_64): 93 tests,
  zero failures/errors/skips. The executed suites were designsystem=7, core database=6,
  log=45, family=21, and settings=14; the remaining Android-test modules reported zero tests.
  Gradle completed in 2m52s with 936 actionable tasks (108 executed, 13 from cache, 815 up-to-date).
- `./gradlew :app:assembleRelease --rerun-tasks` — PASS (`679/679` tasks executed); the build's
  signature hook also passed.
- Release APK: `app/build/outputs/apk/release/app-release.apk`, 5,519,958 bytes, SHA-256
  `10215034bf49f589173877d5904f3b88f3bc6e447470d72b77c19d1151850e83`.
- `apksigner verify --verbose --print-certs` — PASS; v2 and v3 are true. Signer certificate SHA-256:
  `ce1438c8c50fe75f04f89ae2092631a46660480764cd52071cc5c08707462211`.
- Final APK streamed installation on both Client A and Client B passed. Launch returned the welcome
  route with Connect, scan-member-QR, and Offline actions; both installed packages reported
  versionName 0.3.0, versionCode 6, minSdk 26, targetSdk 35.
- An isolated `a4dbe07` rebuild with the same signing inputs also produced 5,519,958 bytes, but SHA-256
  `8129d1faf762da69c2fa2bcb165808daaee26c54219df8d1971c06ebaadfac92`, so it is not byte-identical
  to the final APK. All 1,539 non-provenance/signature entries were identical; the only extracted
  differences among 1,543 entries were `META-INF/version-control-info.textproto`, `MANIFEST.MF`,
  `CERT.SF`, and `CERT.RSA`. In particular, `classes.dex` SHA-256 was
  `7128648bd4c2eedde4164edf810bdf01708318bb64915841a1fdd1ba3ef89542` and `resources.arsc` was
  `ea6b99cec3dde50eca11b24c967fcd843beec904914b31c4ed6aa6244510df14` in both APKs. The isolated
  worktree emitted `NO_VALID_GIT_FOUND`, while the final APK records revision `5f9aa3c`; the resulting
  provenance and signature differences mean payload equivalence cannot substitute for testing the
  exact final signed bytes.

## Rust, image, and NAS package gates

- `cargo fmt --all -- --check` — PASS.
- `cargo test --locked` — PASS: 38 library/store tests, 106 HTTP API/current-wire tests, one TLS
  black-box test, and zero doc tests. The TLS test required loopback socket permission; the same
  command passed after leaving the restricted network sandbox.
- `cargo clippy --all-targets --all-features -- -D warnings` — PASS.
- `./build-image.sh` — PASS. `lezi-sync:0.3.0` is `linux/amd64`, user `10001:10001`, image ID
  `sha256:10773e9a246fa5a3a2ef8554034984d7bacece90626cb311605699100f6e55e6`.
- `LEZI_FORCE_PACKAGE=1 ./deploy/package-nas.sh` — PASS. Package:
  `dist/lezi-sync-0.3.0-nas/`; `MANIFEST.json` records `git_sha=5f9aa3c` and all entries in
  `SHA256SUMS` verified.
- Image tar: `lezi-sync-0.3.0-linux-amd64.tar`, 34,975,232 bytes, mode 0600, owner
  `zhangtianshu:zhangtianshu`, SHA-256
  `b8e6527a873895d7ac6e84171d5e6d3447862335ed5155ac4ede93c4877b326f`. `docker load` succeeded
  and restored the same image ID/platform/user.
- Final-image runtime: with the host data directory owned by uid/gid 10001, `/health` returned
  version 0.3.0 plus `atomic_bundle` and `record_membership_author`; `/ready` returned `ok=true`;
  the internal binary healthcheck and Docker health were healthy. `/data` became 0700 and the DB
  and server secret 0600, all owned by 10001:10001.
- Permission negatives: a root-owned 0777 bind failed closed because uid10001 could not harden
  `/data`; this matches the deploy requirement to chown the directory to 10001. A read-only bind
  still exited 1 with SQLite unable to open, even with permission-hardening skip enabled.
- No production NAS deployment was run: the runbook requires a confirmed maintenance window.

## Cross-client and security acceptance during candidate stabilization

- System-PKI trust used `https://cachyos.tail8a083b.ts.net:38765` over the host's private Tailscale
  network. The temporary certificate was issued by Let's Encrypt YE2 for that DNS name and was valid
  from 2026-07-31 through 2026-10-29; the port was not exposed with Funnel or to the public internet.
  Both final-APK clients connected without a TOFU/fingerprint confirmation. Client A created the
  synthetic `T16Pki` family and baby `年年`; Client B requested membership, Client A approved it,
  and Client B claimed the session and recovered the baby. Client A then confirmed a synthetic
  12:49 urine record, which Client B fetched and rendered as the same one-record history.
- Client A permanently deleted `T16Pki`. Both final-APK clients returned to Welcome after the member
  reconnected. A post-delete database snapshot had zero families, memberships, devices, device
  sessions, entities, bundles, bundle-media rows, and media publications; SQLite integrity was `ok`.
- Self-signed trust used `https://10.0.2.2:18765`. The accepted SPKI pin was
  `qZt5MCIaAn+NQoUXERn3kh9Xa0A4ywknHRBd+Dlphk8=`. Owner create, baby recovery, member request and
  Owner approval completed across both AVDs.
- Existing-member second-device binding began from cursor zero and recovered full history.
  Synthetic records exercised 0, 1, 2, and 3 photos; the final family had five records and six
  media, with exact cross-client attachment semantics.
- Access expiry/refresh rotation passed. Replaying the previous refresh credential terminated only
  the presenting device. Single-device revoke, current-client terminal cleanup, member hard delete,
  retained-fact author anonymization, and cross-role access negatives passed.
- QR payload encoding and image decoding round-tripped with `qrencode`/`zbar`; grant claim and replay
  were verified through the API. A live Android camera scan was not run.
- Owner add/takeover, wrong root secret, root rotation, non-Owner root submission, and cross-member
  privilege attempts failed or converged as specified. Same-family takeover retained five records
  and all clean publication receipts.
- SPKI mismatch used an alternate certificate/key. Android reported a sync error; the TLS server saw
  only `certificate unknown` alerts and no HTTP request. Restoring the original certificate recovered
  `/health` and `/ready`.
- Restarting the server changed generation. A pre-fix Release requeued six already-published media
  after full resync. Commit `a4dbe07` makes equal authoritative media acknowledge the existing local
  bytes while keeping content edits protected. On its signed Release, generation changed from
  `nSOr...` to `r3vr...`; records=5/media=6/babies=1 were all clean, outbox=0, all six media receipts
  remained, and SQLite integrity was `ok`.
- Owner permanently deleted synthetic family `T16Family`. Client A returned to Welcome; its local
  family, membership, baby, record, media, plan, outbox, and pending-cleanup counts became zero and
  its media directory was empty. A surviving member's access and refresh both returned
  `401 family_deleted`. Server family, membership, device, and entity counts became zero; integrity
  remained `ok`. The synthetic family is not recoverable from the app or server.
- Fresh-only was preserved: an old schema database failed closed and a fresh database started; no
  Android migration was introduced.

## Remaining Must evidence

- Both clients were emulators, not physical phones, and no live Android camera QR scan passed. A
  final-APK `CaptureActivity` was exercised with Emulator 37.2 `imagefile:` and `videofile:` camera
  inputs, including padded, repositioned, and lossless tiled QR frames. The AVD camera backend
  consistently cropped or displaced sensor rows; independent `zbar` decoding also failed on the
  actual preview screenshots, and no grant was claimed. The QR payload/image round-trip and API
  grant lifecycle therefore still do not prove the app's camera-to-claim path.
- Candidate stabilization used several signed Release rebuilds. Affected reauth, generation-reset,
  SPKI mismatch, deletion, repository, artifact, and final welcome smoke gates were repeated after
  their fixes, but the complete two-client matrix was not replayed from zero against the single final
  APK SHA-256 above. The isolated fixed-point rebuild proves identical production code/resources,
  but its provenance and signature bytes differ and therefore do not close this exact-artifact gap.
- Physical network switching and live NAS replacement were not run.

Ticket 16 therefore remains open. Ticket 17 (0.3.1 upgrade) must not start until the missing Must
evidence is supplied and the complete matrix is accepted on one fixed candidate.
