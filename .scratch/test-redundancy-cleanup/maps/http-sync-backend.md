# HttpSyncBackendTest K0 contract map (ticket 07)

Harness: `HttpSyncBackendTestSupport.kt` (137 LOC).

Before: **2190 LOC**, **43** `@Test`. Original kitchen-sink deleted after move.

## `HttpSyncBackendFamilyAuthTest.kt` (11 tests, ~642 LOC)

| Test | One-line contract |
|------|-------------------|
| `disasterRestoreUsesRootOnlyAtBoundariesAndRecoveryTokenInTheMiddle` | disaster Restore Uses Root Only At Boundaries And Recovery Token In The Middle |
| `startDisasterRestoreAdvertisesClientVersionWithoutBearerToken` | start Disaster Restore Advertises Client Version Without Bearer Token |
| `anonymousHealthAndReadyNeverSendFamilyCredentials` | anonymous Health And Ready Never Send Family Credentials |
| `familyDeleteSendsNormalizedNameAndRequestScopedRootOutsideTheJsonBody` | family Delete Sends Normalized Name And Request Scoped Root Outside The Json Body |
| `ownerLoginAndTakeoverUseRootHeaderWithoutAuthorizationAndParseOwnerSession` | owner Login And Takeover Use Root Header Without Authorization And Parse Owner Session |
| `createSendsBootstrapSecretOnlyAsTheExpectedHeader` | create Sends Bootstrap Secret Only As The Expected Header |
| `createSendsOptionalFamilyNameAndParsesResponse` | create Sends Optional Family Name And Parses Response |
| `createRequiresAndSendsFamilyName` | create Requires And Sends Family Name |
| `renameFamilyPostsOwnerOnlyWireBody` | rename Family Posts Owner Only Wire Body |
| `refreshUsesOnlyTheRefreshBodyAndParsesCanonicalRotatedSession` | refresh Uses Only The Refresh Body And Parses Canonical Rotated Session |
| `deviceLogoutAndOwnerRevokeUseExplicitAuthenticatedRoutes` | device Logout And Owner Revoke Use Explicit Authenticated Routes |

## `HttpSyncBackendMemberEndpointTest.kt` (10 tests, ~556 LOC)

| Test | One-line contract |
|------|-------------------|
| `memberRequestStatusCancelClaimAndOwnerDecisionsUseSeparatedCapabilities` | member Request Status Cancel Claim And Owner Decisions Use Separated Capabilities |
| `memberLoginQrGrantUsesOwnerAuthThenClaimsOnlyWithPinnedEndpointAndDeviceName` | member Login Qr Grant Uses Owner Auth Then Claims Only With Pinned Endpoint And Device Name |
| `memberLoginQrGrantWithoutLandingUrlKeepsLegacyQrCompatibility` | member Login Qr Grant Without Landing Url Keeps Legacy Qr Compatibility |
| `pinnedMemberGrantClaimInstallsSpkiTrustBeforeTheGrantCanBeWritten` | pinned Member Grant Claim Installs Spki Trust Before The Grant Can Be Written |
| `verifiedEndpointResolverPinsFamilyCreateBeforeTheRootPasswordCanBeWritten` | verified Endpoint Resolver Pins Family Create Before The Root Password Can Be Written |
| `verifiedEndpointResolverAlsoPinsAuthenticatedMediaUploadAndDownload` | verified Endpoint Resolver Also Pins Authenticated Media Upload And Download |
| `membersUsesAuthenticatedPrivacyProjectionAndParsesRoles` | members Uses Authenticated Privacy Projection And Parses Roles |
| `membersRejectsUnknownRolesAndMissingCurrentFields` | members Rejects Unknown Roles And Missing Current Fields |
| `ordinaryDisplayNameUpdateReturnsPendingWithoutPretendingTheNameChanged` | ordinary Display Name Update Returns Pending Without Pretending The Name Changed |
| `pendingRenameRequestsParseOnlyOwnerReviewFields` | pending Rename Requests Parse Only Owner Review Fields |

## `HttpSyncBackendPullWireTest.kt` (5 tests, ~229 LOC)

| Test | One-line contract |
|------|-------------------|
| `pullRejectsResponseWithoutRequiredContinuationFlag` | pull Rejects Response Without Required Continuation Flag |
| `pullParsesTheAdditiveContinuationFlag` | pull Parses The Additive Continuation Flag |
| `pullSendsClientVersionCodeHeaderOnAuthoritativeSync` | pull Sends Client Version Code Header On Authoritative Sync |
| `pullRejectsResponseWithoutRequiredFamilyName` | pull Rejects Response Without Required Family Name |
| `pullParsesNullAndValueFamilyName` | pull Parses Null And Value Family Name |

## `HttpSyncBackendReconcileAtomicTest.kt` (8 tests, ~435 LOC)

| Test | One-line contract |
|------|-------------------|
| `reconcilePostsFrozenUnitsAndParsesCompleteTypedVerdicts` | reconcile Posts Frozen Units And Parses Complete Typed Verdicts |
| `reconcileRejectsACursorOlderThanThePulledCheckpoint` | reconcile Rejects ACursor Older Than The Pulled Checkpoint |
| `reconcileRejectsANonMediaEntitySmuggledInsideRemoteMedia` | reconcile Rejects ANon Media Entity Smuggled Inside Remote Media |
| `reconcileRejectsConfirmedWithoutCompleteCanonicalEvidence` | reconcile Rejects Confirmed Without Complete Canonical Evidence |
| `reconcileRejectsBabyManifestThatDoesNotMatchTheAvatarPointer` | reconcile Rejects Baby Manifest That Does Not Match The Avatar Pointer |
| `stalledMediaUploadDisconnectsAndFailsWithIOException` | stalled Media Upload Disconnects And Fails With IOException |
| `commitRejectsMalformedCanonicalRecordAuthors` | commit Rejects Malformed Canonical Record Authors |
| `stagePutAndCommitBundleFollowAtomicEndpoints` | stage Put And Commit Bundle Follow Atomic Endpoints |

## `HttpSyncBackendWireSafetyTest.kt` (9 tests, ~343 LOC)

| Test | One-line contract |
|------|-------------------|
| `jsonResponseRejectsDeclaredBodyLargerThanTheWireLimit` | json Response Rejects Declared Body Larger Than The Wire Limit |
| `mediaResponseStreamsOnlyUpToTheWireLimitWithoutContentLength` | media Response Streams Only Up To The Wire Limit Without Content Length |
| `redirectsAreRejectedWithoutFollowingEvenOnTheSameOrigin` | redirects Are Rejected Without Following Even On The Same Origin |
| `getAppUpdateMetadataUsesAuthenticatedGetAndParsesWireFields` | get App Update Metadata Uses Authenticated Get And Parses Wire Fields |
| `downloadAppUpdateApkUsesAuthenticatedGetAndReturnsBytes` | download App Update Apk Uses Authenticated Get And Returns Bytes |
| `currentIdentityFieldsShareSafeDisplayNameNormalization` | current Identity Fields Share Safe Display Name Normalization |
| `capturedDiagnosticLogRedactsAccessRefreshAndAuthorizationValues` | captured Diagnostic Log Redacts Access Refresh And Authorization Values |
| `familyNameWireHelpersTrimAndRejectControlCharacters` | family Name Wire Helpers Trim And Reject Control Characters |
| `formatSyncHttpFailureIncludesJsonDetail` | format Sync Http Failure Includes Json Detail |
