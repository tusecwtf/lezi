package com.lezi.babylog.sync.session

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.sync.backend.SyncHttpException
import org.junit.Test

class FamilySyncErrorProductCopyTest {
    @Test
    fun httpFailuresMapToActionCopyWithoutStatusOrServerDetail() {
        val serverFailure = familySyncError(
            SyncHttpException(
                statusCode = 500,
                responseBody = """{"detail":"sqlite database is locked at /data/lezi.db"}""",
            ),
            fallback = "同步失败，请稍后重试",
        )
        val unauthorized = familySyncError(
            SyncHttpException(401, """{"detail":"token signature mismatch"}"""),
            fallback = "同步失败，请稍后重试",
        )

        assertThat(serverFailure).isEqualTo("家庭服务器暂时不可用，请稍后重试")
        assertThat(unauthorized).isEqualTo("登录已失效，请重新登录或联系家庭管理员")
        assertThat(serverFailure).doesNotContain("HTTP")
        assertThat(serverFailure).doesNotContain("sqlite")
        assertThat(unauthorized).doesNotContain("token")
    }
}
