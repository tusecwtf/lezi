# LEZI iOS foundation

This is an additive Swift package beside the current Android app and Rust LAN
service. It is **not yet an installable iOS app or a complete sync client**. It
contains no backend fork, network calls, account bootstrap, deployment tooling,
or Android-version impersonation.

## Toolchain and platforms

- Swift 6.0 language/tools minimum, no third-party package dependencies.
- iOS 17 minimum is an initial proposal for the SwiftUI implementation, not a
  verified product/device support decision. macOS 14 supports development tests.
- `LeziCore` is Foundation-only and testable on Linux. `LeziUI` separates portable
  presentation rules from Apple-only SwiftUI views.

Run from the checkout root:

```sh
swift test --package-path ios
```

Tests read `config/conflict-v2-golden.json` from the same checkout; they never
fetch or copy a second corpus. Keep `ios/` beside `config/` when running tests.
A Linux pass does not prove Apple SDK compilation, simulator behavior, signing,
accessibility, device behavior, or end-to-end interoperability.

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

The remaining sync work includes secure endpoint trust and pin persistence,
family/device session setup and refresh, persistent local facts and immutable
pending mutation envelopes, frozen causal base/request hash/media receipts,
commit/pull orchestration, conflict snapshots and resolution, media verification,
retry/deadline policy, directory caching, lifecycle/heartbeat scheduling and
isolated cross-end integration tests. No pending envelope is currently sent,
and no claimed parity or usable-product milestone follows from these unit tests.

See `Sources/LeziUI/README.md` for presentation-slice scope as it lands.
