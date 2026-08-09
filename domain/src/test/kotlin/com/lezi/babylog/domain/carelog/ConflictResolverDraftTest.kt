package com.lezi.babylog.domain.carelog

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.sync.backend.CausalMediaItem
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test

class ConflictResolverDraftTest {
    @Test
    fun draftExposesOnlyRealPathsAndBuildsChosenRootAndMedia() {
        val detail = ConflictResolverDetail(
            conflictId = "c1",
            stableVersionId = "stable-v1",
            stableRootJson = """{"note":"stable","payload_json":{"amount_ml":60}}""",
            stableMedia = listOf(media("photo-a", "a")),
            branchesJson = """
                [{
                  "branch_version_id":"branch-v1",
                  "root":{"note":"branch","payload_json":{"amount_ml":60}},
                  "media":[{
                    "media_uuid":"photo-b","role":"log","mime":"image/jpeg",
                    "sha256":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                    "byte_size":2
                  }]
                }]
            """.trimIndent(),
            conflictingPaths = listOf(
                "/note",
                "/timestamp",
                "/media/photo-a",
                "/media/photo-b",
            ),
            autoMergedJson = """{"/timestamp":100,"/payload_json/amount_ml":90}""",
            branchVersionIds = listOf("branch-v1"),
            cachedAt = 1,
        )

        val draft = ConflictResolverDraft.from(detail)
            .choose("/note", JsonPrimitive("branch"))
            .choose("/media/photo-a", kotlinx.serialization.json.JsonNull)
            .choose("/media/photo-b", media("photo-b", "b").toJsonElement())

        assertThat(draft.paths.map(ConflictResolverPath::path))
            .containsExactly("/media/photo-a", "/media/photo-b", "/note")
            .inOrder()
        assertThat(draft.resolvedRootJson).contains("\"note\":\"branch\"")
        assertThat(draft.resolvedRootJson).contains("\"amount_ml\":90")
        assertThat(draft.resolvedMedia.map(CausalMediaItem::mediaUuid))
            .containsExactly("photo-b")
        assertThat(draft.conflictChoices.keys)
            .containsExactly("/note", "/media/photo-a", "/media/photo-b")
    }

    @Test
    fun refreshRetainsStillValidChoiceAndDropsChoiceMissingFromNewBranchSet() {
        val initial = ConflictResolverDetail(
            conflictId = "c-refresh",
            stableVersionId = "stable-v1",
            stableRootJson = """{"note":"stable","timestamp":100}""",
            branchesJson =
                """[{"root":{"note":"chosen","timestamp":200},"media":[]}]""",
            conflictingPaths = listOf("/note", "/timestamp"),
            autoMergedJson = "{}",
            branchVersionIds = listOf("branch-v1"),
            cachedAt = 1,
        )
        val chosen = ConflictResolverDraft.from(initial)
            .choose("/note", JsonPrimitive("chosen"))
            .choose("/timestamp", JsonPrimitive(200))
        val refreshed = initial.copy(
            stableVersionId = "stable-v2",
            stableRootJson = """{"note":"new-stable","timestamp":300}""",
            branchesJson =
                """[{"root":{"note":"chosen","timestamp":400},"media":[]}]""",
            branchVersionIds = listOf("branch-v2"),
            cachedAt = 2,
        )

        val next = chosen.refresh(refreshed)

        assertThat(next.detail.stableVersionId).isEqualTo("stable-v2")
        assertThat(next.conflictChoices["/note"]).isEqualTo(JsonPrimitive("chosen"))
        assertThat(next.conflictChoices["/timestamp"]).isEqualTo(JsonPrimitive(300))
    }

    private fun media(uuid: String, hashChar: String) = CausalMediaItem(
        mediaUuid = uuid,
        role = "log",
        mime = "image/jpeg",
        sha256 = hashChar.repeat(64),
        byteSize = 2,
    )
}
