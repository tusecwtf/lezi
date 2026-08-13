package com.lezi.babylog.domain.carelog

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.sync.backend.CausalMediaItem
import com.lezi.babylog.sync.conflict.AutoMergedPath
import com.lezi.babylog.sync.conflict.ConflictCandidate
import com.lezi.babylog.sync.conflict.ConflictOutcome
import com.lezi.babylog.sync.conflict.ConflictRoot
import com.lezi.babylog.sync.conflict.ConflictRootType
import com.lezi.babylog.sync.conflict.ConflictSnapshot
import com.lezi.babylog.sync.conflict.ConflictSource
import com.lezi.babylog.sync.conflict.ConflictVersionSnapshot
import com.lezi.babylog.sync.conflict.ConflictingPath
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

class ConflictResolverDraftTest {
    @Test
    fun fiveRootSnapshotsUseRootSpecificFieldLabels() {
        val cases = listOf(
            Triple(ConflictRootType.Baby, babyRoot(), "/nickname" to "宝宝昵称"),
            Triple(ConflictRootType.Record, recordRoot(), "/timestamp" to "发生时间"),
            Triple(ConflictRootType.CarePlan, carePlanRoot(), "/scheduled_at" to "计划时间"),
            Triple(ConflictRootType.CustomItem, customItemRoot(), "/name" to "项目名称"),
            Triple(
                ConflictRootType.WakeObservation,
                wakeRoot(),
                "/wake_timestamp" to "醒来时间",
            ),
        )

        cases.forEachIndexed { index, (type, root, expected) ->
            val draft = ConflictResolverDraft.open(
                snapshot = snapshot(type, root, expected.first),
                audience = ConflictResolverAudience("member-author", isOwner = false),
                fetchedOnline = true,
                nowMillis = 1_000,
                resolutionMutationId = mutationId(index),
            )

            assertThat(draft.model.entityType).isEqualTo(type)
            assertThat(draft.model.paths.single().label).isEqualTo(expected.second)
            assertThat(draft.model.paths.single().options).hasSize(2)
            assertThat(draft.model.paths.single().options.first().provenance)
                .contains("member-author")
        }
    }

    @Test
    fun everyConflictPathRequiresExactlyOneOpaqueChoiceBeforeCommandExists() {
        val draft = ConflictResolverDraft.open(
            snapshot = snapshot(
                ConflictRootType.Record,
                recordRoot(),
                "/note",
                "/timestamp",
            ),
            audience = ConflictResolverAudience("member-author", isOwner = false),
            fetchedOnline = true,
            nowMillis = 1_000,
            resolutionMutationId = mutationId(10),
        )

        assertThat(draft.complete).isFalse()
        assertThat(draft.canSubmit).isFalse()
        assertThat(runCatching { draft.command() }.isFailure).isTrue()
        assertThat(runCatching { draft.choose("/foreign", choiceId("/note", true)) }.isFailure)
            .isTrue()

        val first = draft.choose("/note", choiceId("/note", true))
        assertThat(first.complete).isFalse()
        val complete = first.choose("/timestamp", choiceId("/timestamp", false))
        assertThat(complete.complete).isTrue()
        assertThat(complete.canSubmit).isTrue()
        val frozen = complete.freeze()
        assertThat(frozen.command().snapshotToken).isEqualTo(SNAPSHOT_TOKEN)
        assertThat(frozen.command().resolutionMutationId).isEqualTo(mutationId(10))
        assertThat(frozen.command().choices.map { it.path })
            .containsExactly("/note", "/timestamp").inOrder()
        assertThat(frozen.command().choices.map { it.choiceId })
            .containsExactly(choiceId("/note", true), choiceId("/timestamp", false)).inOrder()
        assertThat(
            runCatching { frozen.choose("/note", choiceId("/note", false)) }.isFailure,
        ).isTrue()

        val replaced = complete.choose("/note", choiceId("/note", false))
        assertThat(replaced.freeze().command().choices).hasSize(2)
        assertThat(replaced.selectedChoiceIds["/note"])
            .isEqualTo(choiceId("/note", false))
    }

