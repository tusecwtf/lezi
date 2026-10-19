# ADR-0025: Native iOS co-development against the existing family contract

- Status: **Accepted for the isolated native foundation direction**; not delivered parity
- Date: 2026-10-19
- Baseline: public Android snapshot `cfd9c878`
- Acceptance inventory: [iOS parity plan](../spec/ios-parity.md)

## Context

The requested outcome is a complete iOS version that follows the current Android
product's UI, interactions, data semantics and synchronization, uses the same Rust
family service, and supports households mixing Android and iPhone. Existing Android
behavior must remain intact. An iOS-only mock or navigation shell does not satisfy
this outcome.

The Android repository has useful domain boundaries but is not already a Kotlin
Multiplatform shared application:

- `domain/build.gradle.kts` applies Android library/Kotlin Android/Hilt/KSP and
  depends on Room-backed `core:database`, DataStore and `sync`.
- `domain/.../CareLog.kt` directly coordinates DAOs, transactions, SettingsStore,
  sync and platform-facing side effects. These remain a deliberate deep facade,
  not a portable `commonMain` library.
- `core/model/build.gradle.kts` uses Kotlin JVM; model sources use `java.time`,
  `java.io.File` and other JVM facilities. “Pure Kotlin” in the architecture map
  does not mean the code compiles unchanged for Kotlin/Native.
- Compose, Glance widgets, Android services, permission/activity lifecycles,
  PackageInstaller, calendar providers and OkHttp/credential adapters are platform
  implementations. Shared business logic would not automatically deliver their
  iOS equivalents or preserve their failure guarantees.
- Canonical wire fixtures and precise specs already provide a cross-language seam:
  Rust and Kotlin independently implement the same causal protocol today.

ADR numbers 0023 and 0024 already exist, so this decision uses 0025 rather than
reusing or renaming an existing architectural decision.

## Decision

Build an isolated native Swift/SwiftUI client under `ios/`, retaining the current
Android Gradle modules, source behavior, release identity and family server during
initial implementation. Do not wrap the Android UI, introduce a webview substitute,
or refactor Android into KMP as a prerequisite.

Port product behavior deliberately into a Swift domain/storage/sync architecture.
Use transactional private local storage, immutable/stable wire identities,
foreground-only sync, protected credentials and explicit lifecycle adapters.
Within iOS, all screens and widgets reuse one domain aggregation/validation layer.
Do not claim that Kotlin and Swift share their implementation: they share the
product/wire contract and executable fixtures, and need differential and mixed
real-client tests to prevent drift.

Use the unchanged `tools/lezi-sync` service wherever its actual contract permits;
no parallel iOS backend, second family authority, weaker TLS/ACL path or permissive
unknown-key fallback. An identified server release-policy gap must be reviewed as
a separate change, not quietly bypassed inside the client.

The full feature inventory and objective gates in `ios-parity.md` are the completion
contract. All iOS acceptance rows initially remain not implemented. Native platform
differences are explicit design/verification items; no feature is excluded merely
because its Android API does not exist on iOS.

## Options considered

| Option | Advantages | Cost/risk against this task | Result |
|---|---|---|---|
| Native Swift/SwiftUI with shared fixtures | Android isolation; natural iPhone integration; independent shipping/build boundary; existing Kotlin/Rust contract-testing pattern | Duplicated domain/sync implementation can drift; requires comprehensive golden/differential/live tests and disciplined parity maintenance | Selected |
| Extract KMP shared domain/sync now, native SwiftUI UI | Potential long-term reuse of algorithms/protocol logic | Current domain/storage/sync are Android/JVM coupled; extraction changes working Android before iOS parity, requires platform adapters, build/dependency redesign and fresh regression evidence; still must build iOS UI/system integrations | Defer, not reject forever |
| Compose Multiplatform UI | Potential shared UI components | Current screens rely on Android lifecycles, services, Hilt and platform integrations; migration is a major Android change and does not solve release-floor, storage/security or native system semantics | Not initial path |
| Web/PWA or a native shell around shared web UI | Potential UI reuse and a lightweight access path | Pure web cannot preserve all existing native/storage/TLS guarantees; a native shell still needs Apple platform adapters and validation | Under separate investigation; not the current implementation path |
| Separate iOS service | Independent service changes | Duplicates family authority and violates the same-backend requirement | Rejected |

A later KMP proposal should identify a bounded, demonstrably portable domain seam,
prove benefits and migration cost, and preserve Android behavior with independent
regression gates. It must not assume the current modules are portable because
some leaf algorithms are written in Kotlin. No extraction is authorized here.

## Release/version obstacle

`tools/lezi-sync/src/lib.rs` gates authenticated clients with
`x-lezi-client-version-code`. `handlers/app_update.rs` obtains the minimum from a
verified Android APK update channel. The current code does not establish a separate
iOS channel/version policy. iOS build identity cannot simply impersonate today's
Android versionCode, nor append platform fields to frozen closed-key messages.

Before production compatibility is asserted, approve either a proven, explicitly
mapped protocol-compatibility policy viable on the unchanged server, or a separately
scoped additive platform-aware server release policy preserving Android requests,
APK integrity checks and floors. The latter needs the repo's wire/floor gates and
coordinated release approval. Exact iOS bundle ID, team/signing, minimum OS and
Apple distribution path remain open decisions. Existing LAN APK installation is
Android-only and is not an iOS distribution solution.

## Consequences and verification

- Swift domain tests consume existing golden fixtures unchanged and add reviewed
  payload, canonicalization, aggregation, growth, state-machine and fault fixtures.
- Mixed-client tests run actual current Android and native Swift implementations
  against this tree's real Rust server with synthetic data and isolated storage/TLS.
  Both Owner/member directions, offline concurrency, N-way conflicts, media atomicity,
  plans/wakes, membership revocation, restart and empty-server restore are required.
- Native timer, calendar, notifications, widgets, photo and share integrations need
  explicit outcome-based adaptation and physical iPhone acceptance. Suspension and
  OS scheduling are not excuses to fabricate running/success states.
- macOS/Xcode CI must build app/extension targets and run simulator/unit/integration
  gates. Linux tooling or mocked HTTP success cannot validate an iOS app release.
- Android remains the audited reference until a reviewed cross-platform product
  change is requested. Track Android changes as parity deltas; do not freeze the
  Android product forever or label an old snapshot as latest.
- No production NAS changes, TLS regeneration, family data export, deployments or
  signing credential creation are consequences of writing this ADR. A separately
  authorized draft PR is a review artifact, not merge/deployment approval. Existing deployment approvals and safety invariants remain in force.

## Acceptance of this decision

The native isolation direction is accepted for continuing foundation work and a
draft PR, with no merge or release implied. The release-policy choice remains open
and must be approved separately before production enrollment. Architecture acceptance
is distinct from application acceptance: full product parity requires the matrix's
macOS, real-server, Android regression and manual iPhone evidence. Foundation package
CI, including Simulator SDK core compilation, does not satisfy those app gates.
