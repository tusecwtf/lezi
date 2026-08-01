package com.lezi.babylog.sync.engine
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import kotlinx.coroutines.CancellationException
import org.junit.Test
import com.lezi.babylog.sync.backend.SyncHttpException

class ForegroundSyncRetryPolicyTest {
    @Test
    fun transientNetworkAndServerFailuresUseBoundedBackoff() {
        assertThat(ForegroundSyncRetryPolicy.delayMillis(IOException("offline"), 0))
            .isEqualTo(30_000L)
        assertThat(ForegroundSyncRetryPolicy.delayMillis(SyncHttpException(503), 1))
            .isEqualTo(120_000L)
        assertThat(ForegroundSyncRetryPolicy.delayMillis(SyncHttpException(429), 2))
            .isEqualTo(600_000L)
        assertThat(ForegroundSyncRetryPolicy.delayMillis(SyncHttpException(503), 99))
            .isEqualTo(600_000L)
    }

    @Test
    fun terminalClientAclAndCancellationFailuresNeverAutoRetry() {
        assertThat(ForegroundSyncRetryPolicy.delayMillis(SyncHttpException(400), 0)).isNull()
        assertThat(ForegroundSyncRetryPolicy.delayMillis(SyncHttpException(403), 0)).isNull()
        assertThat(ForegroundSyncRetryPolicy.delayMillis(SyncHttpException(422), 0)).isNull()
        assertThat(ForegroundSyncRetryPolicy.delayMillis(CancellationException(), 0)).isNull()
    }
}
