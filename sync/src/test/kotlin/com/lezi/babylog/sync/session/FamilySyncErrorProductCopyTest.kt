package com.lezi.babylog.sync.session

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.common.failure.FailureCategory
import com.lezi.babylog.core.common.failure.FailureKind
import com.lezi.babylog.core.common.failure.failureExplanation
import com.lezi.babylog.sync.MemberLoginQrUnavailableException
import com.lezi.babylog.sync.backend.ClientUpdateRequiredException
import com.lezi.babylog.sync.backend.ReauthRequiredException
import com.lezi.babylog.sync.backend.AuthorityProofException
import com.lezi.babylog.sync.backend.CausalCommitRejectedException
import com.lezi.babylog.sync.backend.SyncHandshakeRejectedException
import com.lezi.babylog.sync.backend.SyncHttpException
import com.lezi.babylog.core.common.failure.LocalPersistException
import com.lezi.babylog.sync.backend.deadline.FamilyHttpConnectTimeoutException
import com.lezi.babylog.sync.backend.deadline.FamilyHttpException
import com.lezi.babylog.sync.backend.deadline.FamilyHttpFailureKind
import com.lezi.babylog.sync.backend.deadline.FamilyHttpWriteStallException
import com.lezi.babylog.sync.backend.deadline.toCatalogKind
import com.lezi.babylog.sync.backend.retry.SyncRetryBudgetExceededException
import com.lezi.babylog.sync.backend.retry.SyncRetryOperation
import java.net.ConnectException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test

class FamilySyncErrorProductCopyTest {
    @Test
    fun httpFailuresClassifyToCatalogKindsWithoutLeakingStatusOrServerDetail() {
        val serverFailure = SyncHttpException(
            statusCode = 500,
            responseBody = """{"detail":"sqlite database is locked at /data/lezi.db"}""",
        )
        val unauthorized = SyncHttpException(401, """{"detail":"token signature mismatch"}""")

        assertThat(familyFailureKind(serverFailure)).isEqualTo(FailureKind.HouseholdUnavailable)
        assertThat(familyFailureKind(unauthorized)).isEqualTo(FailureKind.SessionExpired)
        val serverCopy = failureExplanation(FailureKind.HouseholdUnavailable)
        val sessionCopy = failureExplanation(FailureKind.SessionExpired)
        assertThat(serverCopy.title).isEqualTo("家里服务器暂时不可用")
        assertThat(sessionCopy.title).isEqualTo("登录已失效")
        assertThat(serverCopy.dialogTitle).doesNotContain("HTTP")
        assertThat(serverCopy.whatHappened).doesNotContain("sqlite")
        assertThat(sessionCopy.whatHappened).doesNotContain("token")
    }

    @Test
    fun familyHttpFailuresMapToCatalogKindsWithoutEnglishCauseText() {
        FamilyHttpFailureKind.entries.forEach { kind ->
            val error = FamilyHttpException(kind, SocketTimeoutException("connect timed out"))
            assertThat(familyFailureKind(error)).isEqualTo(kind.toCatalogKind())
            assertThat(error.message).isEqualTo("family-http:${kind.name}")
            assertThat(error.message).doesNotContain("timed out")
            val catalog = failureExplanation(kind.toCatalogKind())
            assertThat(catalog.title).doesNotContain("Exception")
            assertThat(catalog.title).doesNotContain("timed out")
        }
    }

    @Test
    fun familyHttpFailuresClassifyAsNetworkKindsWithDistinguishableTitles() {
        val classified = FamilyHttpFailureKind.entries.associateWith { kind ->
            familyFailureKind(FamilyHttpException(kind, SocketTimeoutException("connect timed out")))
        }

        assertThat(classified[FamilyHttpFailureKind.AddressNotFound])
            .isEqualTo(FailureKind.AddressNotFound)
        assertThat(classified[FamilyHttpFailureKind.Unreachable])
            .isEqualTo(FailureKind.Unreachable)
        assertThat(classified[FamilyHttpFailureKind.SendStalled])
            .isEqualTo(FailureKind.SendStalled)
        assertThat(classified[FamilyHttpFailureKind.ResponseTimedOut])
            .isEqualTo(FailureKind.ResponseTimedOut)
        assertThat(classified[FamilyHttpFailureKind.SyncTookTooLong])
            .isEqualTo(FailureKind.SyncTookTooLong)
        assertThat(classified[FamilyHttpFailureKind.HouseholdSyncing])
            .isEqualTo(FailureKind.HouseholdSyncing)

        val titles = classified.values.map { failureExplanation(it!!).dialogTitle }
        assertThat(titles.toSet()).hasSize(titles.size)
        titles.forEach { title ->
            assertThat(title).startsWith("网络问题：")
            assertThat(title).doesNotContain("timed out")
            assertThat(title).doesNotContain("Exception")
        }
        assertThat(classified.values).doesNotContain(FailureKind.SafetyCheckStuck)
        assertThat(classified.values).doesNotContain(FailureKind.SessionExpired)
    }

