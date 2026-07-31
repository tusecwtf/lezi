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
| 05 | Certificate trust becomes a durable resume endpoint only after a Ready probe | `RealSyncPortTest.certificateAcceptancePersistsThePinOnlyAfterReadyAndPreservesTheExistingSession`, `failedTrustedProbeDoesNotLeaveADurableResumeEndpoint` |
| 06 | QR verification is cancellable and initial recovery status survives the port boundary | `RealSyncPortTest.qrEndpointVerificationCanBeCancelledWithoutPersistingTrust`, `qrMemberLoginExposesRetryableInitialDataRecovery` |
| 08 | Loopback cleartext router contains only health/readiness; business APIs remain on public TLS | Rust API test `internal_router_exposes_only_health_and_readiness`; TLS integration test |
| 09 | Create replay is not coupled to an arbitrary latest Owner session; request/grant claim retries replay only the still-valid original device session | Rust API tests `family_create_retry_never_reissues_credentials_after_session_rotation`, `owner_explicitly_binds_a_pending_device_to_an_existing_member`, `owner_member_login_grant_is_ten_minutes_single_use_and_target_bound` |
| 10 | Kotlin command/receipt diagnostics redact secrets; server root comparison hashes to fixed-size SHA-256 bytes before constant-time comparison | `FamilySessionCoordinatorTest.secretBearingFamilyCommandsNeverRenderPlaintext`; Rust unit `constant_time_eq_rejects_mismatched_secrets` |
| 11 | New session identity is persisted credential-gated before secrets; terminal clear is credential-gated and serialized through the entire local clear | `SyncPreferencesTest.interruptedSessionReplacementPersistsNewIdentityAsReauthBeforeWritingRefreshToken`, `terminalClearMarkerMakesCredentialsUnusableBeforeDomainClearStarts`, `RealSyncPortTest.terminalIdentityClearBlocksConcurrentSyncUntilLocalDataAndCredentialsAreRetired` |
| 12 | Approval-check failures remain visible and retryable, blank delete-family names have a recovery path, and rename copy matches required validation | `FamilyWizardControllerTest.failedManualApprovalCheckKeepsPendingRequestAndShowsRetryableFeedback`, `FamilyMemberDecisionPolicyTest` |
| 13 | Security and operations docs use trusted HTTPS/device-session language and distinguish public TLS from internal loopback health | Static review of `SECURITY.md`, `AGENTS.md`, and `tools/lezi-sync/README.md` |

## Fixed-tree gates

- `./gradlew test lintDebug :app:assembleDebug` — passed after one unrelated
  `DeviceLayoutSnapshotWriterTest` timing failure passed in isolation and the complete gate passed
  on rerun.
- `./gradlew connectedDebugAndroidTest` — passed on `lezi_api35` / API 35 emulator: 93 tests,
  0 failed (modules without instrumentation sources reported 0 tests).
- `cargo fmt --all -- --check` — passed.
- `cargo test --locked` — passed: 38 library tests, 116 API tests, 1 TLS integration test, 0 doc
  test failures.
- `cargo clippy --all-targets --all-features -- -D warnings` — passed.
- `git diff --check` — passed after removing one Markdown trailing-space artifact.
- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`
  SHA-256 `c36ba0a7acc486b89a1f528bfc072e8a6261c16447848969b8a1f2dc452b5a21`.

## Evidence boundary

- No live family NAS replacement, public-port exposure, or production secret operation was run.
- This is a Debug build and automated acceptance record, not parent ticket 16 cross-device release
  acceptance and not ticket 17 Release 0.3.1 delivery.
- Because these fixes postdate the previous candidate, parent ticket 16 must pin a new HEAD and
  rerun its cross-device matrix before release.
