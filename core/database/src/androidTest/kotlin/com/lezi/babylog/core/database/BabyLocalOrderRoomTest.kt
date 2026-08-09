package com.lezi.babylog.core.database

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BabyLocalOrderRoomTest {
    private lateinit var database: LeziDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            LeziDatabase::class.java,
        ).build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun completeLocalOrderPublishesNoIntermediateGapOrDuplicate() = runBlocking {
        val babies = database.babyDao()
        val ids = listOf("first", "second", "third").mapIndexed { index, uuid ->
            babies.upsert(
                BabyEntity(
                    familyId = 1,
                    nickname = uuid,
                    birthdayEpochDay = index.toLong(),
                    themeColorArgb = 0,
                    sortOrder = index,
                    clientUuid = uuid,
                    updatedAt = 1,
                ),
            )
        }
        val initialObserved = CompletableDeferred<Unit>()
        val observations = async {
            withTimeout(5_000) {
                babies.observeAll()
                    .map { rows -> rows.map { row -> row.id to row.sortOrder } }
                    .onEach { initialObserved.complete(Unit) }
                    .take(2)
                    .toList()
            }
        }
        initialObserved.await()

        babies.writeCompleteLocalOrder(ids.reversed())

        assertThat(observations.await()).containsExactly(
            listOf(ids[0] to 0, ids[1] to 1, ids[2] to 2),
            listOf(ids[2] to 0, ids[1] to 1, ids[0] to 2),
        ).inOrder()

        // Sensitivity control: the same observer must expose an invalid frame when a
        // sort update happens outside the transaction seam.
        babies.writeCompleteLocalOrder(ids)
        val controlStarted = CompletableDeferred<Unit>()
        val invalidFrame = async {
            withTimeout(5_000) {
                babies.observeAll()
                    .map { rows -> rows.map { row -> row.sortOrder } }
                    .onEach { controlStarted.complete(Unit) }
                    .first { sortOrders -> sortOrders != listOf(0, 1, 2) }
            }
        }
        controlStarted.await()
        babies.updateLocalSortOrder(ids.last(), 0)
        yield()

        assertThat(invalidFrame.await()).containsExactly(0, 0, 1).inOrder()
    }
}