    @Test
    fun httpStatusAndSessionExceptionsClassifyAsCatalogKindsWithoutLeakingDetail() {
        assertThat(
            familyFailureKind(SyncHttpException(500, """{"detail":"sqlite locked"}""")),
        ).isEqualTo(FailureKind.HouseholdUnavailable)
        assertThat(familyFailureKind(SyncHttpException(401, """{"detail":"token"}""")))
            .isEqualTo(FailureKind.SessionExpired)
        assertThat(familyFailureKind(SyncHttpException(403, "")))
            .isEqualTo(FailureKind.SessionExpired)
        assertThat(familyFailureKind(ReauthRequiredException()))
            .isEqualTo(FailureKind.SessionExpired)
        assertThat(familyFailureKind(SyncHttpException(409, "")))
            .isEqualTo(FailureKind.HouseholdStateChanged)
        assertThat(familyFailureKind(SyncHttpException(422, "")))
            .isEqualTo(FailureKind.HouseholdStateChanged)
        assertThat(
            familyFailureKind(SyncHttpException(422, """{"detail":"称呼不能为空"}""")),
        ).isEqualTo(FailureKind.InvalidInput)
        assertThat(familyFailureKind(SyncHttpException(429, "")))
            .isEqualTo(FailureKind.TooFast)
        assertThat(familyFailureKind(ClientUpdateRequiredException()))
            .isEqualTo(FailureKind.AppUpdateRequired)
        assertThat(familyFailureKind(MemberLoginQrUnavailableException()))
            .isEqualTo(FailureKind.QrExpired)
        assertThat(familyFailureKind(CancellationException("user cancelled"))).isNull()

        val other = listOf(
            FailureKind.SessionExpired,
            FailureKind.HouseholdStateChanged,
            FailureKind.InvalidInput,
            FailureKind.TooFast,
            FailureKind.AppUpdateRequired,
            FailureKind.QrExpired,
        ).map { failureExplanation(it) }
        other.forEach { explanation ->
            assertThat(explanation.category).isEqualTo(FailureCategory.Other)
            assertThat(explanation.dialogTitle).startsWith("其他：")
        }
        assertThat(failureExplanation(FailureKind.HouseholdUnavailable).category)
            .isEqualTo(FailureCategory.Network)
        assertThat(failureExplanation(FailureKind.HouseholdUnavailable).dialogTitle)
            .isNotEqualTo(failureExplanation(FailureKind.Unreachable).dialogTitle)
    }

    @Test
    fun storedVersionReloadCodeClassifiesAsHouseholdStateChangedNotUnavailable() {
        val codedConflict = SyncHttpException(
            409,
            """{"code":"invalid_stored_payload","detail":"stored entity payload is invalid"}""",
        )
        val codedInternal = SyncHttpException(
            500,
            """{"code":"invalid_stored_payload","detail":"stored entity payload is invalid"}""",
        )
        val uncodedInternal = SyncHttpException(500, """{"detail":"sqlite locked"}""")

        assertThat(familyFailureKind(codedConflict)).isEqualTo(FailureKind.HouseholdStateChanged)
        assertThat(familyFailureKind(codedInternal)).isEqualTo(FailureKind.HouseholdStateChanged)
        assertThat(familyFailureKind(uncodedInternal)).isEqualTo(FailureKind.HouseholdUnavailable)

        val copy = failureExplanation(FailureKind.HouseholdStateChanged)
        assertThat(copy.category).isEqualTo(FailureCategory.Other)
        assertThat(copy.dialogTitle).doesNotContain("检修")
        assertThat(copy.dialogTitle).doesNotContain("重启")
        assertThat(copy.body).doesNotContain("检修")
        assertThat(copy.body).doesNotContain("重启")
        assertThat(copy.dialogTitle).doesNotContain("连不上")
        assertThat(copy.dialogTitle).doesNotContain("暂时不可用")
        assertThat(failureExplanation(FailureKind.HouseholdUnavailable).body).contains("检修")
    }

