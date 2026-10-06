package com.lezi.babylog.core.common.failure

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.common.looksTechnicalDetail
import org.junit.Test

class FailureCatalogTest {
    @Test
    fun injectedKindsKeepThreeCategoriesAndDistinguishableTitles() {
        val explanations = FailureKind.entries.map { kind ->
            failureExplanation(kind)
        }

        assertThat(explanations.map { it.kind }.toSet()).hasSize(24)
        assertThat(explanations.map { it.category }.toSet()).containsExactly(
            FailureCategory.Network,
            FailureCategory.LocalData,
            FailureCategory.Other,
        )
        assertThat(explanations.map { it.title }.toSet()).hasSize(24)
        assertThat(explanations.map { it.dialogTitle }.toSet()).hasSize(24)

        val addressNotFound = explanation(FailureKind.AddressNotFound)
        val unreachable = explanation(FailureKind.Unreachable)
        assertThat(addressNotFound.category).isEqualTo(FailureCategory.Network)
        assertThat(unreachable.category).isEqualTo(FailureCategory.Network)
        assertThat(addressNotFound.title).isEqualTo("找不到家里的服务器")
        assertThat(unreachable.title).isEqualTo("连不上家里的服务器")
        assertThat(addressNotFound.dialogTitle).isEqualTo("网络问题：找不到家里的服务器")
        assertThat(unreachable.dialogTitle).isEqualTo("网络问题：连不上家里的服务器")
        assertThat(addressNotFound.title).isNotEqualTo(unreachable.title)
        assertThat(addressNotFound.whatHappened).isNotEqualTo(unreachable.whatHappened)
    }

    @Test
    fun everyKindKeepsLocalPresenceAndDropsTechnicalDetail() {
        FailureKind.entries.forEach { kind ->
            val explanation = explanation(kind)
            assertThat(explanation.localDataStatus).isNotEmpty()
            assertThat(explanation.body).contains(explanation.whatHappened)
            assertThat(explanation.body).contains(explanation.likelyCause)
            assertThat(explanation.body).contains(explanation.localDataStatus)
            assertThat(explanation.actions).isNotEmpty()
            explanation.surfaceTexts().forEach { text ->
                assertThat(text).doesNotContain("https://")
                assertThat(text).doesNotContain("192.168.50.4")
                assertThat(text).doesNotContain("8765")
                assertThat(text).doesNotContain("HTTP 503")
                assertThat(text).doesNotContain("ConnectException")
                assertThat(text).doesNotContain("timed out")
                assertThat(looksTechnicalDetail(text)).isFalse()
            }
        }
    }

    @Test
    fun networkKindsUseTableNextStepsAndNeverLookLikeLocalData() {
        assertActions(
            FailureKind.AddressNotFound,
            FailureAction.ChangeAddress,
            FailureAction.StayOffline,
        )
        assertActions(
            FailureKind.Unreachable,
            FailureAction.Retry,
            FailureAction.ChangeAddress,
            FailureAction.StayOffline,
        )
        assertActions(
            FailureKind.CertificateChanged,
            FailureAction.ForgetServerAndReconnect,
        )
        assertActions(
            FailureKind.SendStalled,
            FailureAction.Retry,
            FailureAction.StayOffline,
        )
        assertActions(
            FailureKind.ResponseTimedOut,
            FailureAction.Retry,
            FailureAction.StayOffline,
        )
        assertActions(
            FailureKind.HouseholdUnavailable,
            FailureAction.RetryLater,
            FailureAction.StayOffline,
        )
        assertActions(
            FailureKind.HouseholdSyncing,
            FailureAction.RetryLater,
        )
        assertActions(
            FailureKind.SyncTookTooLong,
            FailureAction.GotIt,
            FailureAction.PullAgainLater,
        )

        NETWORK_KINDS.forEach { kind ->
            val explanation = explanation(kind)
            assertThat(explanation.category).isEqualTo(FailureCategory.Network)
            assertThat(explanation.category.label).isEqualTo("网络问题")
            assertThat(explanation.usesSharedDialog).isTrue()
            assertThat(explanation.dialogTitle).startsWith("网络问题：")
        }
    }

    @Test
    fun localDataKindsStayLocalAndSafetyCheckUsesBlockingPage() {
        val safetyCheck = explanation(FailureKind.SafetyCheckStuck)
        assertThat(safetyCheck.category).isEqualTo(FailureCategory.LocalData)
        assertThat(safetyCheck.category.label).isEqualTo("本机数据问题")
        assertThat(safetyCheck.title).isEqualTo("这台手机的数据还没检查完")
        assertThat(safetyCheck.dialogTitle).isEqualTo("本机数据问题：这台手机的数据还没检查完")
        assertThat(safetyCheck.usesSharedDialog).isFalse()
        assertThat(failureDialogContent(FailureKind.SafetyCheckStuck))
            .isNull()
        assertActions(
            FailureKind.SafetyCheckStuck,
            FailureAction.Retry,
            FailureAction.ExportDiagnostics,
            FailureAction.ClearLocalData,
        )

        val album = explanation(FailureKind.AlbumReadStalled)
        assertThat(album.usesSharedDialog).isTrue()
        assertActions(FailureKind.AlbumReadStalled, FailureAction.Retry, FailureAction.GotIt)
        assertActions(FailureKind.SystemCalendarWriteFailed, FailureAction.GotIt)
        assertActions(
            FailureKind.ExportTookTooLong,
            FailureAction.NarrowRangeAndRetry,
            FailureAction.GoBack,
        )
        assertActions(FailureKind.HouseholdFactRejected, FailureAction.GotIt)
        assertActions(FailureKind.LocalSaveFailed, FailureAction.Retry, FailureAction.GoBack)

        LOCAL_DATA_KINDS.forEach { kind ->
            val explanation = explanation(kind)
            assertThat(explanation.category).isEqualTo(FailureCategory.LocalData)
            assertThat(explanation.dialogTitle).startsWith("本机数据问题：")
        }
    }

