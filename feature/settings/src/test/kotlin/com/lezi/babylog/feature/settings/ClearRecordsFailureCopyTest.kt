package com.lezi.babylog.feature.settings

import com.lezi.babylog.sync.clear.localClearCommittedFailure
import org.junit.Assert.assertEquals
import org.junit.Test

class ClearRecordsFailureCopyTest {
    @Test
    fun retainedFamilyServerUsesTheExistingRetainedCopy() {
        val failure = localClearCommittedFailure(
            familyServerRetained = true,
            cause = IllegalStateException("cleanup failed"),
        )

        assertEquals(
            "本机记录可能已部分清理，家庭服务器上的记录仍保留，请重试",
            clearRecordsFailureCopy(failure),
        )
    }

    @Test
    fun localOnlyCommittedFailureUsesTheExistingRetryCopy() {
        val failure = localClearCommittedFailure(
            familyServerRetained = false,
            cause = IllegalStateException("cleanup failed"),
        )

        assertEquals(
            "本机记录可能已部分清理，请重试",
            clearRecordsFailureCopy(failure),
        )
    }
}
