package com.lezi.babylog.sync.session

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.sync.backend.deadline.FamilyHttpException
import com.lezi.babylog.sync.backend.deadline.FamilyHttpFailureKind
import com.lezi.babylog.sync.backend.deadline.FamilyHttpNameResolver
import com.lezi.babylog.sync.backend.retry.SyncRetryClock
import com.lezi.babylog.sync.backend.retry.SyncRetryTime
import java.net.InetAddress
import kotlinx.coroutines.runBlocking
import org.junit.Test

class TrustedEndpointDeadlineTest {
    @Test
    fun setupAndCertificateInspectionStopWhenSuccessfulDnsExhaustsTheProbeBudget() = runBlocking {
        for (inspect in listOf(false, true)) {
            var elapsed = 0L
            val clock = SyncRetryClock { SyncRetryTime(0L, elapsed) }
            val resolver = FamilyHttpNameResolver {
                elapsed = 8_001L
                arrayOf(InetAddress.getLoopbackAddress())
            }
            val endpoint = TrustedEndpointProfile.systemPki("https://localhost:1")
            val failure = runCatching {
                if (inspect) DefaultTlsPeerInspector(resolver, clock).inspect(endpoint)
                else DefaultSetupHttpTransport(resolver, clock).get(SetupHttpRequest(endpoint, "/health"))
            }.exceptionOrNull()
            assertThat(failure).isInstanceOf(FamilyHttpException::class.java)
            assertThat((failure as FamilyHttpException).kind)
                .isEqualTo(FamilyHttpFailureKind.ResponseTimedOut)
        }
    }
}
