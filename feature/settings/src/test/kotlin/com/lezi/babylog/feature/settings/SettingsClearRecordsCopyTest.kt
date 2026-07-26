package com.lezi.babylog.feature.settings

import com.lezi.babylog.domain.LocalRecordsClearCommittedException
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsClearRecordsCopyTest {
    @Test
    fun joinedFamilyCopyExplainsLocalScopeServerRetentionAndRedownload() {
        val copy = clearRecordsConfirmationCopy(isFamilyJoined = true)

        assertEquals(
            "只清除这台设备上的喂养、睡眠等记录；宝宝档案和家庭服务器上的记录仍保留。",
            copy.firstPrompt,
        )
        assertEquals(
            "真的要清除本机全部记录吗？宝宝不会被删除；下次家庭同步时，服务器上的记录可能重新下载。",
            copy.finalPrompt,
        )
    }

    @Test
    fun unjoinedCopyExplainsOnlyLocalScopeAndNeverInventsFamilySync() {
        val copy = clearRecordsConfirmationCopy(isFamilyJoined = false)

        assertEquals(
            "只清除这台设备上的喂养、睡眠等记录；宝宝档案会保留。",
            copy.firstPrompt,
        )
        assertEquals(
            "真的要清除本机全部记录吗？宝宝档案会保留。",
            copy.finalPrompt,
        )
        listOf(copy.firstPrompt, copy.finalPrompt).forEach { prompt ->
            check("家庭服务器" !in prompt)
            check("家庭同步" !in prompt)
            check("重新下载" !in prompt)
        }
    }

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
