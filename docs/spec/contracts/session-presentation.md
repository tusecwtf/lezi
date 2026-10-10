# Credential-free session presentation migration

Scope: US-070 / US-076. This is an additive source API transition. There is no persisted
schema, family wire, server capability, authentication policy, or UI behavior change.

## Owner and compatibility

`SyncPort.sessionPresentation()` exposes `SyncSessionPresentation`, projected by the existing
`RealSyncPort` from one coherent `SyncPreferences.session` emission. `isJoined` is copied from
the credential-owning `SyncSession.isJoined`; consumers do not infer readiness from identity or
from synthetic credential-presence flags. Refresh-only cold-start sessions, retained reauth
identities, and replica-reset gating therefore retain their existing semantics. Identity,
endpoint, readiness, profile, last-success time, and creator provenance come from one snapshot.
The provenance set is detached and unmodifiable; constructor/copy visibility prevent ordinary
callers from constructing a mutable variant. There is no new independently writable state owner.

The deprecated `session(): Flow<SyncSession>` remains source-compatible for sync internals and
transitioning tests. The create/owner-login/member-QR/member-approval result objects retain their
deprecated credential-bearing `session` adapters and expose `sessionPresentation` through the
same projection. No ordinary production caller reads these old adapters. `FamilyWizardOutcome`
retains only the credential-free type, including post-commit retry state.

## Production consumer inventory

All ordinary production session consumers have migrated:

- App: `MainActivity` root/base/forced-update state and update install preflight
- Domain read/write policy inputs: `BabyFamilyProfileCoordinator`, `CustomItemCatalog`,
  `CarePlanCoordinator`, `ConflictAuditQueries`, `RecordMutationCoordinator`,
  `WakeObservationCoordinator`, `LocalDataClearCoordinator`, `TimelineWindowRepository`
- Domain retained family state: `FamilyWizardController` outcomes from create, reclaim,
  owner login, member approval, and member QR claim
- Feature family: `AccountFamilyWizardHost`, `AccountOverviewHost`, `MembersDevicesHost`,
  `FamilyNetworkSettingsHost`, `ConflictResolverHost`, and `LocalFamilyIdentityReload`
- Other features: `LogViewModel`, `SettingsViewModel`, `SearchRepository`, `OnboardingViewModel`

Residual credential-bearing production reads are intentionally inside `sync`: preference owner,
authentication/refresh/backend and replica/session engines, credential-aware lifecycle commands,
legacy result adapters, and the existing shallow-status projector. The app composition root still
wires credential storage; it does not publish it into ordinary state. No ordinary app/domain/feature
source imports `SyncSession`, calls the legacy `session()`, or reads a legacy command-result session.

## Fake inventory

Every existing fake overriding `SyncPort.session()` explicitly overrides the new accessor to map
its own flow. This matters for Kotlin delegation: an inherited default on `SyncPort by NoOpSyncPort()`
would otherwise project the delegate's empty session, ignoring the fake's session override.

- Domain: `RecordingSyncPort`, `TimelineSyncPort`, `RecordingClearSyncPort`, and the
  `CareLogRealServerSeamSupport` scaffold adapter
- Family: three `FamilyHostBehaviorTest` session fixtures and `MembersDevicesTimeoutTest`
- `ConflictResolverHostRaceTest` supplies the new projection directly
- Wizard controller, QR-dialog, account/onboarding adapter, and family-copy fixtures project
  their legacy owner fixtures before building retained outcomes
- `NoOpSyncPort` uses the shared default projection of its genuinely disabled session

Fakes without a session override remain disabled as before. Internal sync/session/credential-store
fixtures keep the owner type because their subject is authentication and persistence, not top-level UI.

## Projection cost

Each collected owner emission maps to a new snapshot and defensively copies its creator-provenance
set before `distinctUntilChanged`. Thus credential/checkpoint-only churn suppresses downstream UI
emissions but still costs O(A) traversal/allocation for A provenance entries per source emission.
The regression feeds 1,000 credential/checkpoint variants with 64 provenance entries, asserting one
visible result and exactly 1,000 source-set iterations. This is an operation-count/behavior check,
not a release-device latency, heap, or zero-cost claim. No independent cache or mutable secondary
owner is introduced to optimize away that required snapshot isolation.

## Regression proof targets

New targeted tests:

- `:sync:testDebugUnitTest --tests com.lezi.babylog.sync.session.SyncSessionPresentationTest`
- `:sync:testDebugUnitTest --tests com.lezi.babylog.sync.RealSyncPortSessionPresentationTest`
- `:sync:testDebugUnitTest --tests com.lezi.babylog.sync.session.SyncPreferencesCredentialProjectionTest`
- `:domain:testDebugUnitTest --tests com.lezi.babylog.domain.family.FamilyWizardSessionPresentationTest`

They cover owner-equivalent joined/refresh-only/reauth/left readiness, rename and last-success
updates, no credential/checkpoint-only UI churn, immutable provenance, command-result adapters,
production port transitions, delayed checkpoint emission across a committed identity change,
retained completed/retryable wizard state, and compiled API/field checks excluding credentials.
These are behavioral/type checks, not source-layout or line-count assertions.

Run changed-module JVM suites for `sync`, `domain`, `feature:family`, `feature:onboarding`,
`feature:log`, `feature:settings`, `feature:search`, and `app`; compile the final app. Existing family
host, timeline, local-clear, conflict race, and wizard suites exercise the migrated fake contracts.
Run the repository-required isolated paired sync proof against the final integrated tree; no NAS
operation is needed. Execution results belong to the integration validation record, not this source
migration document.
