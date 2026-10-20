# iOS family transport and sync adapter: implementation plan

> Status: **design, not implemented or accepted**, 2026-10-19.
> This document plans the next native iOS integration slice. It does not authorize
> a server policy change, production enrollment, deployment, or merge of the iOS
> draft. Android 0.5.5 / versionCode 35 remains unchanged. The current frozen wire
> is 0.4.0, server schema 13, Android sync floor 21.

## 1. Authority and starting point

Read [iOS parity](ios-parity.md), [offline records](ios-offline-records.md),
[causal wire](contracts/causal-sync-wire.md),
[trusted endpoint](contracts/sync-trusted-endpoint.md), [platform](platform.md),
[ADR-0025](../adr/0025-ios-co-development.md), and the repository `AGENTS.md`.
The causal wire document owns closed shapes; this plan must not introduce another
wire dialect. Source route paths below are the actual implementation paths.

The native package has strict handshake/heartbeat/terminal-error decoders, a
bounded baby canonical/hash implementation, and offline SQLite care storage.
Those are not a family session, a complete pull decoder, production transport,
or durable frozen-mutation implementation. Local storage schema revisions are
not the server's schema 13 or payload document schema 2. Preserve existing
user data when adding joined-family state; do not repurpose an offline revision
as a causal `base_version`.

Apple validation is available through `.github/workflows/ios-core.yml`: package
tests, iOS SDK compilation, unsigned development-host build and Simulator UI
coverage. Existing green checkpoints cover their recorded source only. They do
not prove a future transport implementation or physical-device behavior. Lack of
Xcode on a particular development host does not block using this Apple CI path.
Signed installation, distribution and real iPhone acceptance remain separate gates.

## 2. Blocking release compatibility decision

### 2.1 What the current server actually does

Sources: `tools/lezi-sync/src/lib.rs` (`require_supported_client`,
`parse_client_version_code`, `ApiError::client_update_required`),
`tools/lezi-sync/src/handlers/app_update.rs` (`min_supported_if_verified`), and
`tools/lezi-sync/src/handlers/sync.rs`.

- The guard obtains `min_supported_version_code` from the verified Android
  APK/metadata pair. A positive decimal `X-Lezi-Client-Version-Code` is required
  when that floor exists. Missing, malformed or lower values fail.
- With verified floor 21, an honest iOS build number 1 cannot pass. Successful
  health, setup or session issuance does not change that result.
- Setup and identity/session handlers do not call this guard. Authenticated
  handshake, heartbeat, pull and authoritative sync paths do. Ordinary failures
  are HTTP 403 with `client_update_required`; causal commit translates this
  particular gate failure into HTTP 409 terminal `capability_mismatch`.
- Missing update-channel files clear the retained floor and fail open. An
  integrity/I/O error after a successful verification can retain the last-good
  floor. A never-verified channel can also fail open. An isolated test passing
  only because it lacks a verified APK channel is not release compatibility proof.

Do not report Android code 35 or an arbitrarily large number, remove the guard,
change the APK metadata to accommodate iOS, or insert `platform` into a frozen
JSON body. The Android APK channel is not an iOS update/install channel.

### 2.2 Options requiring review, neither approved here

1. **Explicit platform-neutral compatibility mapping on the unchanged server.**
   This needs a reviewed meaning for the value, a mapping for every iOS release,
   and correct behavior under future Android-backed floor changes. Current source
   has no separate neutral registry; calling the existing APK floor a generic
   protocol number does not establish such a policy.
2. **Additive server policy dispatch by explicit platform header.** A possible
   design preserves the entire current APK-backed path for absent/Android
   platform declarations, while an explicit iOS declaration uses a separately
   approved compatibility policy with its real build number. Unknown/malformed
   platforms and missing/unapproved iOS policy must not become a permissive
   fallback. Define supported wire generation, minimum iOS build, policy failure
   behavior and the actual iOS update/distribution path before implementation.

Option 2 can be designed without changing frozen handshake JSON, old Android
responses, or SQL schema 13. It still changes release/floor behavior and requires
`AGENTS.md` row E proof and a separately approved coordinated rollout. Header
names and policy storage are intentionally not frozen by this document.

A platform header is **self-declared compatibility information, not attestation**
of platform, signing identity or installed APK. It cannot prove an adversarial
caller runs iOS; the existing version header is also self-declared. Session and
ACL remain the authentication/authorization boundary. A new platform branch must
not silently become a no-check bypass of the current compatibility policy.