    @Test
    fun probeFailuresClassifyNetworkAndOtherKindsWithoutMergingAddressAndUnreachable() {
        assertThat(familyFailureKind(SetupProbeResult.Failed.AddressNotFound))
            .isEqualTo(FailureKind.AddressNotFound)
        assertThat(familyFailureKind(SetupProbeResult.Failed.Unreachable))
            .isEqualTo(FailureKind.Unreachable)
        assertThat(familyFailureKind(SetupProbeResult.Failed.CertificateChanged))
            .isEqualTo(FailureKind.CertificateChanged)
        assertThat(familyFailureKind(SetupProbeResult.Failed.Maintenance))
            .isEqualTo(FailureKind.HouseholdUnavailable)
        assertThat(familyFailureKind(SetupProbeResult.Failed.InvalidAddress))
            .isEqualTo(FailureKind.InvalidInput)
        assertThat(familyFailureKind(SetupProbeResult.Failed.Incompatible))
            .isEqualTo(FailureKind.AppUpdateRequired)
        assertThat(familyFailureKind(SetupProbeResult.Failed.NotLezi))
            .isEqualTo(FailureKind.InvalidInput)
        assertThat(familyFailureKind(SetupProbeResult.Failed.ResponseTimedOut))
            .isEqualTo(FailureKind.ResponseTimedOut)
        assertThat(failureExplanation(familyFailureKind(SetupProbeResult.Failed.NotLezi)))
            .isNotEqualTo(failureExplanation(FailureKind.Unreachable))
        val waitTimeout = failureExplanation(FailureKind.ResponseTimedOut)
        assertThat(waitTimeout.title).isEqualTo("家里服务器没有及时回应")
        assertThat(waitTimeout.actions).doesNotContain(
            com.lezi.babylog.core.common.failure.FailureAction.ChangeAddress,
        )
        assertThat(waitTimeout.dialogTitle).isNotEqualTo(
            failureExplanation(FailureKind.Unreachable).dialogTitle,
        )
        assertThat(failureExplanation(FailureKind.InvalidInput).whatHappened)
            .doesNotContain("敲门没人应")

        val address = failureExplanation(FailureKind.AddressNotFound)
        val unreachable = failureExplanation(FailureKind.Unreachable)
        assertThat(address.category).isEqualTo(FailureCategory.Network)
        assertThat(unreachable.category).isEqualTo(FailureCategory.Network)
        assertThat(address.title).isNotEqualTo(unreachable.title)
        assertThat(address.dialogTitle).isEqualTo("网络问题：找不到家里的服务器")
        assertThat(unreachable.dialogTitle).isEqualTo("网络问题：连不上家里的服务器")
    }

    @Test
    fun timeoutAndBudgetExhaustionEnterCatalogKindsInsteadOfMissingKind() {
        assertThat(familyFailureKind(timeoutCancellation()))
            .isEqualTo(FailureKind.ResponseTimedOut)
        assertThat(familyFailureKind(SyncRetryBudgetExceededException(SyncRetryOperation.Pull)))
            .isEqualTo(FailureKind.ResponseTimedOut)
        assertThat(familyFailureKind(FamilyHttpException(FamilyHttpFailureKind.SyncTookTooLong)))
            .isEqualTo(FailureKind.SyncTookTooLong)

        val timedOut = failureExplanation(FailureKind.ResponseTimedOut)
        val tooLong = failureExplanation(FailureKind.SyncTookTooLong)
        assertThat(timedOut.category).isEqualTo(FailureCategory.Network)
        assertThat(tooLong.category).isEqualTo(FailureCategory.Network)
        assertThat(timedOut.title).isNotEqualTo(tooLong.title)
    }