    @Test
    fun partialSnapshotIsRejectedAndExpiredOfflineUnauthorizedSnapshotsAreReadOnly() {
        val current = snapshot(ConflictRootType.Record, recordRoot(), "/note")
        assertThat(
            runCatching {
                ConflictResolverDraft.open(
                    current.copy(complete = false, continuation = "next"),
                    ConflictResolverAudience("member-author", false),
                    fetchedOnline = true,
                    nowMillis = 1_000,
                    resolutionMutationId = mutationId(20),
                )
            }.isFailure,
        ).isTrue()
        val cases = listOf(
            ConflictResolverDraft.open(
                current.copy(expiresAt = 1_000),
                ConflictResolverAudience("member-author", false),
                fetchedOnline = true,
                nowMillis = 1_000,
                resolutionMutationId = mutationId(21),
            ) to ConflictResolverAvailability.Expired,
            ConflictResolverDraft.open(
                current,
                ConflictResolverAudience("member-author", false),
                fetchedOnline = false,
                nowMillis = 1_000,
                resolutionMutationId = mutationId(22),
            ) to ConflictResolverAvailability.Offline,
            ConflictResolverDraft.open(
                current,
                ConflictResolverAudience("member-other", false),
                fetchedOnline = true,
                nowMillis = 1_000,
                resolutionMutationId = mutationId(23),
            ) to ConflictResolverAvailability.Forbidden,
        )

        cases.forEach { (draft, expected) ->
            assertThat(draft.model.availability).isEqualTo(expected)
            assertThat(
                runCatching { draft.choose("/note", choiceId("/note", true)) }.isFailure,
            ).isTrue()
        }
        val owner = ConflictResolverDraft.open(
            current,
            ConflictResolverAudience("member-other", true),
            fetchedOnline = true,
            nowMillis = 1_000,
            resolutionMutationId = mutationId(24),
        ).choose("/note", choiceId("/note", true))
        assertThat(owner.canSubmit).isTrue()
    }

    @Test
    fun fiveRootAclTableKeepsBabyOwnerOnlyAndOtherAuthorsAuthorized() {
        val roots = listOf(
            ConflictRootType.Baby to babyRoot(),
            ConflictRootType.Record to recordRoot(),
            ConflictRootType.CarePlan to carePlanRoot(),
            ConflictRootType.CustomItem to customItemRoot(),
            ConflictRootType.WakeObservation to wakeRoot(),
        )

        roots.forEachIndexed { index, (type, root) ->
            val detail = snapshot(type, root, "/updated_at")
            val author = ConflictResolverDraft.open(
                detail,
                ConflictResolverAudience("member-author", false),
                true,
                1_000,
                resolutionMutationId = mutationId(60 + index),
            )
            val outsider = ConflictResolverDraft.open(
                detail,
                ConflictResolverAudience("member-other", false),
                true,
                1_000,
                resolutionMutationId = mutationId(70 + index),
            )
            val owner = ConflictResolverDraft.open(
                detail,
                ConflictResolverAudience("member-other", true),
                true,
                1_000,
                resolutionMutationId = mutationId(80 + index),
            )

            assertThat(author.model.availability).isEqualTo(
                if (type == ConflictRootType.Baby) {
                    ConflictResolverAvailability.Forbidden
                } else {
                    ConflictResolverAvailability.Current
                },
            )
            assertThat(outsider.model.availability)
                .isEqualTo(ConflictResolverAvailability.Forbidden)
            assertThat(owner.model.availability).isEqualTo(ConflictResolverAvailability.Current)
        }
    }

    @Test
    fun openBeforeExpiryCannotChooseOrFreezeAtExpiryEquality() {
        var clockNow = 999L
        val opened = ConflictResolverDraft.open(
            snapshot(ConflictRootType.Record, recordRoot(), "/note").copy(expiresAt = 1_000),
            ConflictResolverAudience("member-author", false),
            fetchedOnline = true,
            nowMillis = clockNow,
            resolutionMutationId = mutationId(90),
            clock = { clockNow },
        )
        val complete = opened.choose("/note", choiceId("/note", true))

        clockNow = 1_000

        assertThat(complete.canSubmit).isFalse()
        assertThat(runCatching { complete.freeze() }.isFailure).isTrue()
        val selection = opened.select("/note", choiceId("/note", false))
        assertThat(selection).isInstanceOf(ConflictResolverChoiceResult.ReadOnly::class.java)
        assertThat((selection as ConflictResolverChoiceResult.ReadOnly).reason)
            .contains("已过期")
    }

    @Test
    fun frozenAttemptKeepsSameCommandForLostResponseReplayAfterExpiry() {
        var clockNow = 999L
        val frozen = ConflictResolverDraft.open(
            snapshot(ConflictRootType.Record, recordRoot(), "/note").copy(expiresAt = 1_000),
            ConflictResolverAudience("member-author", false),
            fetchedOnline = true,
            nowMillis = clockNow,
            resolutionMutationId = mutationId(91),
            clock = { clockNow },
        ).choose("/note", choiceId("/note", false)).freeze()
        val first = frozen.command()

        clockNow = 1_001

        assertThat(frozen.command()).isEqualTo(first)
    }

