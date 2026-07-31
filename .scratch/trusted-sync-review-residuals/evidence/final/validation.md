# Trusted-sync review residuals · final validation

Date: 2026-07-31
Implementation base: `47828af072b1e85e47b91bf6c9533feffa94766c`
Scope: `.scratch/trusted-sync-review-residuals` tickets 01–13

## Acceptance result

All 13 tickets' Must requirements are implemented. Ticket 12's separate optional shallow-status
Should remains deliberately unchecked: adding last-sync time and pending-outbox counts needs a new
projection contract and is not required to close its four Must items.

| Tickets | Accepted behavior | Regression evidence |
|---------|-------------------|---------------------|
| 01, 04 | Pending restoration cannot clobber active/completed wizard state; secret-bearing submits require and retain the verified origin | `FamilyWizardControllerTest.pendingApprovalRestoreOnlyClaimsIdleOrExistingWaitingState`, `submitUsesOnlyTheVerifiedOriginAndNeverCallsGatewayForAMutatedDraft` |
| 02 | Failed root/bootstrap guesses share a source-scoped budget across admin paths; configured create no longer reveals password correctness | Rust API tests `failed_root_passwords_share_a_per_source_budget_across_admin_endpoints`, `family_create_requires_the_root_password_and_never_reopens_configured_setup` |
| 03, 07 | Stable member reauthentication preserves receipts; changed identities remain gated behind durable replica reset before synchronization | `FamilySessionCoordinatorTest.memberReauthWithTheSameReplicaIdentitySkipsReceiptReset`, `approvedMemberClaimGatesTheNewSessionUntilReplicaResetAndNeverClaimsTwice`, `ReplicaSyncEngineTest.reauthRequiredPreviousIdentityStillOwnsItsStableMediaReceipt` |
| 05 | Certificate trust becomes a durable resume endpoint only after a Ready probe; a failed same-origin re-verification clears the stale durable endpoint | `RealSyncPortTest.certificateAcceptancePersistsThePinOnlyAfterReadyAndPreservesTheExistingSession`, `failedTrustedProbeClearsAStaleDurableResumeEndpoint` |
| 06 | QR verification is cancellable, verification failure offers retry and manual join without logging in, and initial recovery status survives the port boundary | `RealSyncPortTest.qrEndpointVerificationCanBeCancelledWithoutPersistingTrust`, `qrMemberLoginExposesRetryableInitialDataRecovery`, `MemberApprovalWaitingDeviceTest.failedMemberQrVerificationOffersRetryAndManualJoinWithoutLogin` |
| 08 | Loopback cleartext router contains only health/readiness; business APIs remain on public TLS | Rust API test `internal_router_exposes_only_health_and_readiness`; TLS integration test |
| 09 | Create replay is not coupled to an arbitrary latest Owner session; request/grant claim retries replay only the still-valid original device session | Rust API tests `family_create_retry_never_reissues_credentials_after_session_rotation`, `owner_explicitly_binds_a_pending_device_to_an_existing_member`, `owner_member_login_grant_is_ten_minutes_single_use_and_target_bound` |
| 10 | Kotlin command/receipt diagnostics redact secrets; server root comparison hashes to fixed-size SHA-256 bytes before constant-time comparison | `FamilySessionCoordinatorTest.secretBearingFamilyCommandsNeverRenderPlaintext`; Rust unit `constant_time_eq_rejects_mismatched_secrets` |
| 11 | New session identity is persisted credential-gated before secrets; terminal clear is credential-gated and serialized through the entire local clear | `SyncPreferencesTest.interruptedSessionReplacementPersistsNewIdentityAsReauthBeforeWritingRefreshToken`, `terminalClearMarkerMakesCredentialsUnusableBeforeDomainClearStarts`, `RealSyncPortTest.terminalIdentityClearBlocksConcurrentSyncUntilLocalDataAndCredentialsAreRetired` |
| 12 | Approval-check failures remain visible and retryable, blank delete-family names perform a real pull-to-refresh recovery, and rename copy matches required validation | `FamilyWizardControllerTest.failedManualApprovalCheckKeepsPendingRequestAndShowsRetryableFeedback`, `FamilyMembersDevicesPageDeviceTest.blankDeleteFamilyNameProvidesARealRefreshAction`, `FamilyMemberDecisionPolicyTest` |
| 13 | Security and operations docs use trusted HTTPS/device-session language and distinguish public TLS from internal loopback health | Static review of `SECURITY.md`, `AGENTS.md`, and `tools/lezi-sync/README.md` |

## Post-implementation review hardening

The fixed-range Standards and Spec review covered
`47828af072b1e85e47b91bf6c9533feffa94766c..1d6e839731cb3189bdce94f3f9de56ed32838d63`.

- Standards: corrected Kotlin indentation in the two QR error mappers. The reviewer retained one
  Low maintainability judgement: account and onboarding ViewModels repeat thin QR orchestration,
  while their projection-specific error mapping differs. This is bounded debt, not a correctness
  blocker, and extracting a new shared controller is outside these residual tickets.
- Spec: closed all three findings by clearing a stale same-origin endpoint after failed trust
  verification, keeping QR verification failure recoverable through retry/manual join, and making
  the blank-family-name deletion action perform a real pull-to-refresh before refreshing the member
  projection. Re-review found no related regression.

## Fixed-tree gates

- `./gradlew test` — passed. The earlier implementation-fixed-point run also passed after one
  unrelated `DeviceLayoutSnapshotWriterTest` timing failure passed in isolation.
- `./gradlew lintDebug` — passed. A preceding combined Gradle invocation hit an Android lint/Kotlin
  FIR internal exception while analyzing unrelated `AppHeaderTest.kt`; isolated lint completed
  normally with no lint-rule failure.
- `./gradlew :app:assembleDebug` — passed.
- `./gradlew connectedDebugAndroidTest --no-parallel --console=plain -q` — passed on
  `lezi_api35` / API 35 emulator: 95 tests, 0 failed (modules without instrumentation sources
  reported 0 tests). The default parallel invocation caused multiple library test APKs to contend
  for the Compose test host and fail with `No compose hierarchies found`; serial execution removed
  that harness conflict.
- `cargo fmt --all -- --check` — passed.
- `cargo test --locked` — passed: 38 library tests, 116 API tests, 1 TLS integration test, 0 doc
  test failures.
- `cargo clippy --all-targets --all-features -- -D warnings` — passed.
- `git diff --check` — passed after removing one Markdown trailing-space artifact.
- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`
  SHA-256 `0274a7224c5f283761051b13fba7a967cf1d041258fccbc6c48a6d9bd706b4d8`.

## Evidence boundary

- No live family NAS replacement, public-port exposure, or production secret operation was run.
- This is a Debug build and automated acceptance record, not parent ticket 16 cross-device release
  acceptance and not ticket 17 Release 0.3.1 delivery.
- Because these fixes postdate the previous candidate, parent ticket 16 must pin a new HEAD and
  rerun its cross-device matrix before release.