    @Test
    fun writeStallAndConnectTimeoutClassifyByTypeNotSocketMessage() {
        assertThat(familyFailureKind(FamilyHttpWriteStallException(80)))
            .isEqualTo(FailureKind.SendStalled)
        assertThat(familyFailureKind(FamilyHttpConnectTimeoutException(SocketTimeoutException("x"))))
            .isEqualTo(FailureKind.Unreachable)
        assertThat(familyFailureKind(SocketTimeoutException("write timed out")))
            .isEqualTo(FailureKind.ResponseTimedOut)
        assertThat(familyFailureKind(SocketTimeoutException("connect timed out")))
            .isEqualTo(FailureKind.ResponseTimedOut)
        assertThat(familyFailureKind(ConnectException("connection refused")))
            .isEqualTo(FailureKind.Unreachable)

        assertThat(failureExplanation(FailureKind.SendStalled).category)
            .isEqualTo(FailureCategory.Network)
        assertThat(failureExplanation(FailureKind.ResponseTimedOut).category)
            .isEqualTo(FailureCategory.Network)
    }

    @Test
    fun localPersistAndUnknownFallbackAreNotNetworkKinds() {
        val persist = familyFailureKind(LocalPersistException(IllegalStateException("disk full")))
        assertThat(persist).isEqualTo(FailureKind.LocalSaveFailed)
        val persistCopy = failureExplanation(persist!!)
        assertThat(persistCopy.category).isEqualTo(FailureCategory.LocalData)
        assertThat(persistCopy.actions).doesNotContain(
            com.lezi.babylog.core.common.failure.FailureAction.ChangeAddress,
        )
        assertThat(persistCopy.dialogTitle).doesNotContain("连不上")
        assertThat(persistCopy.dialogTitle).doesNotContain("填写")

        val unknown = familyFailureKind(IllegalStateException("unexpected"))
        assertThat(unknown).isEqualTo(FailureKind.UnexpectedError)
        assertThat(
            familyFailureKind(IllegalStateException("睡眠状态已变化，请重新打开睡眠菜单")),
        ).isEqualTo(FailureKind.HouseholdStateChanged)
        assertThat(failureExplanation(unknown!!).category).isEqualTo(FailureCategory.Other)
        assertThat(failureExplanation(FailureKind.UnexpectedError).title).doesNotContain("填写")

        val network = familyFailureKind(ConnectException("refused"))
        assertThat(failureExplanation(network!!).category).isEqualTo(FailureCategory.Network)
        assertThat(network).isNotEqualTo(FailureKind.SafetyCheckStuck)
    }

    @Test
    fun removalOutcomeExceptionsGetDedicatedKindsNotFormCopy() {
        assertThat(familyFailureKind(com.lezi.babylog.sync.backend.RemoteDeviceRemovedException()))
            .isEqualTo(FailureKind.DeviceRemoved)
        assertThat(familyFailureKind(com.lezi.babylog.sync.backend.RemoteMembershipDeletedException()))
            .isEqualTo(FailureKind.DeviceRemoved)
        assertThat(familyFailureKind(com.lezi.babylog.sync.backend.RemoteFamilyDeletedException()))
            .isEqualTo(FailureKind.ServerHasNoFamily)
        assertThat(familyFailureKind(com.lezi.babylog.sync.BootstrapSecretRejectedException()))
            .isEqualTo(FailureKind.SessionExpired)
        assertThat(familyFailureKind(com.lezi.babylog.sync.OwnerRootPasswordRejectedException()))
            .isEqualTo(FailureKind.SessionExpired)

        val removedCopy = failureExplanation(FailureKind.DeviceRemoved)
        assertThat(removedCopy.title).isEqualTo("这台手机已不在家庭里")
        assertThat(removedCopy.body).contains("管理员")
        assertThat(removedCopy.body).doesNotContain("填写")
        assertThat(removedCopy.body).doesNotContain("称呼")
        assertThat(isUnrecoverableForegroundStop(
            com.lezi.babylog.sync.backend.RemoteDeviceRemovedException(),
        )).isTrue()
    }

