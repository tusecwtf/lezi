# LEZI iOS foundation

This is an additive Swift package beside the current Android app and Rust LAN
service. It is **not a complete iOS product, signed device app or sync client**. It
contains no backend fork, network calls, account bootstrap, deployment tooling,
or Android-version impersonation.
`App/` supplies an unsigned Simulator-only development host for the bounded
offline baby/pee flow. Its Apple build and launch evidence are separate from
the portable package tests; no production distribution is configured.

## Toolchain and platforms

- Swift 6.0 language/tools minimum. SHA-256 uses Apple's CryptoKit on Apple
  platforms and the official `apple/swift-crypto` package on Linux. The package
  and its ASN.1 dependency are pinned to Swift 6.0-compatible releases in the
  manifest and resolved lockfile; no custom cryptographic implementation is used.
- iOS 17 minimum is an initial proposal for the SwiftUI implementation, not a
  verified product/device support decision. macOS 14 supports development tests.
- `LeziCore` is Foundation-only and testable on Linux. `LeziUI` separates portable
  presentation rules from Apple-only SwiftUI views.

Run from the checkout root:

```sh
swift test --package-path ios --force-resolved-versions
```

Tests read `config/conflict-v2-golden.json` from the same checkout; they never
fetch or copy a second corpus. Keep `ios/` beside `config/` when running tests.
A Linux pass does not prove Apple SDK compilation, simulator behavior, signing,
accessibility, device behavior, or end-to-end interoperability.
The storage tests require SQLite development headers and linkage on Linux
(`libsqlite3-dev` on Debian/Ubuntu); Apple SDKs supply the platform SQLite API.

## Implemented contract slice

Authoritative references are
[`causal-sync-wire.md`](../docs/spec/contracts/causal-sync-wire.md),
[`data-model.md`](../docs/spec/contracts/data-model.md), and the shared
[golden corpus/schema](../config/conflict-v2-golden.schema.json).
Implementation was checked against Android `HttpSyncBackend` and Rust
`handlers/sync.rs` in the current tree.

- A fixed authenticated handshake request: protocol `1`, exactly
  `causal_sync_v2`. No membership/device identity is supplied by this request.
- Typed successful handshake with mandatory expected-session principal binding.
  Exact object key sets, ready/protocol checks, capability/compression uniqueness,
  negotiated bounded limits, lowercase directory digest and retry-hint validation
  follow the current Android parser. Session membership, device and role must all
  match before the result can be obtained.
- Typed terminal rejection envelopes and handshake HTTP/code pairing
  (`409/capability_mismatch`, `503/not_ready`). Other authentication failures
  need the future session/transport implementation, not this decoder.
- Typed foreground heartbeat receipt with lossless signed 64-bit revision.
  Receipt equality allows detecting a changed/recovered lower head; no heartbeat
  scheduling or tip-skip algorithm is implemented.
- An internal bounded JSON parser retains number tokens, so decimals, exponent
  notation, strings, booleans and overflow cannot masquerade as integer fields.
  Duplicate object keys (including escaped aliases) fail closed. This is an
  intentional ambiguity defense stricter than parsers that keep the last key.
  Errors do not echo received payloads or identity values.

The request capability is byte-exact, like Rust. Response capability/compression
strings and nonblank fields follow Android's trimming behavior. Setup-status
capabilities are additive and **must not** be sent as extra handshake capabilities.
`server_version` is a release label, not a substitute for contract negotiation.
No Android `versionCode` header or minimum-version-gate bypass is implemented.

## Proof scope and next work

Behavior tests cover successful and rejected handshake shapes, all nested closed
objects, missing fields, capabilities, limits, principal mismatch, integer
fidelity, malformed JSON, duplicate keys and terminal HTTP mapping. The shared
corpus is consumed for all 19 conflict-v2 terminal error examples and vocabulary,
plus the explicit-null/missing-key/unknown-key baby-root shape cases. Those three
shape cases exercise parser mechanics; **they do not implement or prove baby
canonicalization**. Snapshot, choice resolution, commit replay and all other
corpus scenarios remain unimplemented and are not silently counted as passing.

### Subsequent bounded slices

- `RecordPayloadCodec` implements Android's **local** schema-2 payload document
  rules for all 28 record types, including opaque preservation of unsupported
  documents and a local-only custom row ID. Its coercions follow the pinned
  Android serializer and must never be used for strict causal wire validation.
  Known floating output is semantic JSON, not JVM byte-canonical output.
  Nonstandard escaped controls, unpaired surrogates and over-deep documents stay
  opaque rather than silently changing their typed contents.
- `BabyCanonicalTests` adds real semantic decoder coverage for the first three
  baby corpus cases. This is distinct from the older parser-mechanics tests
  above. The public canonical hash API is limited to validated baby mutations;
  it does not establish arbitrary record/floating-point canonicalization.