The adapter/codec work below can proceed while this decision is open. Production
family sync cannot be accepted on that basis, and this document commits to no
server edit or deployment.

## 3. Proposed implementation slices and exit criteria

### S1: origin, trust and anonymous setup

Implement independently testable origin and trust types plus an Apple transport
adapter. Match `sync/src/main/kotlin/com/lezi/babylog/sync/session/TrustedEndpoint.kt`:

- Normalize HTTPS origin; reject userinfo, non-root path, query, fragment and
  invalid port. Handle IPv6 and default port correctly. No production cleartext.
- Try system PKI. A self-signed trust candidate is obtained without sending
  application data or credentials; validate hostname, validity and self-signature.
- Show the fingerprint for explicit user approval before setup or root-password
  submission. Bind approved trust to the normalized origin.
- Pin SHA-256 of DER **SubjectPublicKeyInfo**, encoded as Base64. The displayed
  colon-delimited hexadecimal fingerprint is that digest, not the whole
  certificate digest. A raw public-key export must not be assumed to be SPKI.
- An existing pin mismatch hard-blocks. No “continue anyway”; forgetting and
  reconnecting is explicit. Candidate failure must not replace the old endpoint,
  session or local care data.
- After trust, anonymous `GET /health`, `GET /ready`, and `GET /v1/setup-status`
  can establish setup state. They carry no access/refresh/root credentials or
  family data. They are not a serial prerequisite to every ordinary sync round.

Setup response required fields are `protocol_version`, `capabilities`,
`family_state` (`empty|configured`). Its capabilities have **incremental**
semantics; unknown additions are ignored. Route to create versus join only after
trusted setup. Health is not family identity or synchronization success.

Exit: synthetic PKI/TOFU trust fixtures, negative origin cases, wrong SAN,
expiry, first-trust rejection/approval, pin change, same-key renewal, redirect
and credential-leak tests. Use only an isolated test certificate/data root.

### S2: device session and crash-safe credentials

Implement the request/response adapter and a single-owner session coordinator.
These endpoints mutate identity state: “session-only” is **not read-only**.
Test with a synthetic family first; do not use a real family to prove login logic.

Actual routes and closed request bodies (`tools/lezi-sync/src/model.rs` and
`tools/lezi-sync/src/handlers/identity.rs`):

| Method and path | Exact body keys | Credential / note |
|---|---|---|
| POST `/v1/family/create` | `create_request_id, display_name, device_name, family_name` | `X-Lezi-Bootstrap-Secret` |
| POST `/v1/owner/login` | `login_request_id, device_name` | `X-Lezi-Bootstrap-Secret` |
| POST `/v1/owner/takeover` | `login_request_id, device_name` | Explicit dangerous takeover, not default login |
| POST `/v1/member/requests` | `display_name, device_name` | Creates a pending request |
| POST `/v1/member/requests/status` | `pending_secret` | Secret-bearing status read, no family access |
| POST `/v1/member/requests/cancel` | `pending_secret` | Cancels the request |
| POST `/v1/member/requests/claim` | `pending_secret` | Claims device credentials |
| GET `/v1/member/requests` | No body | Owner Bearer; opt in with `X-Lezi-Member-Request-View: open-v1` for open requests |
| POST `/v1/member/requests/{request_id}/approve-new` | Empty object | Owner Bearer |
| POST `/v1/member/requests/{request_id}/bind-existing` | `membership_id` | Owner Bearer; never bind merely by matching name |
| POST `/v1/member/requests/{request_id}/reject` | Empty object | Owner Bearer |
| POST `/v1/member/login-grants` | `membership_id` | Owner Bearer; grants are single-use and short-lived |
| POST `/v1/member/login-grants/claim` | `grant, device_name` | Ordinary-member enrollment, not Owner QR login |
| POST `/v1/session/refresh` | `refresh_token` | Stable `X-Lezi-Refresh-Request-Id` for the rotation attempt |

The bootstrap/session success fields are `family_id`, `membership_id`,
`device_id`, `session_id`, `role`, `access_token`, `access_expires_at`,
`refresh_token`, `generation`, `family_name`. Parse each endpoint against its
actual response, not a generic permissive JSON dictionary. Public request IDs
are not claim credentials. Root passwords never become daily Bearer tokens or
persistent client state.

Keychain design requirements:

- Keep access tokens in memory. Store refresh and pending secrets with explicit
  origin/session binding, device-only accessibility and no iCloud synchronization.
  Never persist the administrator root password.