    @Test
    fun modelExposesStableBranchMediaAutoOutcomeDeleteRestoreAndProvenance() {
        val media = CausalMediaItem(
            mediaUuid = "00000000-0000-0000-0000-000000000040",
            role = "log",
            mime = "image/jpeg",
            sha256 = "a".repeat(64),
            byteSize = 12,
            width = 1,
            height = 1,
        )
        val source = source("v-stable", "member-author", "device-a")
        val detail = snapshot(
            ConflictRootType.Record,
            recordRoot(),
            "/_mutation.deleted",
            "/media/${media.mediaUuid}",
        ).copy(
            stable = version("v-stable", recordRoot(), deleted = true, media = listOf(media)),
            branches = listOf(
                version("v-branch", recordRoot(), deleted = false, media = listOf(media)),
            ),
            autoMerged = listOf(
                AutoMergedPath("/note", ConflictOutcome.Set(JsonNull), listOf(source)),
            ),
        )

        val model = ConflictResolverDraft.open(
            detail,
            ConflictResolverAudience("member-author", false),
            fetchedOnline = true,
            nowMillis = 1_000,
            resolutionMutationId = mutationId(30),
        ).model

        assertThat(model.versions.map { it.role })
            .containsExactly(ConflictVersionRole.Stable, ConflictVersionRole.Branch).inOrder()
        assertThat(model.versions.first().deleted).isTrue()
        assertThat(model.versions.first().media).containsExactly(media)
        assertThat(model.versions.last().provenance).contains("device-a")
        assertThat(model.autoMerged.single().label).isEqualTo("备注")
        assertThat(model.autoMerged.single().value).isEqualTo("清空")
        assertThat(model.paths.first { it.path == "/_mutation.deleted" }.options.map { it.value })
            .containsExactly("删除", "恢复并保留")
        assertThat(model.paths.first { it.path.startsWith("/media/") }.options.first().value)
            .contains("照片")
    }

    @Test
    fun processRestoreKeepsOnlyChoicesBoundToTheSameTokenAndMutation() {
        val initial = ConflictResolverDraft.open(
            snapshot(ConflictRootType.Record, recordRoot(), "/note"),
            ConflictResolverAudience("member-author", false),
            fetchedOnline = true,
            nowMillis = 1_000,
            resolutionMutationId = mutationId(40),
        ).choose("/note", choiceId("/note", false)).freeze()
        val saved = initial.savedState()

        val restored = ConflictResolverDraft.open(
            snapshot(ConflictRootType.Record, recordRoot(), "/note"),
            ConflictResolverAudience("member-author", false),
            fetchedOnline = true,
            nowMillis = 1_000,
            restored = saved,
            resolutionMutationId = mutationId(41),
        )
        assertThat(restored.selectedChoiceIds).isEqualTo(initial.selectedChoiceIds)
        assertThat(restored.resolutionMutationId).isEqualTo(mutationId(40))
        assertThat(restored.submitted).isTrue()

        val refreshed = ConflictResolverDraft.open(
            snapshot(ConflictRootType.Record, recordRoot(), "/note")
                .copy(snapshotToken = "b".repeat(43)),
            ConflictResolverAudience("member-author", false),
            fetchedOnline = true,
            nowMillis = 1_000,
            restored = saved,
            resolutionMutationId = mutationId(41),
        )
        assertThat(refreshed.selectedChoiceIds).isEmpty()
        assertThat(refreshed.resolutionMutationId).isEqualTo(mutationId(41))

        val corrupt = ConflictResolverDraft.open(
            snapshot(ConflictRootType.Record, recordRoot(), "/note"),
            ConflictResolverAudience("member-author", false),
            fetchedOnline = true,
            nowMillis = 1_000,
            restored = saved.copy(selectedChoiceIds = mapOf("/note" to "d".repeat(43))),
            resolutionMutationId = mutationId(42),
        )
        assertThat(corrupt.selectedChoiceIds).isEmpty()
        assertThat(corrupt.resolutionMutationId).isEqualTo(mutationId(42))
    }
}

private const val SNAPSHOT_TOKEN = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

private fun snapshot(
    type: ConflictRootType,
    root: ConflictRoot,
    vararg paths: String,
): ConflictSnapshot = ConflictSnapshot(
    conflictId = "00000000-0000-0000-0000-000000000010",
    entityType = type,
    clientUuid = "00000000-0000-0000-0000-000000000001",
    snapshotToken = SNAPSHOT_TOKEN,
    expiresAt = 2_000_000,
    stable = version("v-stable", root),
    branches = listOf(version("v-branch", root)),
    conflicting = paths.map { path ->
        ConflictingPath(
            path,
            listOf(
                ConflictCandidate(
                    choiceId(path, first = true),
                    pathOutcome(path, first = true),
                    listOf(source("v-stable", "member-author", "device-a")),
                ),
                ConflictCandidate(
                    choiceId(path, first = false),
                    pathOutcome(path, first = false),
                    listOf(source("v-branch", "member-other", "device-b")),
                ),
            ),
        )
    },
    autoMerged = emptyList(),
    pageIndex = 0,
    continuation = null,
    complete = true,
)

