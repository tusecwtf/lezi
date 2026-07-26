package com.lezi.babylog.feature.settings

import com.lezi.babylog.domain.LocalRecordsClearCommittedException
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsClearRecordsCopyTest {
    @Test
    fun committedLocalClearFailureExplainsBothReplicaStatesAndRetry() {
        assertEquals(
            "本机记录可能已部分清理，家庭服务器上的记录仍保留，请重试",
            clearRecordsFailureCopy(
                LocalRecordsClearCommittedException(
                    familyServerRetained = true,
                    cause = IllegalStateException("outbox delete failed"),
                ),
            ),
        )
    }

    @Test
    fun unjoinedCommittedFailureDoesNotInventAFamilyServer() {
        assertEquals(
            "本机记录可能已部分清理，请重试",
            clearRecordsFailureCopy(
                LocalRecordsClearCommittedException(
                    familyServerRetained = false,
                    cause = IllegalStateException("settings failed"),
                ),
            ),
        )
    }

    @Test
    fun preCommitFailureKeepsTheGenericFailureCopy() {
        assertEquals(
            "清除失败，请重试",
            clearRecordsFailureCopy(IllegalStateException("database blocked")),
        )
    }
}