- Serialize refresh. Persist the current refresh token and pending refresh
  request ID before network submission; retry that same logical rotation with
  the same ID. The header accepts 16–128 URL-safe alphanumeric/`-`/`_` characters.
- Commit the replacement credential record before publishing the new access
  token. Define crash recovery between Keychain and SQLite explicitly; do not
  claim multiple Keychain items and a SQLite transaction are one atomic write.
- Refresh once and retry once on eligible access failure. Invalid refresh or
  replay clears invalid credentials while retaining local facts and endpoint.
  Generic 401, floor 403, timeout and 5xx are not deletion instructions.
- Only explicit `device_removed`, `membership_deleted`, `family_deleted` or a
  confirmed active logout/delete flow invokes the corresponding local cleanup.

Exit: create/login/approval/claim, refresh response loss, process death around
credential rotation, replay, one-device revocation, and preserved offline data.
No automatic retry of non-idempotent enrollment without a supported stable identity.

### S3: authenticated read-only replica

This slice depends on S1/S2 and a demonstrated compatibility policy. Do not
publish local mutations or overwrite a user's existing offline store just to
exercise it. Initially use a separate synthetic test replica.

1. `POST /v1/sync/handshake`: Bearer plus the approved version declaration;
   exact body `{protocol_version:1, required_capabilities:["causal_sync_v2"]}`.
   The capability set is exactly that one item; duplicates/additions fail.
2. Validate readiness, protocol, limits and principal/session identity before
   directory, pull or commit. Closed success top-level keys are
   `protocol_version, server_version, ready, capabilities, principal,
   directory_generation, limits, compression, retry_hints`.
   Principal is `{membership_id,device_id,role}`. Follow the wire contract for
   every nested closed key set. Capability mismatch is 409, not-ready is 503,
   with `{status:"rejected",error:{code:...,retryable:false}}`.
3. Fetch `GET /v1/family/members` when directory generation differs. Require its
   generation to match the handshake before atomically replacing the directory.
4. `GET /v1/sync/heartbeat` has no body and a closed response
   `{generation,head_rev,directory_generation}`. Use it only when setup advertises
   `sync_heartbeat_v1`; never add this capability to the frozen handshake.
   Compare watermarks using inequality, including rollback, not only increase.
5. `GET /v1/pull?cursor=...&generation=...&page_index=...` requires all three
   query fields. The base closed response is
   `{entities,cursor,generation,page_index,has_more,family_name}`.
   Opting into `include_live_census=true` permits only one additional top-level
   key, `live_census`; live keys require their separate opt-in. Start with base
   envelope proof, then cover the opted-in cases without widening the decoder.
6. Authenticated `GET /v1/media/{client_uuid}` materialization is tied to validated
   references, digest checks and owned staging files. Partial downloads must not
   become visible care facts.

Pull must understand the current family contract, including all seven entity
classes, full payloads and references; a baby-only parser is not a joined-family
reader. Apply complete validated pages transactionally with safe checkpointing,
reference deferral and bounded diagnostics per wire §1.4. Do not skip unknown
shapes or advance the cursor past unresolved facts outside that contract.

Transport must reject unsafe redirects, isolate anonymous and authenticated
requests, avoid persistent cookie/cache/credential leaks, and cancel when leaving
foreground. Preserve the single bounded retry owner and exact deadlines in wire
§1.3. URLSession automatic decoding is not proof of the dual pull budget: verify
encoded <=9 MiB, decoded <=8 MiB, <=200 entities/page and <=500 pages/round.
An initial isolated `Accept-Encoding: identity` test can prove basic reads;
ordinary gzip support still needs bounded decompression, corrupt/truncated gzip
and encoding-negotiation tests before acceptance.

Exit: full synthetic read round through production Swift transport/storage,
restart-safe checkpoint and media materialization, malformed-page rejection,
no background requests and correct session/floor/transport errors. A heartbeat
or successful handshake alone does not satisfy this exit.

### S4: durable bidirectional sync

Only after S3, implement complete local-to-wire projections and durable causal
state: `base_version`, stable `mutation_id`, canonical request hash, a unique
immutable envelope and media spool/receipt binding for each frozen mutation.
Session identity provides provenance; do not send the offline observer UUID as a
membership stamp. Define offline-baby binding/local retained content before
joining existing care data to a family.

