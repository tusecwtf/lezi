# Ticket 16 acceptance · candidate HEAD refresh · 2026-07-31

## Fixed points and devices

- **Current candidate product HEAD:** `ed99c762ecde214b279b9d64427e0ece3148fd21`
  (`fix(sync): harden trusted sync recovery paths`). This supersedes the prior
  product fixed point `a4dbe07` / evidence HEAD `5f9aa3c` after review-residuals
  (`1d6e839` / `ed99c76`) and self-hosted app-update work landed on the same line.
- Client A: `emulator-5554` (`lezi_api35`), API 35, x86_64, app 0.3.0 (versionCode 6).
- Client B: `emulator-5556` (`lezi_api35_b`), API 35, x86_64, app 0.3.0 (versionCode 6).
- Synthetic acceptance server: Docker `lezi-sync:0.3.0` on host `https://127.0.0.1:18765`
  (emulator alias `https://10.0.2.2:18765`), TLS data dir `/tmp/lezi-t16-accept-data`,
  container user `10001:10001`. **No live family data and no production NAS deploy.**

## Android repository and artifact gates (HEAD `ed99c76`)

- `./gradlew test lintDebug` — PASS (`BUILD SUCCESSFUL`, 1,317 actionable tasks).
- `./gradlew :app:assembleRelease --rerun-tasks` — PASS (679/679 executed); signature
  hook verified `app-release.apk`.
- Release APK: `app/build/outputs/apk/release/app-release.apk`, 5,536,437 bytes,
  SHA-256 `8ff39b3f04d668f5312d540a0527a25557e0e600d5493a2c66678d3d3986e960`.
- `apksigner verify --verbose --print-certs` — PASS; v2 and v3 true. Signer cert SHA-256:
  `ce1438c8c50fe75f04f89ae2092631a46660480764cd52071cc5c08707462211`.
- Streamed install on both AVDs succeeded. Pulled `base.apk` hashes on both devices were
  **byte-identical** to the Release artifact:
  `8ff39b3f04d668f5312d540a0527a25557e0e600d5493a2c66678d3d3986e960`.
- Launch smoke: both devices resumed `com.lezi.babylog/.MainActivity` Welcome with
  「连接家庭服务器」「扫描成员登录二维码」「离线模式」. Client B launched production
  `com.journeyapps.barcodescanner.CaptureActivity` from the scan action (camera permission
  granted).

### Packaging pin vs fixed commit (hygiene · fix r2)

- Working-tree `tools/lezi-sync/deploy/app-update.json` pins the **current** Release APK
  `8ff39b3f04d668f5312d540a0527a25557e0e600d5493a2c66678d3d3986e960` (verified against
  `app/build/outputs/apk/release/app-release.apk`).
- Clean commit `ed99c76` still has the **previous** pin
  `10215034bf49f589173877d5904f3b88f3bc6e447470d72b77c19d1151850e83`.
- **Commit requirement:** include this pin (and ticket-16 evidence) on the same HEAD that
  freezes the candidate so `package-nas.sh` is self-sufficient on a clean checkout.
  Until Commit, packaging success is **dirty-tree only** (already demonstrated with the
  working-tree pin). Do not claim clean-`ed99c76` packaging against APK `8ff39b3f…`.

## Rust, image, and NAS package gates (HEAD `ed99c76` + working-tree pin)

- `cargo fmt --all -- --check` — PASS.
- `cargo test --locked` — PASS: library + **116** HTTP API/current-wire tests + TLS black-box.
- `cargo clippy --all-targets --all-features -- -D warnings` — PASS.
- `./build-image.sh` — PASS. `lezi-sync:0.3.0` is `linux/amd64`, user `10001:10001`,
  image ID `sha256:8b451e4158ba48d7436ace06d7b1fcb7dd043a564098b8281a9c4f265fd82d34`.
- `LEZI_FORCE_PACKAGE=1 ./deploy/package-nas.sh` — PASS (with working-tree
  `app-update.json` pin). Package: `dist/lezi-sync-0.3.0-nas/`; `MANIFEST.json` records
  `git_sha=ed99c76` and app-update SHA matching the Release APK above.
- Image tar: `lezi-sync-0.3.0-linux-amd64.tar`, SHA-256
  `68cf6d54f45a4b02fc7be5a1c9b9041186faabc9fa7eff89bdd8fd61327e3370` (mode 0600).
