package com.lezi.babylog.sync.sourcerelation

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.LocalDataClearScope
import com.lezi.babylog.sync.LocalClearWorkflow
import com.lezi.babylog.sync.MemorySyncPreferences
import com.lezi.babylog.sync.RecordingSyncBackend
import com.lezi.babylog.sync.SourceCommandLogoutConsentChangedException
import com.lezi.babylog.sync.SyncRig
import com.lezi.babylog.sync.backend.SyncBackend
import com.lezi.babylog.sync.backend.SyncHttpException
import com.lezi.babylog.sync.joinedSession
import com.lezi.babylog.sync.session.SyncSession
import com.lezi.babylog.sync.session.SyncPreferences
import com.lezi.babylog.sync.session.TrustedEndpointProfile
import java.io.IOException
import java.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test

class SourceCommandLogoutRecoveryTest {
    @Test(timeout = 30_000)
    fun failedHttpLogoutKeepsExactIntentAndConsentForRetry() = runBlocking {
        assertFailedLogoutRetainsEvidence(SyncHttpException(503))
    }

    @Test(timeout = 30_000)
    fun lostLogoutResponseKeepsExactIntentAndConsentForRetry() = runBlocking {
        assertFailedLogoutRetainsEvidence(IOException("logout response lost after dispatch"))
    }

    @Test(timeout = 30_000)
    fun realPortRestartCompletesTerminalFlagCrashWithoutAnotherHttpLogout() = runBlocking {
        val original = joinedSession("family-a")
        val preferences = MemorySyncPreferences(original)
        val interruption = IOException("process stopped after durable terminal flag")
        val interruptedPreferences = object : SyncPreferences by preferences {
            override suspend fun markPendingDeviceRemovalClear() {
                preferences.markPendingDeviceRemovalClear()
                throw interruption
            }
        }
        val first = SyncRig(original, syncPreferences = preferences, ownedPreferences = interruptedPreferences)
        first.awaitStartupRecovery()
        val declaration = seedLogoutEvidence(first)
        val command = first.conflictDetails.getTransportJournal(SOURCE_RELATION_COMMAND_JOURNAL_KEY)
        val consent = requireNotNull(first.port.prepareSourceCommandLogout().getOrThrow())

        val result = first.port.logoutCurrentDevice(consent)

        val reported = requireNotNull(result.exceptionOrNull())
        assertThat(reported).isInstanceOf(IOException::class.java)
        assertThat(reported).hasMessageThat().isEqualTo(interruption.message)
        // Coroutine stack recovery may copy the exception while retaining its exact original cause.
        assertThat(generateSequence(reported) { it.cause }.any { it === interruption }).isTrue()
        assertThat(first.backend.deviceLogoutCalls).isEqualTo(1)
        assertThat(preferences.pendingDeviceRemovalClear).isTrue()
        assertThat(preferences.current()).isEqualTo(original)
        assertThat(first.conflictDetails.getTransportJournal(SOURCE_RELATION_COMMAND_JOURNAL_KEY)).isEqualTo(command)
        assertThat(first.sourceRelations.listUnsettledDeclarations()).containsExactly(declaration)
        assertThat(first.conflictDetails.getTransportJournal(SourceCommandLogoutAdmission.APPROVAL_KEY)?.payloadJson)
            .contains("\"phase\":\"admitted\"")
        assertThat(first.conflictDetails.getTransportJournal(SourceCommandLogoutAdmission.NOTICE_KEY)).isNull()
        val completed = CompletableDeferred<Unit>()
        val resumedPreferences = object : SyncPreferences by preferences {
            override suspend fun clearPendingDeviceRemovalClear() {
                preferences.clearPendingDeviceRemovalClear()
                completed.complete(Unit)
            }
        }

        val restarted = SyncRig(
            original,
            syncPreferences = preferences,
            ownedPreferences = resumedPreferences,
            conflictDetailsOverride = first.conflictDetails,
            sourceRelationsOverride = first.sourceRelations,
        )
        withTimeout(2_000) { completed.await() }

        assertThat(restarted.backend.deviceLogoutCalls).isEqualTo(0)
        assertThat(preferences.current().isJoined).isFalse()
        assertThat(preferences.pendingDeviceRemovalClear).isFalse()
        assertThat(restarted.conflictDetails.getTransportJournal(SOURCE_RELATION_COMMAND_JOURNAL_KEY)).isNull()
        assertThat(restarted.sourceRelations.listUnsettledDeclarations()).isEmpty()
        assertThat(restarted.conflictDetails.getTransportJournal(SourceCommandLogoutAdmission.APPROVAL_KEY)).isNull()
        assertThat(restarted.conflictDetails.getTransportJournal(SourceCommandLogoutAdmission.NOTICE_KEY)).isNotNull()
        Unit
    }