Use `PUT /v1/causal/media/{client_uuid}` with `X-Lezi-Media-Sha256` for staged
preimages and `POST /v1/causal/commit` for authoritative commit. Follow wire §3–4
and `tools/lezi-sync/src/handlers/media.rs`; do not revive retired ordinary push/media upload or a
reconcile fallback. Mutation keys are `mutation_id,base_version,entity_type,
client_uuid,root,media,deleted`; no media bytes are embedded in the mutation.

Validate terminal `request_hash` against frozen bytes and atomically settle the
matching local revision. `accepted|merged|branched` are durable terminal results;
`branched` is not an endless transport retry. Retries reuse identity/content and
never reread mutable source media. Add conflict snapshot pagination, choice-only
resolution, withdrawal, source relations, sleep/wake and plan fulfillment before
claiming full product sync parity.

## 4. Canonical boundaries to resolve before publishing

### UUID text versus local identity

`ios/Sources/LeziStorage/OfflineSchema.swift` and `OfflineStore.swift` use native
`UUID.uuidString` in local storage. This commonly has uppercase text, while
`ios/Sources/LeziCore/Contracts/BabyCanonicalRoot.swift` accepts only lowercase canonical
UUIDs and Android `sync/src/main/kotlin/com/lezi/babylog/sync/conflict/ConflictSnapshotValidation.kt` makes the same
canonical comparison. Rust accepting some broader UUID spellings at ingress is
not evidence that every hash, reference, snapshot and sort is case-insensitive.

Provide a separate canonical wire projection for new root IDs **and references**.
Do not change only one side of local foreign-key relationships. The ordinary
closed-sleep UUIDv5 helper parses the namespace UUID, whereas the legacy migration
helper's name input preserves original text exactly. Follow
`ios/Sources/LeziCore/Domain/SleepWakeIdentity.swift`; do not globally lowercase historical
migration inputs. No current iOS network producer has demonstrated an actual
case-related production defect; this is a pre-integration boundary.

### Record storage JSON is not a wire root

`ios/Sources/LeziCore/Domain/RecordPayloadCodec.swift` explicitly rebuilds known storage JSON,
retains opaque raw text, and can encode a local custom row ID. Its `rawJSON` is
not a canonical transport blob. Wire `payload_json` is an **object**, not a JSON
string containing that text.

Mirror Android `sync/src/main/kotlin/com/lezi/babylog/sync/engine/SyncWireMapper.kt`: typed decode/validate/re-encode,
closed current payload validation, remove local `photos`/`custom_item_id`, and
use portable custom UUID references. Opaque local documents must remain locally
preserved instead of bypassing the wire allowlist. Separate local storage codec,
wire projection and canonical hashing APIs.

`BabyMutationCanonicalHash` is deliberately bounded to a baby integer/null/
string/bool projection. It rejects decimal/exponent numbers and negative zero;
it is not an arbitrary-record canonicalizer. Add cross-language fixtures for
actual fractional producer values, Unicode, escaping and number spelling before
extending it. Hash canonical content, not raw input JSON text.

### Record numbers: typed spelling and request-hash boundary

Android `SyncWireMapper.requireStrictCurrentPayload` compares the decoded and
re-encoded `JsonObject` with the received object. In the pinned
kotlinx.serialization 1.6.3, numeric `JsonLiteral` equality compares literal
content and string/non-string type, not mathematical equivalence. Temperature
uses `Double` (`37.0`); measurement uses an integer for integral values (`65`),
otherwise `Double`. Thus `37` is not the canonical temperature spelling, and
`65.0` is not the canonical measurement spelling.

This check is used both by outbound `localPayloadForWire` and inbound
`localPayloadFromWire`; the latter adds a local custom ID when required, then
checks equality **before** returning re-encoded local JSON. `ReplicaSyncEngine`
uses it when materializing pulled records/plans, and `CausalSettlement` uses it
for stable settlement. Conflict snapshots use `requireCurrentTransportPayload`.
Do not assume Android pull silently repairs arbitrary numeric spellings. Nor
does a difference in the originally submitted spelling establish a pull failure:
the server can normalize the stored stable payload before returning it.

Rust `store/causal.rs::evaluate_unit` computes `mutation_content_hash` from the
already parsed `serde_json::Value` **before** `validate_mutation_content` calls
`model.rs::validate_causal_root` and typed-payload normalization. The latter
converts temperature to a floating number and integral measurements to integers.
That later normalization does not change the request hash. The lockfile pins
serde_json 1.0.151 and zmij 1.0.23; the project does not request
`arbitrary_precision` or `float_roundtrip`. Default serde numeric parsing and
serialization do not preserve arbitrary input spelling. Confirm the resolved
feature graph with the actual Rust build; do not infer it from Cargo.lock alone.