    @Test
    fun otherKindsUseTableNextStepsAndNeverLookLikeNetwork() {
        val sessionExpired = explanation(FailureKind.SessionExpired)
        assertThat(sessionExpired.category).isEqualTo(FailureCategory.Other)
        assertThat(sessionExpired.title).isEqualTo("登录已失效")
        assertThat(sessionExpired.dialogTitle).isEqualTo("其他：登录已失效")
        assertActions(FailureKind.SessionExpired, FailureAction.SignInAgain)

        val updateRequired = explanation(FailureKind.AppUpdateRequired)
        assertThat(updateRequired.title).isEqualTo("需要更新乐记")
        assertThat(updateRequired.dialogTitle).isEqualTo("其他：需要更新乐记")
        assertActions(FailureKind.AppUpdateRequired, FailureAction.GoUpdate)

        assertActions(FailureKind.InvalidInput, FailureAction.CheckAndRetry)
        assertActions(FailureKind.TooFast, FailureAction.WaitAndRetry)
        assertActions(FailureKind.HouseholdStateChanged, FailureAction.RefreshAndRetry)
        assertActions(FailureKind.QrExpired, FailureAction.GotIt)
        assertActions(FailureKind.QrWrongHousehold, FailureAction.ScanAgain)
        assertActions(
            FailureKind.ServerHasNoFamily,
            FailureAction.CreateFamily,
            FailureAction.ChangeAddress,
        )
        assertActions(FailureKind.UnexpectedError, FailureAction.Retry, FailureAction.StayOffline)
        assertActions(
            FailureKind.DeviceRemoved,
            FailureAction.SignInAgain,
            FailureAction.StayOffline,
        )

        OTHER_KINDS.forEach { kind ->
            val explanation = explanation(kind)
            assertThat(explanation.category).isEqualTo(FailureCategory.Other)
            assertThat(explanation.category.label).isEqualTo("其他")
            assertThat(explanation.usesSharedDialog).isTrue()
            assertThat(explanation.dialogTitle).startsWith("其他：")
        }
    }

    @Test
    fun sharedDialogShellProjectsTitleBodyAndNextSteps() {
        val content = failureDialogContent(FailureKind.Unreachable)
        checkNotNull(content)
        assertThat(content.title).isEqualTo("网络问题：连不上家里的服务器")
        assertThat(content.body).contains("找到了地址，但敲门没人应")
        assertThat(content.body).contains("家里服务器没开")
        assertThat(content.body).contains("已记下的护理记录还在")
        assertThat(content.actions.map { it.label }).containsExactly(
            "再试一次",
            "改地址",
            "先离线用",
        ).inOrder()
        assertThat(content.title + content.body).doesNotContain("192.168.50.4")
    }

    private fun explanation(kind: FailureKind): FailureExplanation = failureExplanation(kind)

    private fun assertActions(kind: FailureKind, vararg expected: FailureAction) {
        assertThat(explanation(kind).actions).containsExactly(*expected).inOrder()
    }

    private fun FailureExplanation.surfaceTexts(): List<String> = listOf(
        category.label,
        title,
        dialogTitle,
        whatHappened,
        likelyCause,
        localDataStatus,
        body,
    ) + actions.map { it.label }

    private companion object {
        const val LEAKED_TECHNICAL_DETAIL =
            "https://192.168.50.4:8765 HTTP 503 java.net.ConnectException: timed out"

        val NETWORK_KINDS = listOf(
            FailureKind.AddressNotFound,
            FailureKind.Unreachable,
            FailureKind.CertificateChanged,
            FailureKind.SendStalled,
            FailureKind.ResponseTimedOut,
            FailureKind.HouseholdUnavailable,
            FailureKind.HouseholdSyncing,
            FailureKind.SyncTookTooLong,
        )

        val LOCAL_DATA_KINDS = listOf(
            FailureKind.SafetyCheckStuck,
            FailureKind.AlbumReadStalled,
            FailureKind.SystemCalendarWriteFailed,
            FailureKind.ExportTookTooLong,
            FailureKind.HouseholdFactRejected,
            FailureKind.LocalSaveFailed,
        )

        val OTHER_KINDS = listOf(
            FailureKind.SessionExpired,
            FailureKind.AppUpdateRequired,
            FailureKind.InvalidInput,
            FailureKind.TooFast,
            FailureKind.HouseholdStateChanged,
            FailureKind.QrExpired,
            FailureKind.QrWrongHousehold,
            FailureKind.ServerHasNoFamily,
            FailureKind.UnexpectedError,
            FailureKind.DeviceRemoved,
        )
    }
}
