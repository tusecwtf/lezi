package com.lezi.babylog.core.database.causal

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.lezi.babylog.core.database.BabyDao
import com.lezi.babylog.core.database.BabyEntity
import com.lezi.babylog.core.database.DatabaseTransactionRunner
import com.lezi.babylog.core.database.MediaAssetDao
import com.lezi.babylog.core.database.MediaAssetEntity
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PrivateSpoolPathPolicyTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun fixedFilesRootResolvesRelativeAbsoluteUriAndSymlinkAliasesIncludingAbsentFiles() {
        val root = temporary.newFolder("files")
        val spool = File(root, "causal-media-spool").apply { mkdir() }
        val policy = PrivateSpoolPathPolicy(root)
        val alias = File(root, "spool-alias")
        Files.createSymbolicLink(alias.toPath(), spool.toPath())
        val candidate = File(spool, "group/absent.spool")
        listOf(
            "causal-media-spool/group/absent.spool",
            "./causal-media-spool/group/absent.spool",
            "product/../causal-media-spool/group/absent.spool",
            candidate.path,
            candidate.toURI().toString(),
            "file:causal-media-spool/group/absent.spool",
            "file:${root.path}/causal-media%2Dspool/group/absent.spool",
            "spool-alias/group/absent.spool",
            File(alias, "group/absent.spool").toURI().toString(),
        ).forEach { path ->
            assertWithMessage(path).that(policy.isPrivatePath(path)).isTrue()
            assertWithMessage(path).that(policy.resolvedPath(path)).isEqualTo(candidate)
        }
        listOf(null, "", "photos/a.jpg", "causal-media-spool-other/a", "content://photos/a").forEach {
            assertThat(policy.isPrivatePath(it)).isFalse()
        }
        assertThat(policy.ownsSpoolRoot(spool)).isTrue()
        assertThat(policy.ownsSpoolRoot(alias)).isTrue()
        assertThat(policy.ownsSpoolRoot(root)).isFalse()
        assertThat(policy.resolvedPath("file:%")).isNull()
    }

    @Test
    fun danglingAliasesRemainPrivateAfterRetirementAndPolicyReconstruction() {
        val root = temporary.newFolder("files")
        val spool = File(root, "causal-media-spool")
        val alias = File(root, "alias")
        Files.createSymbolicLink(alias.toPath(), spool.toPath())
        Files.createSymbolicLink(File(root, "relative-alias").toPath(), File("causal-media-spool").toPath())
        val policy = PrivateSpoolPathPolicy(root)
        listOf("alias/group/absent", "relative-alias/group/absent").forEach { path ->
            assertThat(policy.isPrivatePath(path)).isTrue()
            assertThat(policy.resolvedPath(path)).isEqualTo(File(spool, "group/absent"))
        }
        assertThat(policy.ownsSpoolRoot(spool)).isTrue()
        assertThat(policy.isPrivatePath("alias/group/../absent")).isTrue()
        assertThat(policy.resolvedPath("alias/../photos/a")).isEqualTo(File(root, "photos/a"))
    }

    @Test
    fun outwardSymlinkStillReservesLexicalSpoolPathAndRetargetedRootIsNotOwned() {
        val root = temporary.newFolder("files")
        val spool = File(root, "causal-media-spool").apply { mkdir() }
        val outside = temporary.newFolder("outside")
        val policy = PrivateSpoolPathPolicy(root)
        val child = File(spool, "out")
        Files.createSymbolicLink(child.toPath(), outside.toPath())
        assertThat(policy.isPrivatePath("causal-media-spool/out/photo.jpg")).isTrue()
        Files.delete(child.toPath())
        Files.delete(spool.toPath())
        Files.createSymbolicLink(spool.toPath(), outside.toPath())
        assertThat(policy.ownsSpoolRoot(spool)).isFalse()
        assertThat(policy.ownsSpoolRoot(outside)).isFalse()
        assertThat(policy.isPrivatePath("causal-media-spool/photo.jpg")).isTrue()
    }

    @Test
    fun everyPublisherRejectsNewPrivateAliasInsideTransaction() = runBlocking<Unit> {
        val fixture = Fixture(temporary.newFolder("files"))
        fixture.aliases().forEach { path ->
            val attempts: List<suspend () -> Unit> = listOf(
                { fixture.media.upsert(media(path)) },
                { fixture.media.update(media(path)) },
                { fixture.babies.upsert(baby(path)) },
                { fixture.babies.update(baby(path)) },
                { fixture.babies.updateAvatarReplica("baby", "avatar", path) },
                { fixture.babies.updateAvatarPathForReplica(1, null, path) },
                { fixture.references.upsert(reference(path)) },
                { fixture.references.replaceHolders("media", listOf(reference(path))) },
            )
            attempts.forEach { attempt ->
                expectRejected { attempt() }
                assertThat(fixture.events.first()).isEqualTo("begin")
                assertThat(fixture.events.last()).isEqualTo("end")
                assertThat(fixture.events).doesNotContain("write")
                assertThat(fixture.events).contains("read")
                fixture.events.clear()
            }
        }
    }

    @Test
    fun allPublishersRetainExactLegacyIdentityAndRawPathWithoutHidingReads() = runBlocking<Unit> {
        val f = Fixture(temporary.newFolder("files"))
        val path = "./causal-media-spool/group/legacy"
        f.mediaRows["media"] = media(path)
        f.babyRows["baby"] = baby(path)
        f.referenceRows.add(reference(path))
        f.media.upsert(media(path).copy(remoteUri = "remote"))
        f.media.update(media(path).copy(deletedAt = 2))
        f.babies.upsert(baby(path).copy(nickname = "edited"))
        f.babies.update(baby(path).copy(deletedAt = 2))
        f.babies.updateAvatarReplica("baby", "avatar", path)
        assertThat(f.babies.updateAvatarPathForReplica(1, "avatar", path)).isEqualTo(1)
        f.references.upsert(reference(path).copy(remoteUri = "remote"))
        f.references.replaceHolders("media", listOf(reference(path).copy(createdAt = 2)))
        assertThat(f.media.getByClientUuid("media")?.localUri).isEqualTo(path)
        assertThat(f.babies.getByClientUuid("baby")?.avatarPath).isEqualTo(path)
        assertThat(f.references.listForMedia("media").single().localUri).isEqualTo(path)
        listOf(f.media, f.babies, f.references).forEach {
            assertThat((it as PrivateSpoolPublicationGuarded).privateSpoolPathPolicy)
                .isSameInstanceAs(f.policy)
        }
    }

    @Test
    fun existingAliasCannotTransferIdentityOrChangeRawSpelling() = runBlocking<Unit> {
        val f = Fixture(temporary.newFolder("files"))
        val path = "causal-media-spool/group/legacy"
        f.mediaRows["media"] = media(path)
        f.babyRows["baby"] = baby(path)
        f.referenceRows.add(reference(path))
        listOf<suspend () -> Unit>(
            { f.media.upsert(media(path).copy(id = 0)) },
            { f.media.upsert(media(path).copy(clientUuid = "another")) },
            { f.media.update(media("./$path")) },
            { f.babies.upsert(baby(path).copy(id = 0)) },
            { f.babies.update(baby(path).copy(clientUuid = "another")) },
            { f.babies.updateAvatarReplica("baby", null, "./$path") },
            { f.babies.updateAvatarPathForReplica(1, null, "./$path") },
            { f.references.upsert(reference(path).copy(holderId = "another")) },
            { f.references.upsert(reference(path).copy(holderKind = "local_mutation")) },
            { f.references.upsert(reference(path).copy(mediaUuid = "another")) },
            { f.references.upsert(reference("./$path")) },
        ).forEach { attempt -> expectRejected { attempt() } }
    }

    @Test
    fun replaceHoldersValidatesWholeBatchBeforeDeletingAndCannotLaunderOtherMedia() = runBlocking<Unit> {
        val f = Fixture(temporary.newFolder("files"))
        val path = "causal-media-spool/group/legacy"
        val legacy = reference(path)
        f.referenceRows.add(legacy)
        expectRejected {
            f.references.replaceHolders("media", listOf(legacy, legacy.copy(holderId = "new")))
        }
        assertThat(f.referenceRows).containsExactly(legacy)
        expectRejected {
            f.references.replaceHolders("media", listOf(legacy.copy(mediaUuid = "other")))
        }
        assertThat(f.referenceRows).containsExactly(legacy)
    }

    @Test
    fun removingLegacyBeforeTransactionCannotResurrectSnapshot() = runBlocking<Unit> {
        val f = Fixture(temporary.newFolder("files"))
        val path = "causal-media-spool/group/legacy"
        f.mediaRows["media"] = media(path)
        f.beforeTransaction = { f.mediaRows.clear() }
        expectRejected { f.media.upsert(media(path)) }
        assertThat(f.mediaRows).isEmpty()
        assertThat(f.events).containsExactly("begin", "read", "end").inOrder()
    }

    @Test
    fun normalProductPathsRemainPublishableAndLegacyPathsCanBeCleared() = runBlocking<Unit> {
        val f = Fixture(temporary.newFolder("files"))
        f.mediaRows["media"] = media("causal-media-spool/group/legacy")
        f.babyRows["baby"] = baby("causal-media-spool/group/legacy")
        f.referenceRows.add(reference("causal-media-spool/group/legacy"))
        f.media.upsert(media("photos/saved.jpg"))
        f.media.update(media(""))
        f.babies.upsert(baby("avatars/saved.jpg"))
        f.babies.update(baby(null))
        f.babies.updateAvatarReplica("baby", "avatar", "avatars/new.jpg")
        f.babies.updateAvatarPathForReplica(1, "avatar", null)
        f.references.upsert(reference("photos/saved.jpg"))
        f.references.replaceHolders("media", listOf(reference(null)))
        assertThat(f.mediaRows["media"]?.localUri).isEmpty()
        assertThat(f.babyRows["baby"]?.avatarPath).isNull()
        assertThat(f.referenceRows.single().localUri).isNull()
    }

    @Test
    fun unchangedMalformedLegacyPathsRemainEditableWithoutInventingNewAliases() = runBlocking<Unit> {
        val f = Fixture(temporary.newFolder("malformed"))
        val path = "file:%"
        f.mediaRows["media"] = media(path)
        f.babyRows["baby"] = baby(path)
        f.referenceRows += reference(path)
        f.media.update(media(path).copy(remoteUri = "changed"))
        f.babies.update(baby(path).copy(nickname = "changed"))
        f.references.upsert(reference(path).copy(createdAt = 2))
        assertThat(f.mediaRows["media"]?.localUri).isEqualTo(path)
        assertThat(f.babyRows["baby"]?.avatarPath).isEqualTo(path)
    }

    private suspend fun expectRejected(block: suspend () -> Unit) {
        var rejected = false
        try { block() } catch (_: IllegalArgumentException) { rejected = true }
        assertThat(rejected).isTrue()
    }

    private class Fixture(val root: File) {
        val policy = PrivateSpoolPathPolicy(root)
        val events = mutableListOf<String>()
        val mediaRows = mutableMapOf<String, MediaAssetEntity>()
        val babyRows = mutableMapOf<String, BabyEntity>()
        val referenceRows = mutableListOf<MediaReferenceEntity>()
        var beforeTransaction: () -> Unit = {}
        private var inTransaction = false
        private val transactions = object : DatabaseTransactionRunner {
            override suspend fun <T> run(block: suspend () -> T): T {
                check(!inTransaction)
                events.add("begin")
                inTransaction = true
                return try {
                    beforeTransaction()
                    block()
                } finally {
                    inTransaction = false
                    events.add("end")
                }
            }
        }

        val media = policy.guard(proxy<MediaAssetDao> { name, args ->
            when (name) {
                "getByClientUuid" -> read { mediaRows[args[0] as String] }
                "upsert", "update" -> write {
                    val row = args[0] as MediaAssetEntity
                    mediaRows[row.clientUuid] = row
                    if (name == "upsert") row.id else Unit
                }
                else -> error("Unexpected media method $name")
            }
        }, transactions)
        val babies = policy.guard(proxy<BabyDao> { name, args ->
            when (name) {
                "getByClientUuid" -> read { babyRows[args[0] as String] }
                "getIncludingDeleted" -> read { babyRows.values.singleOrNull { it.id == args[0] } }
                "upsert", "update" -> write {
                    val row = args[0] as BabyEntity
                    babyRows[row.clientUuid] = row
                    if (name == "upsert") row.id else Unit
                }
                "updateAvatarReplica" -> write {
                    val uuid = args[0] as String
                    babyRows[uuid]?.let {
                        babyRows[uuid] = it.copy(avatarMediaUuid = args[1] as String?, avatarPath = args[2] as String?)
                    }
                    Unit
                }
                "updateAvatarPathForReplica" -> write {
                    val current = babyRows.values.singleOrNull { it.id == args[0] && it.avatarMediaUuid == args[1] }
                    if (current != null) {
                        babyRows[current.clientUuid] = current.copy(avatarPath = args[2] as String?)
                        1
                    } else 0
                }
                else -> error("Unexpected baby method $name")
            }
        }, transactions)
        val references = policy.guard(proxy<MediaReferenceDao> { name, args ->
            when (name) {
                "listForMedia" -> read { referenceRows.filter { it.mediaUuid == args[0] } }
                "upsert" -> write { saveReference(args[0] as MediaReferenceEntity) }
                "replaceHolders" -> write {
                    referenceRows.removeAll { it.mediaUuid == args[0] }
                    @Suppress("UNCHECKED_CAST")
                    (args[1] as List<MediaReferenceEntity>).forEach(::saveReference)
                }
                else -> error("Unexpected reference method $name")
            }
        }, transactions)

        fun aliases(): List<String> {
            val spool = File(root, "causal-media-spool").apply { mkdir() }
            Files.createSymbolicLink(File(root, "alias").toPath(), spool.toPath())
            return listOf(
                "causal-media-spool/group/new",
                "photos/../causal-media-spool/group/new",
                File(spool, "group/new").path,
                File(spool, "group/new").toURI().toString(),
                "file:causal-media-spool/group/new",
                "alias/group/new",
            )
        }

        private fun saveReference(row: MediaReferenceEntity) {
            referenceRows.removeAll {
                it.mediaUuid == row.mediaUuid && it.holderKind == row.holderKind && it.holderId == row.holderId
            }
            referenceRows.add(row)
        }

        private fun <T> read(block: () -> T): T {
            if (inTransaction) events.add("read")
            return block()
        }

        private fun <T> write(block: () -> T): T {
            check(inTransaction) { "Path publication escaped its transaction" }
            events.add("write")
            return block()
        }
    }

    private companion object {
        fun media(path: String) = MediaAssetEntity(
            id = 1, clientUuid = "media", recordId = 1, localUri = path, createdAt = 1,
        )

        fun baby(path: String?) = BabyEntity(
            id = 1, clientUuid = "baby", familyId = 1, nickname = "baby",
            birthdayEpochDay = 1, themeColorArgb = 0, updatedAt = 1, avatarPath = path,
        )

        fun reference(path: String?) = MediaReferenceEntity(
            mediaUuid = "media", holderKind = "stable_root", holderId = "holder",
            localUri = path, createdAt = 1,
        )

        inline fun <reified T> proxy(crossinline call: (String, Array<out Any?>) -> Any?): T =
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
                call(method.name, args.orEmpty())
            } as T
    }
}
