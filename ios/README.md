# LEZI iOS foundation

This is an additive Swift package beside the current Android app and Rust LAN
service. It is **not a complete iOS product, signed device app or sync client**. It
contains no backend fork, network calls, account bootstrap, deployment tooling,
or Android-version impersonation.
`App/` supplies an unsigned Simulator-only development host for the bounded
offline baby, ordinary care and custom-record flows. Its Apple build and launch evidence are separate from
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
  The current draft batch uses native schema **4**, with atomic,
  data-preserving migration from schema 1, 2 or 3. It supports 26 ordinary
  no-media record types plus dedicated sleep and custom-definition/record paths.
  Generic `confirmRecord` still rejects sleep and custom; their dedicated writers
  preserve their separate identity and transaction contracts. Photos remain
  unsupported. First-slice sleep start/wake UI passed at `eb64828c`; current
  completed-backfill/edit/soft-delete additions still await Apple acceptance.
  Confirmation saves facts and dirty markers atomically. Original-baby ownership,
  revision CAS, rollback, replay and opaque preservation remain enforced.
- The draft custom slice adds a concrete local catalog and dedicated record
  create/edit/soft-delete UI. Definitions have stable UUIDs and separate local
  ordering; records retain title/icon snapshots across definition changes. It does
  not invent Android numeric IDs or family authority. Catalog/record saves remain
  explicit-confirm and revision/context guarded.
- The draft manual nursing form completes **26 ordinary forms**, alongside
  the dedicated custom flow. It records left/right minutes, order and optional
  amount as point facts, retaining supported historical record-mode values when
  editing. It does not implement an active nursing timer, background timing,
  interval inference.
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

Evidence boundaries (updated 2026-10-20):

Historical complete dual-green `10016b6a` covered 22 UI types, schema 3,
199 Apple package tests and seven UI tests, plus 5,908 Android tests. That result
covers only its source, not any later draft.

