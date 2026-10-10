package com.lezi.babylog.core.database

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.causal.MediaReferenceDao
import com.lezi.babylog.core.database.causal.MediaReferenceEntity
import com.lezi.babylog.core.database.causal.PrivateSpoolPathPolicy
import com.lezi.babylog.core.database.causal.PrivateSpoolPublicationGuarded
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the actual application providers, not a separate test-only decorating factory. */
@RunWith(AndroidJUnit4::class)
class PrivateSpoolPublicationRoomTest {
    private lateinit var database: LeziDatabase
    private lateinit var policy: PrivateSpoolPathPolicy
    private lateinit var babies: BabyDao
    private lateinit var media: MediaAssetDao
    private lateinit var references: MediaReferenceDao

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, LeziDatabase::class.java).build()
        policy = DatabaseModule.privateSpoolPathPolicy(context)
        val transactions = DatabaseModule.transactionRunner(database)
        babies = DatabaseModule.babyDao(database, policy, transactions)
        media = DatabaseModule.mediaAssetDao(database, policy, transactions)
        references = DatabaseModule.mediaReferenceDao(database, policy, transactions)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun applicationProvidersShareFixedFilesDirGuardAndRejectEveryPublicationRoute() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertThat(policy.ownsSpoolRoot(File(context.filesDir, "causal-media-spool"))).isTrue()
        listOf(babies, media, references).forEach {
            assertThat((it as PrivateSpoolPublicationGuarded).privateSpoolPathPolicy)
                .isSameInstanceAs(policy)
        }
        val ordinaryBaby = baby("avatars/product.jpg")
        val ordinaryMedia = asset("photos/product.jpg")
        babies.upsert(ordinaryBaby)
        media.upsert(ordinaryMedia)
        val privatePath = "causal-media-spool/group/donor"
        val absoluteUri = File(context.filesDir, privatePath).toURI().toString()
        listOf<suspend () -> Unit>(
            { babies.upsert(ordinaryBaby.copy(avatarPath = privatePath)) },
            { babies.update(ordinaryBaby.copy(avatarPath = absoluteUri)) },
            { babies.updateAvatarReplica("baby", "avatar", privatePath) },
            { babies.updateAvatarPathForReplica(1, null, absoluteUri) },
            { media.upsert(ordinaryMedia.copy(localUri = privatePath)) },
            { media.update(ordinaryMedia.copy(localUri = absoluteUri)) },
            { references.upsert(reference(privatePath)) },
            { references.replaceHolders("media", listOf(reference(absoluteUri))) },
        ).forEach { attempt -> expectRejected { attempt() } }
        assertThat(babies.getByClientUuid("baby")).isEqualTo(ordinaryBaby)
        assertThat(media.getByClientUuid("media")).isEqualTo(ordinaryMedia)
        assertThat(references.listForMedia("media")).isEmpty()
    }

    @Test
    fun legacyRowsRemainReadableAndAllExactPathEditsRemainUsable() = runBlocking<Unit> {
        val path = "./causal-media-spool/group/legacy"
        // Direct Room seeding represents an older persisted database, before the runtime guard.
        database.babyDao().upsert(baby(path))
        database.mediaAssetDao().upsert(asset(path))
        database.mediaReferenceDao().upsert(reference(path))
        assertThat(babies.getByClientUuid("baby")?.avatarPath).isEqualTo(path)
        assertThat(media.getByClientUuid("media")?.localUri).isEqualTo(path)
        assertThat(references.listForMedia("media").single().localUri).isEqualTo(path)

        babies.upsert(baby(path).copy(nickname = "edited"))
        babies.update(baby(path).copy(deletedAt = 2))
        babies.updateAvatarReplica("baby", "avatar", path)
        assertThat(babies.updateAvatarPathForReplica(1, "avatar", path)).isEqualTo(1)
        media.upsert(asset(path).copy(remoteUri = "receipt"))
        media.update(asset(path).copy(deletedAt = 2))
        references.upsert(reference(path).copy(remoteUri = "receipt"))
        references.replaceHolders("media", listOf(reference(path).copy(createdAt = 2)))

        assertThat(babies.getIncludingDeleted(1)?.avatarPath).isEqualTo(path)
        assertThat(media.getByClientUuid("media")?.localUri).isEqualTo(path)
        assertThat(references.listForMedia("media").single().localUri).isEqualTo(path)
        expectRejected { media.upsert(asset(path).copy(id = 0)) }
        expectRejected { babies.upsert(baby(path).copy(id = 0)) }
        expectRejected { references.upsert(reference(path).copy(holderId = "new-holder")) }
    }

    @Test
    fun holderReplacementRejectsBeforeDeletingAnyLegacyHolder() = runBlocking<Unit> {
        val legacy = reference("causal-media-spool/group/legacy")
        database.mediaReferenceDao().upsert(legacy)
        expectRejected {
            references.replaceHolders("media", listOf(legacy, legacy.copy(holderId = "new-holder")))
        }
        assertThat(references.listForMedia("media")).containsExactly(legacy)
        expectRejected {
            references.replaceHolders("media", listOf(legacy.copy(mediaUuid = "other-media")))
        }
        assertThat(references.listForMedia("media")).containsExactly(legacy)
        assertThat(references.listForMedia("other-media")).isEmpty()
    }

    @Test
    fun delegatedTransactionalBabyMethodsPreserveLegacyPath() = runBlocking<Unit> {
        val path = "causal-media-spool/group/legacy"
        database.babyDao().upsert(baby(path))
        assertThat(babies.acknowledgeSyntheticRootPublication("baby", 1, 2)).isTrue()
        assertThat(babies.getIncludingDeleted(1)?.avatarPath).isEqualTo(path)
        babies.markAllPendingSync()
        assertThat(babies.freezeDirtyEpoch("baby", 2, "mutation")?.avatarPath).isEqualTo(path)
        assertThat(babies.getIncludingDeleted(1)?.avatarPath).isEqualTo(path)
    }

    private suspend fun expectRejected(block: suspend () -> Unit) {
        var rejected = false
        try { block() } catch (_: IllegalArgumentException) { rejected = true }
        assertThat(rejected).isTrue()
    }

    private fun baby(path: String) = BabyEntity(
        id = 1, clientUuid = "baby", familyId = 1, nickname = "baby",
        birthdayEpochDay = 1, themeColorArgb = 0, updatedAt = 1, avatarPath = path,
    )

    private fun asset(path: String) = MediaAssetEntity(
        id = 1, clientUuid = "media", recordId = 1, localUri = path, createdAt = 1,
    )

    private fun reference(path: String) = MediaReferenceEntity(
        mediaUuid = "media", holderKind = "stable_root", holderId = "holder",
        localUri = path, createdAt = 1,
    )
}
