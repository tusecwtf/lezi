# RealSyncPort fixture startup barrier

This is JVM test scheduling support only. It changes no production dispatcher,
mutex, timeout, gate or persistence behavior, and does not prove Application/device
startup acceptance.

## Preserved failures and controlled interleaving

At `ea266550`, the full JVM run stopped after a pending-member foreground test
returned a failed `Result`; the old boolean assertion did not retain its exception
identity. That identity remains unknown. The separate controlled test at `4347e55`
uses the existing credential-migration latch to hold real IO startup under its
replica mutex. It demonstrates exactly 2,000 ms of virtual waiting produces
`HouseholdSyncing`, zero backend status requests and an unchanged pending request.
It releases the latch and fences relevant startup work in `finally`.

At `0901453`, the complete Sync Debug run recorded a different failure:
`retainedFamilyEndpointChangeDoesNotRequeueLocalFacts` expected
`DifferentFamilyServerException` but received `FamilyHttpException(HouseholdSyncing)`.
Its full 1,429-case result and the earlier failure are preserved separately.
These failures motivate an explicit test startup precondition, not a change to
the production two-second budget. The controlled interleaving does not retroactively identify
the earlier lost exception.

## Opt-in completion fence

`SyncRig.awaitStartupRecovery()` intentionally retains its old meaning: it waits
only for the first pending-store load. It is not changed globally.

The new `awaitInitialReplicaBarrier()` is allowed only before a normal fixture's
first public port operation/signal or policy-clock advance, with no terminal
cleanup staged. It reads that port's actual private `syncMutex` in the ordinary
host JVM, requiring the exact field and `Mutex` type and successful reflection
access. Missing/inaccessible/wrong-type fields fail immediately; no replacement
mutex, exception swallowing, JVM security settings or production visibility change
is permitted.

The causal chain under this precondition is:

1. The constructor's IO startup coroutine acquires `syncMutex`.
2. Its `recoverPendingLocalClearLocked()` calls
   `LocalReplicaClearCoordinator.recoverPendingLocked()`.
3. `TestPendingReplicaCleanupStore.load()` completes `firstLoad` while the
   constructor still owns that same mutex. The signal is not an unlock signal.
4. After awaiting that signal, the helper acquires/releases the same mutex with
   an empty untimed `withLock`. It cannot finish before constructor unlock.

No test business operation can supply an earlier firstLoad under the stated
precondition. The default fixture's policy clock has not advanced to the first
heartbeat deadline, and its sync-signal channel starts empty. This fences the
initial replica critical section only. It is not shutdown of process collectors
or a guarantee that terminal-removal continuation outside the lock is complete.
No real sync/handshake is added to joined fixtures as a waiting mechanism.

## Audited ordinary-operation call sites

The audit examined all 29 `RealSyncPort*Test.kt` files and 261 `SyncRig`
constructions at `0901453`. Only the following 23 methods / 28 fixture instances
need the explicit precondition. Business assertions remain unchanged.

### RealSyncPortFamilyWireTest.kt

Initial state: joined Owner.

- `renamePreservesCredentialsRotatedWhileTheServerRequestWasInFlight`: `rig`.
- `retainedFamilyEndpointChangeDoesNotRequeueLocalFacts`: `rig`.
- `familyMemberListUsesTrustedEndpointWithoutTransportIdentity`: `rig`.
- `fakeBackendConvergesFamilyNameAcrossTwoClientsOnZeroEntityPull`: `ownerRig`.

### RealSyncPortSessionLifecycleTest.kt

Initial state: unjoined configured endpoint, except ownerCreatesOneQrCode... (joined Owner).

- `memberLoginChecksNeverBlockNetworkCompletionBehindSlowUiCollector`: `rig`.
- `failedDomainCleanupRecoveryBlocksEndpointMutation`: `rig`.
- `failedReplicaRecoveryBlocksFamilyCreationBeforePolicyAndBackendIo`: `rig`.
- `freshCreatePersistsSessionThenPushesPendingDataAndFullPullsFromZero`: `rig`.
- `failedFirstPullKeepsOwnerSessionAndRestartRetriesWithoutCreatingAgain`: `rig`.
- `qrMemberLoginFinishesBeforeRetryableInitialDataRecovery`: `rig`.
- `ownerCreatesOneQrCodeFromTheTrustedEndpointAndServerLandingUrl`: `rig`.
- `queuedNetworkChangeWaitsForDurableCreateWithoutExtendingCreateThroughFirstPull`: `rig`.
- `failedReplicaRecoveryBlocksEndpointMutationInsideSharedBarrier`: `rig`.

### RealSyncPortIdentityClearTest.kt

Initial state: joined Owner or Member, before staged terminal cleanup.

- `confirmedFamilyDeleteStagesFullClearAndRetiresEveryLocalFamilyTrace`: `rig`.
- `failedFamilyDeletePreservesEverythingAndInterruptedCleanupResumes`: `failedRemoteRig`, `interruptedRig`.
- `repeatedFamilyDeleteConvergesOnlyOnExplicitTerminalReason`: `explicit`, `generic`.
- `confirmedMemberLeaveStagesFullClearAndRetiresLocalIdentity`: `rig`.
- `failedMemberLeavePreservesEverythingAndInterruptedCleanupResumes`: `failedRemoteRig`, `interruptedRig`.
- `confirmedCurrentDeviceLogoutStagesFullClearAndRetiresLocalSession`: `rig`.
- `terminalIdentityClearBlocksConcurrentSyncUntilLocalDataAndCredentialsAreRetired`: `rig`.
- `failedLogoutPreservesEverythingWhileInterruptedCleanupResumesFromDurableMarker`: `failedRemoteRig`, `interruptedRig`.

### RealSyncPortReconnectTest.kt

Initial state: unjoined original and replacement.

- `retiredNormalCheckDoesNotBroadcastCancellationForANewerApplication`: `first`, `replacement`.

### RealSyncPortAtomicMediaTest.kt

Initial state: joined Member.

- `memberRejoiningSameFamilyPullsCanonicalAvatarWithoutRepublishingBaby`: `rig`.

## Deliberate exclusions

Keep the controlled startup-busy test and startup recovery error/cancellation
wrapper tests unchanged. Preserve resumed family/leave/logout cleanup fixtures;
their terminal continuation may happen after the first mutex block. Preserve
reconcile/media cleanup and disaster-restore contention tests which explicitly
wait for an in-flight public operation before checking the locked behavior.
Ordinary direct joined `sync()` already acquires the mutex without the timed
family-command path, so no blanket fence is added there.

The previously repaired pending-member positive test retains its explicit
unjoined/no-pending public no-op fence. The retained-directory A→B→A tests already
invoke their operation on a real dispatcher. Candidate reconnect uses a separate
mutex; endpoint probes do not enter the timed replica barrier. The separate
possible IO/session-collector versus virtual-time observation race is not proven
by these failures and is not changed here.

Validation must report the focused affected methods, complete Sync Debug/Release,
and the final full JVM run separately. A focused pass does not complete the
unexecuted portion of a failed full run. Device acceptance remains manual/pending.

A repository-wide constructor/caller cross-check also inspected the two source-command
logout suites and five direct-constructor restore suites. They use `runBlocking`,
not the eager virtual scheduler; most logout paths additionally pass through
untimed `prepareSourceCommandLogout()`. No blanket change is made there. Their
explicit reconstructed terminal-cleanup completion signals remain intact.