    @Test
    fun replicaCycleFailuresAreHouseholdStateNotNicknameCopy() {
        val leftover = ReplicaSyncNotConvergedException("家庭同步尚未收敛，本机待同步项仍保留")
        assertThat(familyFailureKind(leftover)).isEqualTo(FailureKind.HouseholdStateChanged)
        // Typed cycle-state failures classify by type even with reworded messages.
        assertThat(
            familyFailureKind(
                ReplicaCycleStateException("任意改写过的引擎措辞，marker 不再命中"),
            ),
        ).isEqualTo(FailureKind.HouseholdStateChanged)
        assertThat(failureExplanation(FailureKind.HouseholdStateChanged).likelyCause)
            .doesNotContain("称呼")
        assertThat(failureExplanation(FailureKind.HouseholdStateChanged).whatHappened)
            .doesNotContain("草稿")
        assertThat(failureExplanation(FailureKind.HouseholdStateChanged).likelyCause)
            .doesNotContain("邀请")
        assertThat(shouldContinueIncompleteForegroundCycle(leftover, progressMade = true))
            .isTrue()
        assertThat(shouldContinueIncompleteForegroundCycle(leftover, progressMade = false))
            .isTrue()
        assertThat(
            familyFailureKind(IllegalArgumentException("家庭服务器同步超过 500 页上限，请稍后重试")),
        ).isEqualTo(FailureKind.HouseholdStateChanged)

        assertThat(
            familyFailureKind(IllegalArgumentException("同步数据引用尚未就绪，保留 cursor 以便重试")),
        ).isEqualTo(FailureKind.HouseholdFactRejected)
        assertThat(
            familyFailureKind(IllegalArgumentException("家庭服务器在同一 pull 页重复返回实体")),
        ).isEqualTo(FailureKind.HouseholdStateChanged)
        assertThat(
            familyFailureKind(IllegalStateException("家庭同步依赖在 8 轮内未收敛，请稍后重试")),
        ).isEqualTo(FailureKind.HouseholdStateChanged)

        assertThat(
            familyFailureKind(AuthorityProofException("generation-a", IllegalArgumentException("x"))),
        ).isEqualTo(FailureKind.HouseholdStateChanged)
        assertThat(
            familyFailureKind(CausalCommitRejectedException("mut-a", "content_drift")),
        ).isEqualTo(FailureKind.HouseholdFactRejected)
        assertThat(
            familyFailureKind(CausalCommitRejectedException("mut-a", "invalid_domain")),
        ).isEqualTo(FailureKind.HouseholdFactRejected)
        assertThat(
            familyFailureKind(CausalCommitRejectedException("mut-a", "unauthenticated")),
        ).isEqualTo(FailureKind.SessionExpired)
        assertThat(
            familyFailureKind(CausalCommitRejectedException("mut-a", "not_ready")),
        ).isEqualTo(FailureKind.HouseholdUnavailable)
        assertThat(
            familyFailureKind(CausalCommitRejectedException("mut-a", "capability_mismatch")),
        ).isEqualTo(FailureKind.AppUpdateRequired)
        assertThat(familyFailureKind(SyncHandshakeRejectedException("capability_mismatch")))
            .isEqualTo(FailureKind.AppUpdateRequired)
        assertThat(familyFailureKind(SyncHandshakeRejectedException("unauthenticated")))
            .isEqualTo(FailureKind.SessionExpired)
        assertThat(familyFailureKind(SyncHandshakeRejectedException("not_ready")))
            .isEqualTo(FailureKind.HouseholdUnavailable)

        val form = familyFailureKind(IllegalArgumentException("请填写家庭称呼"))
        assertThat(form).isEqualTo(FailureKind.InvalidInput)
        assertThat(familyFailureKind(IllegalStateException("unexpected")))
            .isEqualTo(FailureKind.UnexpectedError)
    }

    @Test
    fun transportTitlesComeFromSharedCatalogNotASecondTable() {
        FamilyHttpFailureKind.entries.forEach { kind ->
            val catalog = failureExplanation(kind.toCatalogKind())
            assertThat(familyFailureKind(FamilyHttpException(kind))).isEqualTo(kind.toCatalogKind())
            assertThat(catalog.category).isEqualTo(FailureCategory.Network)
            assertThat(catalog.title).isNotEmpty()
        }
    }

    private fun timeoutCancellation(): TimeoutCancellationException =
        runCatching {
            runBlocking { withTimeout(1) { delay(50) } }
        }.exceptionOrNull() as TimeoutCancellationException
}
