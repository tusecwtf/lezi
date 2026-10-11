package com.lezi.babylog.feature.search

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.sync.session.CreatorAcknowledgementRef
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.sync.session.SyncSession
import org.junit.Test

class SearchPermissionScopeTest {
    private val session = SyncSession(
        familyId = "family",
        membershipId = "member",
        deviceId = "device",
        role = FamilyRole.Member,
        serverHost = "example.test",
        accessToken = "private-access-secret",
        refreshToken = "private-refresh-secret",
    )

    @Test
    fun authorityCredentialAndRecordAcknowledgementChangesInvalidateScope() {
        val baseline = session.toSearchPermissionScope()
        listOf(
            session.copy(familyId = "other-family"),
            session.copy(membershipId = "other-member"),
            session.copy(deviceId = "other-device"),
            session.copy(role = FamilyRole.Owner),
            session.copy(serverHost = "other.test"),
            session.copy(reauthRequired = true),
            session.copy(accessToken = "rotated-access"),
            session.copy(refreshToken = "rotated-refresh"),
            session.copy(pendingCreatorAcknowledgements = setOf(CreatorAcknowledgementRef("record", "record-1"))),
        ).forEach { changed -> assertThat(changed.toSearchPermissionScope()).isNotEqualTo(baseline) }
    }

    @Test
    fun transportProgressFamilyCopyAndNonRecordAcknowledgementsDoNotChangePermissions() {
        assertThat(
            session.copy(
                pullCursor = 99,
                pullGeneration = "generation",
                lastSuccessAt = 123,
                familyName = "new name",
                accessExpiresAtEpochSeconds = 456,
                pendingCreatorAcknowledgements = setOf(CreatorAcknowledgementRef("care_plan", "plan-1")),
            ).toSearchPermissionScope(),
        ).isEqualTo(session.toSearchPermissionScope())
    }

    @Test
    fun acknowledgementOrderIsIrrelevantAndScopeNeverPrintsSecrets() {
        val first = CreatorAcknowledgementRef("record", "record-1")
        val second = CreatorAcknowledgementRef("record", "record-2")
        val scope = session.copy(pendingCreatorAcknowledgements = linkedSetOf(first, second)).toSearchPermissionScope()
        assertThat(scope).isEqualTo(
            session.copy(pendingCreatorAcknowledgements = linkedSetOf(second, first)).toSearchPermissionScope(),
        )
        assertThat(scope.toString()).isEqualTo("SearchPermissionScope(<redacted>)")
        assertThat(scope.toString()).doesNotContain(session.accessToken)
        assertThat(scope.toString()).doesNotContain(session.refreshToken)
    }
}