- Runtime on synthetic bind: `/health` → version `0.3.0` + `atomic_bundle` /
  `record_membership_author`; `/ready` → `ok=true`; Docker health **healthy**;
  `lezi-sync healthcheck` exit 0. Loopback internal readiness path is present in the
  image (container listens on 8766 inside the netns; published probe used HTTPS 18765).

## Cross-client wire matrix (supporting — not dual-client E2E closeout)

Host black-box against `https://127.0.0.1:18765` with the data-dir CA (emulator origin in
QR payloads: `https://10.0.2.2:18765`). TOFU SPKI pin (Base64 SHA-256 of SPKI):

`lxIAoiP8+7kr9qInJ/wx0G1DfaVy+krJJ8EQN/3qlSg=`

Fingerprint (cert SHA-256):
`F4:EB:48:04:94:3C:73:49:58:84:1E:02:E3:BF:32:76:70:60:73:7D:62:C3:40:81:CE:A4:9C:8A:06:A3:6A:62`.

**Result: 31/31 checks PASS** in durable
[`api-matrix.json`](./api-matrix.json) (original 29 + fix-r2 Owner-add rows; hard-delete
row amended with full `membership_deleted` body; QR row points at tracker evidence, not
`/var/tmp`).

| Area | Wire result | Dual Android on APK `8ff39b3f…`? |
|------|-------------|----------------------------------|
| health/ready 0.3.0 | PASS | n/a (ops) |
| setup-status empty → configured | PASS | open (UI probe) |
| Owner create + second create fail-closed | PASS | open (UI create) |
| Wrong root secret rejected | PASS | open (UI) |
| Member request → approve-new → claim | PASS | open (dual UI) |
| **Owner add zero-device membership + QR grant (US-22)** | PASS (fix-r2) | open (dual UI) |
| Member cannot create login grant | PASS | open (UI ACL) |
| Bind existing → second device claim | PASS | open (dual UI) |
| Baby + 0/1/2/3 photo atomic bundles | PASS | open (dual UI + photos) |
| Second-device full history pull | PASS (one writer → pull) | open; also need **双向记录** |
| Refresh rotation + replay isolation | PASS | open (device sessions) |
| QR JSON encode/decode + grant claim + single-use | PASS; durable PNG under `evidence/16/` | open (US-17 **camera** path) |
| Server `device_removed` / `membership_deleted` anonymize / `family_deleted` | PASS (hard-delete body re-probed fix-r2) | open (**local** clear US-28/30/33/39) |
| Owner takeover | PASS | open (UI/device) |

**Not in the 31/31 matrix:** root password rotation (see Host security probes below).

Interpretation: wire matrix supports server contracts after residuals. Spec
「两个 Android client + 最终 TLS server」and Testing Decisions E2E are **not** closed by
this table alone. Prior dual-AVD UI on APK `10215034…` is **not** reusable for
`8ff39b3f…`.

### Host security probes (supporting only — outside matrix count)

- **Alternate cert / CA fail-closed (not US-06):** alternate self-signed server on `:18775`
  presented SPKI `HRoLkI7fQHMg/TkFGb6HlnzKD5i1kRTLogN82njU10o=` (≠ TOFU pin above). Curl
  with the original CA failed at TLS with no HTTP body. This shows wrong trust material
  fails closed at the **host CA** layer. It does **not** prove Android **pinned-SPKI**
  hard-block (US-06: zero secrets/sync after pin mismatch, no ignore-and-continue) on the
  current Release APK.
- **Supporting JVM (not Release E2E):** tree at `ed99c76` includes
  `TrustedEndpointTlsTest` / `TrustedEndpointTest` paths that map SPKI mismatch to
  `SetupProbeResult.Failed.CertificateChanged` and related handshake failures. These
  remain unit/integration evidence, not dual-device Release acceptance.
- **Root password rotation (wire, not a matrix row):** restart container on the same data
  bind with a new `LEZI_BOOTSTRAP_SECRET`. Prior Owner access → `401 Invalid or revoked
  token`. Ordinary member access remained valid (`200` pull after `generation_changed`
  recovery). Still needs client-visible convergence on the Release APK for checklist
  closeout. **Do not count this under the 31/31 durable matrix.**

### Fix-r2 matrix amendments (2026-07-31)

Re-probed on synthetic `lezi-t16-accept` (`https://127.0.0.1:18765`) with the live
container bootstrap secret:

1. **Owner add (US-22 supporting):** `POST /v1/family/members` → `201` with
   `membership_id`; subsequent `POST /v1/member/login-grants` → `201` grant; claim →
   session. Rows added to durable matrix.