A synthetic Java 21 / Swift 6.4.1 Linux formatting probe established:

| Value | Java `Double.toString` | Swift `String(Double)` | Swift `JSONEncoder(Double)` |
|---|---|---|---|
| 37 | `37.0` | `37.0` | `37` |
| 65 | `65.0` | `65.0` | `65` |
| 0.000001 | `1.0E-6` | `1e-06` | `1e-06` |
| Negative zero | `-0.0` | `-0.0` | `-0` |

The typed measurement codecs separately turn integral 65 into `65`. The current
Swift storage codec uses `String(Double)` for numbers and JSONEncoder for
strings, so replacing it with direct Double JSONEncoder output is not neutral.
These were formatting probes, **not** execution of Kotlin serialization, Rust
serde, the full mapper, or a mixed-client sync test. No three-runtime hash parity
is established by this table. The integer-only existing no-media mutation golden
does not cover fractional records.

Reachability must be stated narrowly. Ordinary temperatures are within 34–43
and typically use short non-exponent decimals. Quick-record `DecimalField`
allows digits and one dot, truncated to seven characters: `.000001` fits and
positive height passes the growth range check, although it is not a realistic
baby measurement. The growth screen also parses its draft with `toDoubleOrNull`.
Weight's kilogram-to-gram rounding is a different producer path. Negative zero
is outside valid temperature/growth ranges. Exponent overflow and subnormal
fixtures are defensive boundaries, not evidence of a current family-data defect.

Before enabling fractional record publication, require shared **complete
mutation** fixtures executed by the pinned Kotlin, Rust and Swift runtimes.
Record each input, canonical request bytes/hash, Rust-normalized stable payload,
and Android strict admission result. Cover `37.0`/`37`, `65`/`65.0`, ordinary
fractions, `0.001`, `0.000001`/`1e-6`/`1.0E-6`, `1e0`, negative zero, quoted
numbers, range rejection and precision boundaries. Hash the same frozen
projection that is sent; never hash first and normalize afterward. Unsupported
numeric cases must block publication without rounding or losing local data.
A float-formatting patch or exponent case conversion alone cannot establish
full compatibility. Any remedy changing frozen hash semantics or floor policy
requires separate review and the corresponding gates; this plan changes neither
Android nor the server/protocol.

### Nullable media dimensions: confirmed difference, bounded reachability

Android `sync/src/main/kotlin/com/lezi/babylog/sync/engine/CausalSettlement.kt` omits nil width/height in its hash
projection. Rust `tools/lezi-sync/src/store/causal_merge.rs::CausalMediaItem` serializes those Options
as explicit null, without `skip_serializing_if`. This is a real static hash
mismatch for nil dimensions. Wire §4.6 requires both keys, with nullable values;
the Swift baby implementation follows that explicit-null shape.

It is **not** proof that ordinary Android photo uploads currently fail:
`sync/src/main/kotlin/com/lezi/babylog/sync/media/SyncMediaFileStore.kt::prepareUpload` requires decodable supported
images with positive dimensions; `prepareDecodedUpload` produces
`scaled.width/height`; `ImmutableMediaSpool.kt` retains those values and
`CausalSettlement.kt::toCausalMediaItem` forwards them. Normal producer media
therefore has positive dimensions. Test nil-dimension fixtures and any historical
or special recovery producer separately; demonstrate actual reachability before
calling this an existing user-facing defect. Do not modify Android 35 or the
frozen wire opportunistically to eliminate the difference.

### Nullable birthday: accepted scaffold range is not producer parity

Swift baby canonical and Android snapshot models can express nil birthday, while
Rust `tools/lezi-sync/src/model.rs::validate_baby` calls `date(..., false)` and rejects null.
Normal Android `core/database/src/main/kotlin/com/lezi/babylog/core/database/Entities.kt::BabyEntity.birthdayEpochDay` is a
non-null Long, and `SyncWireMapper.baby` emits
`LocalDate.ofEpochDay(...).toString()`. That producer does not emit null.

Require a real valid birthday in the iOS publishable baby projection. Test null
as a negative/contract-boundary fixture, and test extreme dates against the
intersection of all three implementations. Do not claim an existing ordinary
Android birthday bug from this static discrepancy.

## 5. Required local evidence

