package com.lezi.babylog.sync.sourcerelation

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.LocalDataClearScope
import com.lezi.babylog.core.database.causal.CausalTransportJournalEntity
import com.lezi.babylog.sync.MemoryConflictSnapshotCacheDao
import com.lezi.babylog.sync.MemorySourceRelationDao
import com.lezi.babylog.sync.RecordingTransactionRunner
import com.lezi.babylog.sync.SourceCommandLogoutConsentChangedException
import com.lezi.babylog.sync.SourceCommandLogoutState
import com.lezi.babylog.sync.joinedSession
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.sync.session.TrustedEndpointProfile
import java.util.Base64
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class SourceCommandLogoutAdmissionTest {
    @Test
    fun admittedLogoutWithoutConfirmedResponseCannotAuthorizeAnotherFullClear() = runTest {
        val rig = AdmissionRig()
        rig.seed()
        val owner = rig.owner()
        owner.admit(requireNotNull(owner.prepare()))
        val command = rig.command()
        val declaration = rig.relations.listUnsettledDeclarations()
        val approval = rig.approval()

        val result = runCatching { rig.clear(rig.owner()) }

        assertThat(result.exceptionOrNull()).isInstanceOf(SourceCommandLogoutConsentChangedException::class.java)
        assertThat(rig.roomClears).isEqualTo(0)
        assertThat(rig.command()).isEqualTo(command)
        assertThat(rig.relations.listUnsettledDeclarations()).isEqualTo(declaration)
        assertThat(rig.approval()).isEqualTo(approval)
        assertThat(rig.notice()).isNull()
    }

    @Test
    fun restartAfterTerminalFlagBeforeApprovalConfirmationCompletesOneBoundedClear() = runTest {
        val rig = AdmissionRig()
        rig.seed()
        val beforeCrash = rig.owner()
        beforeCrash.admit(requireNotNull(beforeCrash.prepare()))
        // HTTP logout returned, its durable terminal flag committed, then the process
        // died before logoutConfirmed could upgrade the separate Room approval row.
        rig.verifiedTerminalRemoval = true
        assertThat(rig.approval()?.payloadJson).contains("\"phase\":\"admitted\"")

        withTimeout(2_000) { rig.clear(rig.owner()) }

        assertThat(rig.roomClears).isEqualTo(1)
        assertThat(rig.command()).isNull()
        assertThat(rig.relations.listUnsettledDeclarations()).isEmpty()
        assertThat(rig.approval()).isNull()
        val notice = requireNotNull(rig.notice())
        assertThat(notice.payloadJson).doesNotContain("original-operation")
        assertThat(notice.payloadJson).doesNotContain("source-record")
        assertThat(notice.payloadJson).doesNotContain(rig.session.familyId)
        val freshOwner = rig.owner()
        freshOwner.restoreNotice()
        assertThat(freshOwner.notice.value?.unknownCount).isEqualTo(1)
        assertThat(freshOwner.notice.value?.confirmedCount).isEqualTo(0)

        // A further startup has no source work to repeat and retains the same notice.
        withTimeout(2_000) { rig.clear(rig.owner()) }
        assertThat(rig.notice()).isEqualTo(notice)
    }

    @Test
    fun terminalFlagBeforeConfirmationCannotRetireEvidenceFromChangedAuthority() = runTest {
        val changes: List<(AdmissionRig) -> Unit> = listOf(
            { it.session = it.session.copy(familyId = "different-family") },
            { it.session = it.session.copy(membershipId = "different-membership") },
            { it.session = it.session.copy(deviceId = "different-device") },
            { it.session = it.session.copy(pullGeneration = "different-generation") },
            { it.session = it.session.copy(role = FamilyRole.Member) },
            { it.endpoint = TrustedEndpointProfile.systemPki("https://different.example:8765") },
            { it.endpoint = TrustedEndpointProfile.tofuSpki(
                it.endpoint.origin,
                Base64.getEncoder().encodeToString(ByteArray(32) { 9 }),
            ) },
        )
        for (change in changes) {
            val rig = AdmissionRig()
            rig.seed()
            val owner = rig.owner()
            owner.admit(requireNotNull(owner.prepare()))
            rig.verifiedTerminalRemoval = true
            val command = rig.command()
            val declarations = rig.relations.listUnsettledDeclarations()
            val approval = rig.approval()
            change(rig)

            val result = runCatching { rig.clear(rig.owner()) }

            assertThat(result.exceptionOrNull()).isInstanceOf(SourceCommandLogoutConsentChangedException::class.java)
            assertThat(rig.roomClears).isEqualTo(0)
            assertThat(rig.command()).isEqualTo(command)
            assertThat(rig.relations.listUnsettledDeclarations()).isEqualTo(declarations)
            assertThat(rig.approval()).isEqualTo(approval)
            assertThat(rig.notice()).isNull()
        }
    }

    @Test
    fun confirmedLogoutConsentCannotRetireAChangedDeclarationEvenWithTerminalFlag() = runTest {
        val rig = AdmissionRig()
        rig.seed()
        val owner = rig.owner()
        owner.admit(requireNotNull(owner.prepare()))
        owner.logoutConfirmed()
        rig.verifiedTerminalRemoval = true
        val original = rig.relations.listUnsettledDeclarations().single()
        val changed = original.copy(expectedRecordVersion = "changed-version")
        rig.relations.upsertDeclaration(changed)
        val command = rig.command()
        val approval = rig.approval()

        val result = runCatching { rig.clear(rig.owner()) }

        assertThat(result.exceptionOrNull()).isInstanceOf(SourceCommandLogoutConsentChangedException::class.java)
        assertThat(rig.roomClears).isEqualTo(0)
        assertThat(rig.command()).isEqualTo(command)
        assertThat(rig.relations.listUnsettledDeclarations()).containsExactly(changed)
        assertThat(rig.approval()).isEqualTo(approval)
        assertThat(rig.notice()).isNull()
    }

    @Test
    fun confirmedLogoutConsentIncludesJournalEpochEvenWhenPayloadIsUnchanged() = runTest {
        val rig = AdmissionRig()
        rig.seed()
        val owner = rig.owner()
        owner.admit(requireNotNull(owner.prepare()))
        owner.logoutConfirmed()
        val original = requireNotNull(rig.command())
        val changed = original.copy(contentEpoch = original.contentEpoch + 1)
        rig.cache.putTransportJournal(changed.journalKey, changed.payloadJson, changed.contentEpoch)

        val result = runCatching { rig.clear(rig.owner()) }

        assertThat(result.exceptionOrNull()).isInstanceOf(SourceCommandLogoutConsentChangedException::class.java)
        assertThat(rig.roomClears).isEqualTo(0)
        assertThat(rig.command()).isEqualTo(changed)
        assertThat(rig.relations.listUnsettledDeclarations()).hasSize(1)
        assertThat(rig.notice()).isNull()
    }

    @Test
    fun mandatoryTerminalClearFailsClosedForMalformedSourceAuthority() = runTest {
        for (payload in listOf("{broken", "[]", "{}", "{\"authority\":[]}", "{\"authority\":[\"family-a\"]}")) {
            val rig = AdmissionRig()
            rig.cache.putTransportJournal(SOURCE_RELATION_COMMAND_JOURNAL_KEY, payload, 37)
            rig.verifiedTerminalRemoval = true
            val before = rig.command()

            val result = runCatching { rig.clear(rig.owner()) }

            assertThat(result.exceptionOrNull()).isInstanceOf(SourceCommandLogoutConsentChangedException::class.java)
            assertThat(rig.roomClears).isEqualTo(0)
            assertThat(rig.command()).isEqualTo(before)
            assertThat(rig.notice()).isNull()
        }
    }

    @Test
    fun explicitConfirmedLogoutCanRetireExactlyDisclosedMalformedHistoricalIntent() = runTest {
        val rig = AdmissionRig()
        rig.cache.putTransportJournal(SOURCE_RELATION_COMMAND_JOURNAL_KEY, "{broken", 37)
        val owner = rig.owner()
        val consent = requireNotNull(owner.prepare())
        assertThat(consent.state).isEqualTo(SourceCommandLogoutState.Unknown)
        assertThat(consent.serverOrigin).isNull()
        assertThat(consent.requestIds).isNotEmpty()
        owner.admit(consent)
        owner.logoutConfirmed()

        rig.clear(rig.owner())

        assertThat(rig.roomClears).isEqualTo(1)
        assertThat(rig.command()).isNull()
        assertThat(rig.approval()).isNull()
        val notice = Json.parseToJsonElement(requireNotNull(rig.notice()).payloadJson).jsonObject
        assertThat(notice.getValue("unknown").jsonPrimitive.int).isEqualTo(1)
        assertThat(notice.getValue("confirmed").jsonPrimitive.int).isEqualTo(0)
    }

    @Test
    fun mandatoryTerminalClearRejectsUnsettledDeclarationFromAnotherMembership() = runTest {
        val rig = AdmissionRig()
        val declaration = logoutDeclaration(rig.session).copy(authorMembershipId = "previous-membership")
        rig.relations.upsertDeclaration(declaration)
        rig.verifiedTerminalRemoval = true

        val result = runCatching { rig.clear(rig.owner()) }

        assertThat(result.exceptionOrNull()).isInstanceOf(SourceCommandLogoutConsentChangedException::class.java)
        assertThat(rig.roomClears).isEqualTo(0)
        assertThat(rig.relations.listUnsettledDeclarations()).containsExactly(declaration)
        assertThat(rig.notice()).isNull()
    }
}