private fun pathOutcome(path: String, first: Boolean): ConflictOutcome = when {
    path == "/_mutation.deleted" -> ConflictOutcome.Set(JsonPrimitive(first))
    path.startsWith("/media/") -> if (first) {
        ConflictOutcome.Remove
    } else {
        ConflictOutcome.Set(
            buildJsonObject {
                put("media_uuid", path.substringAfterLast('/'))
                put("role", "log")
            },
        )
    }
    path.contains("timestamp") || path == "/scheduled_at" ->
        ConflictOutcome.Set(JsonPrimitive(if (first) 100 else 200))
    else -> ConflictOutcome.Set(JsonPrimitive(if (first) "stable" else "branch"))
}

private fun version(
    id: String,
    root: ConflictRoot,
    deleted: Boolean = false,
    media: List<CausalMediaItem> = emptyList(),
) = ConflictVersionSnapshot(
    versionId = id,
    baseVersion = null,
    root = root,
    media = media,
    deleted = deleted,
    mutationId = "00000000-0000-0000-0000-000000000003",
    actorId = "member-author",
    deviceId = "device-a",
    receivedAt = 100,
)

private fun source(version: String, actor: String, device: String) = ConflictSource(
    versionId = version,
    mutationId = "00000000-0000-0000-0000-000000000003",
    actorId = actor,
    deviceId = device,
    receivedAt = 100,
)

private fun babyRoot() = ConflictRoot.Baby(
    nickname = "宝宝",
    sex = null,
    birthday = null,
    avatarMediaUuid = null,
    createdByMembershipId = "member-author",
    updatedAt = 100,
    canonical = buildJsonObject {
        put("nickname", "宝宝")
        put("created_by_membership_id", "member-author")
        put("updated_at", 100)
    },
)

private fun recordRoot() = ConflictRoot.Record(
    babyClientUuid = "00000000-0000-0000-0000-000000000002",
    type = "formula",
    customItemClientUuid = null,
    timestamp = 100,
    endTimestamp = null,
    note = "stable",
    payload = buildJsonObject { put("amount_ml", 60) },
    schemaVersion = 2,
    effectiveWakeObservationClientUuid = null,
    createdByMembershipId = "member-author",
    updatedAt = 100,
    canonical = buildJsonObject {
        put("timestamp", 100)
        put("note", "stable")
        put("created_by_membership_id", "member-author")
        put("updated_at", 100)
    },
)

private fun carePlanRoot() = ConflictRoot.CarePlan(
    babyClientUuid = "00000000-0000-0000-0000-000000000002",
    type = "formula",
    scheduledAt = 100,
    scheduledZoneId = "Asia/Shanghai",
    note = null,
    payload = buildJsonObject {},
    schemaVersion = 2,
    status = "pending",
    fulfilledRecordClientUuid = null,
    fulfilledAt = null,
    sourceRecordClientUuid = null,
    customItemClientUuid = null,
    createdByMembershipId = "member-author",
    updatedAt = 100,
    canonical = buildJsonObject {
        put("scheduled_at", 100)
        put("created_by_membership_id", "member-author")
        put("updated_at", 100)
    },
)

private fun customItemRoot() = ConflictRoot.CustomItem(
    name = "按摩",
    iconSlot = 1,
    createdByMembershipId = "member-author",
    updatedAt = 100,
    canonical = buildJsonObject {
        put("name", "按摩")
        put("created_by_membership_id", "member-author")
        put("updated_at", 100)
    },
)

private fun wakeRoot() = ConflictRoot.WakeObservation(
    sleepRecordClientUuid = "00000000-0000-0000-0000-000000000002",
    wakeTimestamp = 100,
    note = null,
    withdrawn = false,
    observerMembershipId = "member-author",
    updatedAt = 100,
    canonical = buildJsonObject {
        put("wake_timestamp", 100)
        put("observer_membership_id", "member-author")
        put("updated_at", 100)
    },
)

private fun mutationId(index: Int): String =
    "00000000-0000-0000-0000-${index.toString().padStart(12, '0')}"

private fun choiceId(path: String, first: Boolean): String {
    val prefix = if (first) "A" else "B"
    val pathFrame = path.filter(Char::isLetterOrDigit).ifEmpty { "root" }
    return (prefix + pathFrame).padEnd(43, 'x').take(43)
}