- Mutation and stamped stable baby roots have separate exact key sets. Hashing
  preserves request values rather than applying server ingress normalization.
  Explicit-null media dimensions follow the frozen wire/server representation.
  Negative-zero integer tokens are deliberately rejected at this boundary until
  their cross-language pre-normalization hash semantics are proven.
- `LeziStorage` is a real file-backed SQLite **offline-only** vertical slice.
  Verified checkpoint `10016b6a` includes native schema **3**, with atomic,
  data-preserving migration from schema 1 or 2. It supports 26 ordinary no-media
  record types plus dedicated sleep storage. Generic `confirmRecord` still rejects
  sleep; custom entries and photos remain unsupported. Sleep UI is not exposed.
  Confirmation saves facts and dirty markers atomically. Original-baby ownership,
  revision CAS, rollback, replay and opaque preservation remain enforced.
- Dedicated sleep writes are `createSleepStart`, `recordCompletedSleep`,
  `recordWake`, `editSleepStart`, `editCompletedSleep`, `updateWake`,
  `withdrawWake`, and `selectEffectiveWake`. `sleepPage` batch-projects roots and
  wake evidence in one read snapshot; `sleepDetails`, `openSleep`, and `wakeDetails`
  provide dedicated reads. UUIDv5 wake identity and durable local observer identity
  do not create a family member/session or imply family-aware sync authorization.
- Explicit native differences include stronger atomic completed-sleep two-root
  creation, refusal to accidentally reopen a projected-closed sleep during start
  edits, strict content-matching replay, and independent wake notes that sleep-note
  edits cannot silently overwrite. Native stale-edit CAS and Int64-overflow refusal
  are also safety differences, not claims of identical Android behavior. See
  `Sources/LeziStorage/StorageScope.swift` and
  [the offline expansion specification](../docs/spec/ios-offline-records.md).

Evidence boundaries (2026-10-19):

The latest verified **dual-green** checkpoint is **`10016b6a`**, with **22 bounded
UI types** (including milk and temperature), native schema **3**, and dedicated
sleep storage. [Apple run 39329817431](https://github.com/tusecwtf/lezi/actions/runs/39329817431)
passed **199 package tests + seven Simulator UI tests**;
[Android run 39329817430](https://github.com/tusecwtf/lezi/actions/runs/39329817430)
passed **5,908 tests**. UI duration was 1,450.014 seconds (milk 249.255 seconds,
temperature 184.565 seconds); artifact `12225776079` contains the acceptance
evidence. Schema **1/2→3** migration preserves data atomically. Storage supports
26 ordinary no-media types plus dedicated sleep commands. Fresh schema-3 host
launch is verified, but **sleep UI is not exposed or accepted**. Database schema
and payload document schema versions are separate concepts.

**Current local, unpublished Food expansion:** baby_food, snack and drink bring
UI to **25 types**. The Food slice passed **190 Linux tests**. Its two new
Apple-only model tests and eighth UI scenario await Apple validation. The proposed
eight-class UI suite with two parallel Simulator workers is also unverified; the
seven-test green run above does not validate that parallel execution setup.
Food keeps untouched optional legacy amounts exactly (nil, empty, Chinese,
full-width and whitespace), normalizes only new/changed amounts, and requires a
parseable amount only for new/changed baby-food facts. Guidance uses the original
baby's birthday and the record's local date, with Java-compatible completed
calendar months; suggestion buttons change only content.

A separate **local schema-4 custom-storage workstream** is ready but is excluded
from this Food publication. Its combined local 195-test result is not the Food
slice's 190-test result and is not Apple or published evidence. Custom workflows
remain pending separate integration and validation; no custom UI is claimed.

The three-type recent-amount map is read in one SQLite snapshot through bounded
keyset pages across history. It follows Android's amount-only 1–999 filtering,
not full optional-payload validation; save/edit still enforce full validation.
Step 5/10/15 is a global local AppStorage preference. Android's day-list lastAmount
launch override is not implemented or simulated by recent values in this host.
No custom-item workflow, complete app, signed install or mixed-client sync claim
follows from these counts.

Two source-derived compatibility questions remain open, without a demonstrated
production failure: Kotlin's hash projection omits null media dimensions while
Rust serializes them; Rust baby ingress rejects a null birthday while the frozen
spec and Kotlin snapshot decoder allow it. Current Android photo upload emits
positive dimensions and its baby mapper emits an epoch-day date, so those normal
producer paths do not establish the discrepancies as live incidents. Do not
change existing Android or Rust behavior on this evidence alone; add real
cross-client proof before claiming full compatibility.

The remaining sync work includes secure endpoint trust and pin persistence,
family/device session setup and refresh, complete family-aware persistent facts and immutable
pending mutation envelopes, frozen causal base/request hash/media receipts,
commit/pull orchestration, conflict snapshots and resolution, media verification,
retry/deadline policy, directory caching, lifecycle/heartbeat scheduling and
isolated cross-end integration tests. No pending envelope is currently sent,
and no claimed parity or usable-product milestone follows from these unit tests.

See `Sources/LeziUI/README.md` for presentation-slice scope as it lands.