private class AdmissionRig {
    val cache = MemoryConflictSnapshotCacheDao()
    val relations = MemorySourceRelationDao()
    val transactions = RecordingTransactionRunner()
    var session = joinedSession("family-a")
    var endpoint = TrustedEndpointProfile.systemPki(session.baseUrl)
    var verifiedTerminalRemoval = false
    var roomClears = 0

    fun owner() = SourceCommandLogoutAdmission(
        cache = cache,
        relations = relations,
        transactions = transactions,
        currentSession = { session },
        trustedEndpoint = { endpoint },
        hasVerifiedTerminalRemoval = { verifiedTerminalRemoval },
    )

    suspend fun seed() {
        cache.putTransportJournal(SOURCE_RELATION_COMMAND_JOURNAL_KEY, logoutCommandPayload(session, endpoint), 29)
        relations.upsertDeclaration(logoutDeclaration(session))
    }

    suspend fun clear(owner: SourceCommandLogoutAdmission) = transactions.run {
        owner.aroundClear(LocalDataClearScope.AllLocalData, session) {
            roomClears += 1
            cache.deleteAllTransportJournals()
            relations.deleteAllDeclarations()
        }
    }

    suspend fun command(): CausalTransportJournalEntity? = cache.getTransportJournal(SOURCE_RELATION_COMMAND_JOURNAL_KEY)
    suspend fun approval(): CausalTransportJournalEntity? = cache.getTransportJournal(SourceCommandLogoutAdmission.APPROVAL_KEY)
    suspend fun notice(): CausalTransportJournalEntity? = cache.getTransportJournal(SourceCommandLogoutAdmission.NOTICE_KEY)
}