**Latest verified dual-green checkpoint `eb64828c` (2026-10-20):**
[Android run 39349208928](https://github.com/tusecwtf/lezi/actions/runs/39349208928)
passed **5,910 tests with zero skipped**;
[Apple run 39349208906](https://github.com/tusecwtf/lezi/actions/runs/39349208906)
passed **247 package tests and all 11 UI scenarios**. This verifies that source,
including the first sleep start/wake/relaunch path and prior gate repairs, not
the additional sleep work below.

**Completed-sleep draft `fb3c984a`, Apple UI acceptance blocked:**
[Apple run 39362723847](https://github.com/tusecwtf/lezi/actions/runs/39362723847)
passed **255 package tests**, iOS SDK compilation and the unsigned app build.
All **12 UI cases executed once**: the original eleven passed, while the
completed-sleep case opened the real calendar and failed its numeric-button
lookup. Its retained AX tree shows a day Button with a localized full-date
label containing a numeric StaticText child, plus native month-navigation IDs.
The previous host/runner locale mismatch is fixed without changing app language.
[Android run 39362723838](https://github.com/tusecwtf/lezi/actions/runs/39362723838)
passed **5,910 tests, zero failures/errors/skips**. The complete Linux regression
passed **221 tests, zero failures** (46 storage, 89 portable presentation,
61 domain, 25 core).

The current test-only repair uses those captured native IDs and day-child
semantics. It reads the actual displayed month, moves the exact bounded number
of months with a checked year/month after every tap, then selects the unique
day Button containing the numeric child. The flow reads the initial compact date,
selects January 1 of that captured year, moves back to December 31 of the
previous year for its start, and forward to January 1 for its end. This exercises month-start and year-end
navigation in both directions without changing the device clock or app language.
AX trees and screenshots are retained for each navigation and selection.

Apple execution of this repair is pending. Neither the boundary interactions
nor the completed-sleep save/edit/delete/relaunch sequence is accepted yet.
The earlier package and eleven-scenario passes must not be inherited as a
complete acceptance result for this draft. No test-only picker, skipped
boundary, filtered gate or blind retry establishes acceptance.

The verified first sleep slice supports explicit-confirm SleepStart creation,
a separate WakeObservation for the captured sleep/baby, and projected readback.
The current draft extends that bounded workflow as follows:

- Completed backfill starts with **no end time selected**. Opening the end picker
  offers a candidate initially equal to the start; the user must explicitly
  choose/confirm it. Equal start/end and future times are rejected.
- Start editing preserves duration and sleep flags where applicable. Completed
  editing uses both roots' revision checks (dual CAS) and retains the original
  WakeObservation identity instead of creating a replacement observation.
- Sleep and wake notes remain independent. Sleep soft-delete retains wake facts.
- Legacy `end_timestamp` and opaque sleep remain preserved and read-only.

This is **not complete sleep parity**. No observation-management/recovery entry
is added, and no server behavior changes. Nursing timers, care plans, media,
family sync and complete-product acceptance remain open. New UI behavior above
is source implementation plus Linux evidence, not Apple acceptance.

Food keeps untouched optional legacy amounts exactly (nil, empty, Chinese,
full-width and whitespace), normalizes only new/changed amounts, and requires a
parseable amount only for new/changed baby-food facts. Guidance uses the original
baby's birthday and the record's local date, with Java-compatible completed
calendar months; suggestion buttons change only content.

The three-type recent-amount map is read in one SQLite snapshot through bounded
keyset pages across history. It follows Android's amount-only 1–999 filtering,
not full optional-payload validation; save/edit still enforce full validation.
Step 5/10/15 is a global local AppStorage preference. Android's day-list lastAmount
launch override is not implemented or simulated by recent values in this host.
The local custom workflow is implemented in the draft batch above, but no
complete app, signed install or mixed-client sync claim follows from these counts.

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

See `Sources/LeziUI/README.md` for presentation-slice scope as it lands. The
[next sync-adapter design](../docs/spec/ios-sync-adapter.md) remains a proposal;
its release compatibility policy is neither decided nor implemented.

## Wake-observation continuation (Apple UI repair pending)

The preceding `a0874310` checkpoint passed Android [39368766299](https://github.com/tusecwtf/lezi/actions/runs/39368766299)
(5,910 tests, zero failures/errors/skips) and Apple [39368766367](https://github.com/tusecwtf/lezi/actions/runs/39368766367)
(255 package tests and all twelve UI flows once). Its completed-sleep flow
actually traversed January 2026 → December 2025 → January 2026 and completed
save/edit/delete/relaunch. That verified result supersedes the earlier pending
calendar status above, but does not validate this subsequent observation slice.

This next bounded slice exposes legal visible wake observations, original-date
HH:mm correction with an independent wake note, and direct withdrawal. Parent
sleep and wake revisions are both mandatory transactional compare-and-swap
inputs. Withdrawal retains evidence and may reopen the sleep; it is not deletion
or restoration. No restore/history entry, effective-observation selector, media,
family permissions or network adapter is introduced. Durable-local-actor editing
is an offline adaptation, not proof of family membership or owner authority.

The thirteenth actual-store Simulator flow preserves the existing twelve cases.
The complete Linux package suite passed 251 tests (57 storage, 96 portable
presentation, 73 domain, 25 core). On `0c061e26`, Android
[39375330332](https://github.com/tusecwtf/lezi/actions/runs/39375330332) passed
5,910 tests with zero failures/errors/skips. Apple
[39375330342](https://github.com/tusecwtf/lezi/actions/runs/39375330342) passed
289 package tests, iOS SDK compilation, unsigned app build and smoke launch.
All thirteen UI cases executed once; the original twelve passed. The new
observation case opened its list but failed its first Close-control assertion:
retained AX exposed `Button identifier: offline.wake.sheet, label: 关闭`, because
the outer SwiftUI Group identifier overrode the control's `offline.wake.close`.

The repair removes only that unused Group-wide identifier and captures AX and a
screenshot immediately after opening. The Close-control wait and all remaining
business assertions stay unchanged. Observation correction, withdrawal and
relaunch acceptance remain unverified until the repaired flow executes on Apple.
The serial workflow budget is 75 minutes because the preceding twelve-flow suite
took 46.8 minutes excluding build/boot; no filters, skips or retries were introduced.
Full product parity, mixed-client synchronization, signing and physical-device
acceptance remain incomplete. The PR stays draft and must not merge.

## Scoped offline search continuation (Apple validation pending)

The preceding `c038dccc` checkpoint passed Android [39380697461](https://github.com/tusecwtf/lezi/actions/runs/39380697461)
(5,910 tests, no failures/errors/skips) and Apple [39380697410](https://github.com/tusecwtf/lezi/actions/runs/39380697410)
(289 package tests and all thirteen UI flows once). The wake correction,
withdrawal and relaunch tail is accepted for that bounded Simulator slice;
that evidence supersedes its earlier pending status, not this new search gate.

The new full-screen search route traverses complete stored history rather than
only loaded rows. It matches Android labels/aliases and visible business text,
including projected sleep and saved custom titles, never raw JSON keys. Literal
query identity, selection/scope epochs and content revisions fence pagination
and reopen. Same-scope failure may retain read-only rows; changed scope clears
results immediately. Fresh supported hits reuse original-revision editors;
unsupported or stale hits expose genuinely read-only details. Only successful
commits invalidate queries. No novel date/category filters, timeline rail,
family authority, schema, server or media changes are included.

The complete Linux suite passed 301 tests (69 storage, 115 portable presentation,
92 domain, 25 core). Expected Apple 340 package tests and fourteen real-store UI
flows remain unverified for this new head. Existing thirteen UI bodies/helpers
are unchanged. The new case covers a raw-key suffix non-match, English alias,
literal percent/underscore, UUID-preserving open/cancel/edit, committed refresh,
delete cancellation/confirmation, two-baby isolation and relaunch. The complete
serial scheme keeps its 75-minute budget with no skip/filter/retry.

Full product parity, backend interoperability, signing and physical-device
acceptance remain incomplete. Keep the PR draft and unmerged.