    @Test(timeout = 30_000)
    fun changedGenerationInvalidatesConsentBeforeHttpLogout() = runBlocking {
        val rig = SyncRig(joinedSession("family-a"))
        rig.awaitStartupRecovery()
        seedLogoutEvidence(rig)
        val consent = requireNotNull(rig.port.prepareSourceCommandLogout().getOrThrow())
        val before = rig.conflictDetails.getTransportJournal(SOURCE_RELATION_COMMAND_JOURNAL_KEY)
        rig.preferences.saveSession(rig.preferences.current().copy(pullGeneration = "new-generation"))

        val result = rig.port.logoutCurrentDevice(consent)

        assertThat(result.exceptionOrNull()).isInstanceOf(SourceCommandLogoutConsentChangedException::class.java)
        assertThat(rig.backend.deviceLogoutCalls).isEqualTo(0)
        assertThat(rig.conflictDetails.getTransportJournal(SOURCE_RELATION_COMMAND_JOURNAL_KEY)).isEqualTo(before)
        assertThat(rig.preferences.pendingDeviceRemovalClear).isFalse()
        Unit
    }

    @Test(timeout = 30_000)
    fun changedTrustInvalidatesConsentBeforeHttpLogout() = runBlocking {
        val rig = SyncRig(joinedSession("family-a"))
        rig.awaitStartupRecovery()
        seedLogoutEvidence(rig)
        val consent = requireNotNull(rig.port.prepareSourceCommandLogout().getOrThrow())
        val before = rig.conflictDetails.getTransportJournal(SOURCE_RELATION_COMMAND_JOURNAL_KEY)
        val endpoint = requireNotNull(rig.preferences.verifiedEndpoint.first())
        rig.preferences.rememberEndpoint(TrustedEndpointProfile.tofuSpki(
            endpoint.origin,
            Base64.getEncoder().encodeToString(ByteArray(32) { 7 }),
        ))

        val result = rig.port.logoutCurrentDevice(consent)

        assertThat(result.exceptionOrNull()).isInstanceOf(SourceCommandLogoutConsentChangedException::class.java)
        assertThat(rig.backend.deviceLogoutCalls).isEqualTo(0)
        assertThat(rig.conflictDetails.getTransportJournal(SOURCE_RELATION_COMMAND_JOURNAL_KEY)).isEqualTo(before)
        assertThat(rig.preferences.pendingDeviceRemovalClear).isFalse()
        Unit
    }

    @Test(timeout = 30_000)
    fun changedRequestBytesWithSameRequestIdInvalidateConsentBeforeHttpLogout() = runBlocking {
        val rig = SyncRig(joinedSession("family-a"))
        rig.awaitStartupRecovery()
        seedLogoutEvidence(rig)
        val consent = requireNotNull(rig.port.prepareSourceCommandLogout().getOrThrow())
        val original = requireNotNull(rig.conflictDetails.getTransportJournal(SOURCE_RELATION_COMMAND_JOURNAL_KEY))
        val changed = original.copy(payloadJson = original.payloadJson.replace("source-version", "new-source-version"))
        assertThat(changed.payloadJson).isNotEqualTo(original.payloadJson)
        rig.conflictDetails.putTransportJournal(changed.journalKey, changed.payloadJson, changed.contentEpoch)

        val result = rig.port.logoutCurrentDevice(consent)

        assertThat(result.exceptionOrNull()).isInstanceOf(SourceCommandLogoutConsentChangedException::class.java)
        assertThat(rig.backend.deviceLogoutCalls).isEqualTo(0)
        assertThat(rig.conflictDetails.getTransportJournal(SOURCE_RELATION_COMMAND_JOURNAL_KEY)).isEqualTo(changed)
        assertThat(rig.preferences.pendingDeviceRemovalClear).isFalse()
        Unit
    }

    @Test(timeout = 30_000)
    fun changedDeclarationWithSameRequestIdInvalidatesConsentBeforeHttpLogout() = runBlocking {
        val rig = SyncRig(joinedSession("family-a"))
        rig.awaitStartupRecovery()
        val original = seedLogoutEvidence(rig)
        val consent = requireNotNull(rig.port.prepareSourceCommandLogout().getOrThrow())
        val changed = original.copy(expectedOtherVersion = "new-display-version", status = "failed")
        rig.sourceRelations.upsertDeclaration(changed)

        val result = rig.port.logoutCurrentDevice(consent)

        assertThat(result.exceptionOrNull()).isInstanceOf(SourceCommandLogoutConsentChangedException::class.java)
        assertThat(rig.backend.deviceLogoutCalls).isEqualTo(0)
        assertThat(rig.sourceRelations.getDeclaration(original.mutationId)).isEqualTo(changed)
        assertThat(rig.conflictDetails.getTransportJournal(SOURCE_RELATION_COMMAND_JOURNAL_KEY)).isNotNull()
        assertThat(rig.preferences.pendingDeviceRemovalClear).isFalse()
        Unit
    }