This plan is documentation-only: `AGENTS.md` row A requires no runtime tests for
this file itself. Implementation must use the first matching proof row. Any
closed-key/handshake/capability/schema/floor or compatibility-policy change uses
row E, even if existing JSON bytes or SQL schema remain unchanged.

### Row E: do not omit existing Android/server gates

Run from the repository root, using this tree's binary:

```bash
(cd tools/lezi-sync && \
  cargo fmt --all -- --check && \
  cargo test --locked && \
  cargo clippy --all-targets --all-features -- -D warnings && \
  cargo test --locked --test conflict_v2_contract_fixture)

./gradlew :sync:testDebugUnitTest \
  --tests com.lezi.babylog.sync.backend.ConflictV2GoldenCorpusTest \
  --tests com.lezi.babylog.sync.conflict.ConflictSnapshotParserCorpusTest

(cd tools/lezi-sync && cargo build -p lezi-sync)
export LEZI_SYNC_BIN="$PWD/tools/lezi-sync/target/debug/lezi-sync"
./gradlew :sync:testDebugUnitTest \
  --tests com.lezi.babylog.sync.RealServerQuietRoundSmokeTest \
  --tests com.lezi.babylog.sync.media.RealServerMediaReceiptFaultSeamTest
./gradlew :domain:testDebugUnitTest \
  --tests 'com.lezi.babylog.domain.CareLogRealServerSeam*'
```

Prerequisites include JDK 21, openssl, curl and sqlite3. Missing binary or harness
prerequisites are a local blocker to repair, not a reason to test on the family
server. Never set production NAS/data-root variables for this harness. Breaking
wire also requires the platform §4.2.1 coordinated-floor procedure; this plan
does not authorize a break.

### Mixed Android/iOS acceptance, additional to row E

Use a fresh `mktemp` data root, isolated port, synthetic family and generated test
certificate. Pin Rust, Android and Swift revisions/builds. Android must be the
real current implementation/harness and Swift must use production adapter and
storage; a Swift fake named Android is not mixed-client evidence. Add a third
independent session for N-way cases.

1. Android Owner → iOS member and iOS Owner → Android member, with honest approved
   client identities. Test verified APK floor 21, raised floor, absent channel,
   last-good retention, unapproved iOS build and unknown platform; existing
   Android behavior must remain unchanged.
2. Every entity/payload/media in both directions; compare stable IDs, references,
   canonical bytes/digests, provenance and aggregates. Include ordinary positive
   media dimensions and non-null birthday separately from negative boundaries.
3. Offline create/edit/delete, both reconnect orders, same-UUID three-way edits,
   delete/edit, stale resolution, source relations, two-caregiver sleep/wake and
   same-plan fulfillment. Retain all required facts and achieve quiet pending=0.
4. Disconnect or kill after upload, commit acceptance, page persistence and before
   acknowledgment. Replay identities and receipts remain stable; no partial
   bundle becomes visible and no checkpoint skips uncommitted data.
5. Refresh/rotation/replay/revocation and endpoint/generation changes. Other devices
   stay valid; generic authentication/network failures preserve care data.
6. TLS candidate rejection/approval, wrong SAN, expiry, key mismatch, redirects,
   local-network denial/recovery and foreground cancellation. Certificate mutation
   never targets the family NAS, its container or its TLS/data bind.
7. Owner restore into another empty isolated service, kill/resume, then join both
   platforms and compare complete data/media; nonempty target rejects before
   destructive mutation. Restore is not part of the initial read-only slice.

Run portable Swift tests and extend the existing Apple CI with actual transport,
Keychain/SDK and real-server evidence. Record commands, toolchains, revisions,
exit codes and artifact links; prior green runs do not cover later code.
Physical iPhone tests must additionally cover signed installation/update without
loss, local-network permission, Keychain availability while locked, force quit,
suspension/cancellation, certificate mismatch and a real mixed-client family.

## 6. Decisions and operational boundaries

Independent next deliverables are S1/S2 adapter types and fixtures, S3 read codecs
and isolated harness, plus canonical differential tests. Do not expose a partial
joined-family writer in the development host.

Before production enrollment, obtain the reviewed release-policy decision,
complete family/local-data binding and full reader/writer acceptance, and satisfy
signing/distribution and physical-device gates. No real NAS values, family data,
credentials or certificate private keys belong in fixtures, logs or artifacts.

No production deployment, container replacement, TLS rotation, schema migration,
APK-floor change or public-VPS work is authorized here. Any later operational
step must follow the repository runbook and its separate confirmation window.
