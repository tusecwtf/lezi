# SyncPreferencesTest K0 contract map (ticket 07)

Harness: `SyncPreferencesTestSupport.kt` (122 LOC).

Before: **1332 LOC**, **32** `@Test`. Original kitchen-sink deleted after move.

## `SyncPreferencesClearMarkersTest.kt` (4 tests, ~124 LOC)

| Test | One-line contract |
|------|-------------------|
| `pendingDeviceRemovalClearMarkerSurvivesRestartUntilExplicitCompletion` | pending Device Removal Clear Marker Survives Restart Until Explicit Completion |
| `terminalClearMarkerMakesCredentialsUnusableBeforeDomainClearStarts` | terminal Clear Marker Makes Credentials Unusable Before Domain Clear Starts |
| `pendingMembershipDeletionClearMarkerSurvivesRestartUntilExplicitCompletion` | pending Membership Deletion Clear Marker Survives Restart Until Explicit Completion |
| `pendingFamilyDeletionClearMarkerSurvivesRestartUntilExplicitCompletion` | pending Family Deletion Clear Marker Survives Restart Until Explicit Completion |

## `SyncPreferencesReplayPendingMemberTest.kt` (10 tests, ~417 LOC)

| Test | One-line contract |
|------|-------------------|
| `createRequestIdSurvivesRetryUntilOwnerSessionIsPersisted` | create Request Id Survives Retry Until Owner Session Is Persisted |
| `ownerLoginRequestIdSurvivesRetryUntilOwnerSessionIsPersisted` | owner Login Request Id Survives Retry Until Owner Session Is Persisted |
| `sessionCredentialWriteFailureKeepsClaimAndOwnerReplayCapabilities` | session Credential Write Failure Keeps Claim And Owner Replay Capabilities |
| `refreshRequestIdSurvivesRestartAndRetiresOnlyWithDurableRotatedSession` | refresh Request Id Survives Restart And Retires Only With Durable Rotated Session |
| `failedRotatedTokenWriteLeavesOldJoinedSessionAndReplayNonceUsable` | failed Rotated Token Write Leaves Old Joined Session And Replay Nonce Usable |
| `pendingMemberCapabilitySurvivesProcessButIsEncryptedAndRetiredWithSession` | pending Member Capability Survives Process But Is Encrypted And Retired With Session |
| `pendingMemberSecretIoNeverBlocksTheCallingUiDispatcher` | pending Member Secret Io Never Blocks The Calling Ui Dispatcher |
| `pendingMemberLocalAbandonmentSurvivesSecureResidueCleanupFailure` | pending Member Local Abandonment Survives Secure Residue Cleanup Failure |
| `disasterRestoreCheckpointSurvivesRestartWithoutWritingCredentialToDataStore` | disaster Restore Checkpoint Survives Restart Without Writing Credential To Data Store |
| `nonAuthoritativeConfigRewriteKeepsPendingMemberCapability` | non Authoritative Config Rewrite Keeps Pending Member Capability |

## `SyncPreferencesSessionPersistenceTest.kt` (13 tests, ~546 LOC)

| Test | One-line contract |
|------|-------------------|
| `minimalMemberDirectorySurvivesRestartButIdentityClearRemovesIt` | minimal Member Directory Survives Restart But Identity Clear Removes It |
| `reconnectedSessionAtomicallyRetiresSameFamilyMemberDirectory` | reconnected Session Atomically Retires Same Family Member Directory |
| `sessionBaseUrlIsDerivedFromStructuredEndpoint` | session Base Url Is Derived From Structured Endpoint |
| `sessionAndCursorSurviveStoreRecreation` | session And Cursor Survive Store Recreation |
| `familyNameIsClearedWithFamilySessionAndBlankBecomesNull` | family Name Is Cleared With Family Session And Blank Becomes Null |
| `pullCheckpointAppliesFamilyNamePresenceWithoutReplacingConcurrentSessionFields` | pull Checkpoint Applies Family Name Presence Without Replacing Concurrent Session Fields |
| `staleSameFamilySaveCannotEraseCreatorAcknowledgementsButFamilyChangeDoes` | stale Same Family Save Cannot Erase Creator Acknowledgements But Family Change Does |
| `accessIsProcessOnlyAndRefreshIsNotWrittenToPlaintextDataStore` | access Is Process Only And Refresh Is Not Written To Plaintext Data Store |
| `reauthClearRemovesOnlyCredentialsAndSurvivesProcessRecreation` | reauth Clear Removes Only Credentials And Survives Process Recreation |
| `changingJoinedServerClearsCredentialsAndFamilyReceipts` | changing Joined Server Clears Credentials And Family Receipts |
| `replacingSessionDoesNotLeakPreviousFamiliesSuccessTime` | replacing Session Does Not Leak Previous Families Success Time |
| `interruptedSessionReplacementPersistsNewIdentityAsReauthBeforeWritingRefreshToken` | interrupted Session Replacement Persists New Identity As Reauth Before Writing Refresh Token |
| `httpsSchemeSurvivesSessionPersistence` | https Scheme Survives Session Persistence |

## `SyncPreferencesTrustEndpointTest.kt` (5 tests, ~236 LOC)

| Test | One-line contract |
|------|-------------------|
| `probedEndpointIsPersistedSeparatelyAndForgettingItPreservesActiveSession` | probed Endpoint Is Persisted Separately And Forgetting It Preserves Active Session |
| `forgettingCurrentTrustedEndpointForcesReauthButKeepsFamilyReplicaIdentity` | forgetting Current Trusted Endpoint Forces Reauth But Keeps Family Replica Identity |
| `replacingCurrentTofuPinForcesReauthBeforeTrustingTheNewCertificate` | replacing Current Tofu Pin Forces Reauth Before Trusting The New Certificate |
| `tofuSpkiPinSurvivesStoreRecreationWithoutPersistingAHandshakeCandidate` | tofu Spki Pin Survives Store Recreation Without Persisting AHandshake Candidate |
| `interruptedEndpointCredentialClearIsSuppressedAndRecoveredAfterRestart` | interrupted Endpoint Credential Clear Is Suppressed And Recovered After Restart |