    @Test(timeout = 30_000)
    fun recordsOnlyClearRestoresExactSourceIntentAndUnconfirmedLogoutEvidence() = runBlocking {
        val rig = SyncRig(joinedSession("family-a"))
        rig.awaitStartupRecovery()
        val declaration = seedLogoutEvidence(rig)
        val consent = requireNotNull(rig.port.prepareSourceCommandLogout().getOrThrow())
        rig.backend.deviceLogoutFailure = IOException("response lost")
        assertThat(rig.port.logoutCurrentDevice(consent).isFailure).isTrue()
        val command = requireNotNull(rig.conflictDetails.getTransportJournal(SOURCE_RELATION_COMMAND_JOURNAL_KEY))
        val approval = requireNotNull(rig.conflictDetails.getTransportJournal(SourceCommandLogoutAdmission.APPROVAL_KEY))
        var roomClears = 0
        val workflow = object : LocalClearWorkflow {
            override suspend fun <T> withLocalExclusion(block: suspend () -> T): T = block()
            override suspend fun clearRoom() {
                roomClears += 1
                rig.conflictDetails.deleteAllTransportJournals()
                rig.sourceRelations.deleteAllDeclarations()
            }
            override suspend fun finishCommitted() = Unit
        }

        rig.port.clearLocalData(LocalDataClearScope.RecordsOnly, workflow).getOrThrow()

        assertThat(roomClears).isEqualTo(1)
        assertThat(rig.conflictDetails.getTransportJournal(SOURCE_RELATION_COMMAND_JOURNAL_KEY)).isEqualTo(command)
        assertThat(rig.sourceRelations.listUnsettledDeclarations()).containsExactly(declaration)
        assertThat(rig.conflictDetails.getTransportJournal(SourceCommandLogoutAdmission.APPROVAL_KEY)).isEqualTo(approval)
        assertThat(rig.conflictDetails.getTransportJournal(SourceCommandLogoutAdmission.NOTICE_KEY)).isNull()
        assertThat(rig.preferences.current().isJoined).isTrue()
        assertThat(rig.port.prepareSourceCommandLogout().getOrThrow()?.exactEvidence).isEqualTo(consent.exactEvidence)
        Unit
    }

    private suspend fun assertFailedLogoutRetainsEvidence(failure: Throwable) {
        val sessions = mutableListOf<SyncSession>()
        var nextFailure: Throwable? = failure
        val backend = object : SyncBackend by RecordingSyncBackend() {
            override suspend fun logoutCurrentDevice(session: SyncSession) {
                sessions += session
                nextFailure?.let { throw it }
            }
        }
        val rig = SyncRig(joinedSession("family-a"), syncBackend = backend)
        rig.awaitStartupRecovery()
        val declaration = seedLogoutEvidence(rig)
        val session = rig.preferences.current()
        val command = requireNotNull(rig.conflictDetails.getTransportJournal(SOURCE_RELATION_COMMAND_JOURNAL_KEY))
        val consent = requireNotNull(rig.port.prepareSourceCommandLogout().getOrThrow())

        val failed = rig.port.logoutCurrentDevice(consent)

        assertThat(failed.exceptionOrNull()).isSameInstanceAs(failure)
        assertThat(sessions).containsExactly(session)
        assertThat(rig.preferences.current()).isEqualTo(session)
        assertThat(rig.preferences.pendingDeviceRemovalClear).isFalse()
        assertThat(rig.conflictDetails.getTransportJournal(SOURCE_RELATION_COMMAND_JOURNAL_KEY)).isEqualTo(command)
        assertThat(rig.sourceRelations.listUnsettledDeclarations()).containsExactly(declaration)
        assertThat(rig.conflictDetails.getTransportJournal(SourceCommandLogoutAdmission.NOTICE_KEY)).isNull()
        val approval = requireNotNull(rig.conflictDetails.getTransportJournal(SourceCommandLogoutAdmission.APPROVAL_KEY))
        assertThat(approval.payloadJson).contains("\"phase\":\"admitted\"")
        assertThat(rig.port.prepareSourceCommandLogout().getOrThrow()?.exactEvidence).isEqualTo(consent.exactEvidence)
        assertThat(rig.conflictDetails.getTransportJournal(SourceCommandLogoutAdmission.APPROVAL_KEY)).isEqualTo(approval)

        nextFailure = null
        rig.port.logoutCurrentDevice(consent).getOrThrow()

        assertThat(sessions).containsExactly(session, session).inOrder()
        assertThat(rig.preferences.current().isJoined).isFalse()
        assertThat(rig.preferences.pendingDeviceRemovalClear).isFalse()
        assertThat(rig.conflictDetails.getTransportJournal(SOURCE_RELATION_COMMAND_JOURNAL_KEY)).isNull()
        assertThat(rig.sourceRelations.listUnsettledDeclarations()).isEmpty()
        assertThat(rig.conflictDetails.getTransportJournal(SourceCommandLogoutAdmission.APPROVAL_KEY)).isNull()
        assertThat(rig.conflictDetails.getTransportJournal(SourceCommandLogoutAdmission.NOTICE_KEY)).isNotNull()
    }
}