2. **Hard-delete terminal code:** after `POST /v1/family/members/remove`, member
   `GET /v1/family/members` and `GET /v1/pull` both returned
   `401 {"code":"membership_deleted","detail":"This family membership was deleted"}`.
   Matrix row detail amended to match `device_removed` / `family_deleted` style.
3. **QR artifact:** `qr-encode-sample.png` + redacted `qr-encode-sample.json` under
   `evidence/16/` (no absolute `/var/tmp` path in durable matrix detail).

## Live Android camera QR (still open — US-17)

- Client B Release APK successfully opened ZXing `CaptureActivity` with prompt
  「扫描成员登录二维码」and held an active camera client (`dumpsys media.camera`).
- Emulator back camera remains `hw.camera.back=emulated`. Virtual-scene `poster.png`
  swap and a host-generated QR MP4 were prepared; independent `zbarimg` on CaptureActivity
  screenshots still returned no decode. No grant was claimed through the camera path.
- Host encode/decode + API claim is **supporting** only. Close US-17 only after current
  Release APK camera (or physical device) verify + claim.

## Prior dual-UI evidence (explicitly non-closing for this candidate)

Earlier work under `a4dbe07`/`5f9aa3c` exercised dual-AVD UI for System-PKI, self-signed
TOFU, create/approve/bind, photos, revoke, hard delete, family delete on signed APK
`10215034bf49f589173877d5904f3b88f3bc6e447470d72b77c19d1151850e83`. That APK is **not**
byte-identical to `8ff39b3f…`. Cite only as historical context.

## Remaining Must evidence (expanded — Spec E2E / checklist)

All of the following on **one** fixed candidate, **current** Release APK `8ff39b3f…`
(or a later rebuild whose hashes replace this fixed point), and final TLS server:

1. **Dual-client self-signed TOFU UI** — trust before secrets; setup-status routing.
2. **Dual-client System-PKI UI** — hostname/chain validation path (no TOFU dialog).
3. **Create family** on client A; **member request/approval** and **bind existing
   second device** across A/B.
4. **Owner add (US-22)** — admin creates zero-device membership in UI, then can QR.
5. **Full history + 双向记录** — each client authors at least one record (and exercise
   0–3 photo atomic bundles) and the peer pulls them.
6. **Member QR login (US-17)** — camera/physical scan → verify + claim (not host codec).
7. **Refresh rotation / replay isolation** observed from device sessions.
8. **Single-device revoke, membership hard-delete + anonymization, family delete** with
   **local** Room/Outbox/media/endpoint cleanup on affected clients (server reason codes
   alone are insufficient).
9. **Android SPKI mismatch hard-block (US-06)** on the Release APK — pin, rotate cert/key,
   assert zero secret/sync traffic and no ignore path (host curl CA fail is not enough).
10. Client-visible paths for non-owner root secret, owner takeover, and root rotation
    convergence (wire / host probes already supporting; root rotation is **not** a matrix
    row).

Out of synthetic scope for this ticket unless product ops requests it: production NAS
container replace (needs explicit maintenance-window confirmation per `AGENTS.md`).

Ticket 16 stays **partial**. Ticket 17 must remain **blocked** until every open Must
above is closed on one fixed candidate HEAD—do not start version bump on wire-only partial.

## Artifact summary (candidate)

| Artifact | Value |
|----------|--------|
| git HEAD | `ed99c762ecde214b279b9d64427e0ece3148fd21` |
| Release APK SHA-256 | `8ff39b3f04d668f5312d540a0527a25557e0e600d5493a2c66678d3d3986e960` |
| Image ID | `sha256:8b451e4158ba48d7436ace06d7b1fcb7dd043a564098b8281a9c4f265fd82d34` |
| Image tar SHA-256 | `68cf6d54f45a4b02fc7be5a1c9b9041186faabc9fa7eff89bdd8fd61327e3370` |
| Package | `dist/lezi-sync-0.3.0-nas/` (`git_sha=ed99c76`) |
| Wire matrix log | [`api-matrix.json`](./api-matrix.json) (31/31) |
| QR sample | [`qr-encode-sample.png`](./qr-encode-sample.png) + [`qr-encode-sample.json`](./qr-encode-sample.json) |
| Synthetic HTTPS | `https://127.0.0.1:18765` / `https://10.0.2.2:18765` |
| TOFU SPKI | `lxIAoiP8+7kr9qInJ/wx0G1DfaVy+krJJ8EQN/3qlSg=` |
| app-update pin | working tree `8ff39b3f…` (must land with candidate freeze commit; clean `ed99c76` still `10215034…`) |
