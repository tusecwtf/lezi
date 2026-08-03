package com.lezi.babylog.core.database

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TimelineWindowInvalidationDeviceTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val databaseName = "timeline-invalidation-${System.nanoTime()}.db"
    private val database = buildLeziDatabase(context, databaseName)

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun fulfillmentCandidateMutationInvalidatesTheTimelineWindow() = runBlocking {
        val initialEmission = CompletableDeferred<Unit>()
        val emissions = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(2_000L) {
                database.timelineWindowDao()
                    .observeInvalidations()
                    .onEach { initialEmission.complete(Unit) }
                    .take(2)
                    .toList()
            }
        }
        withTimeout(2_000L) { initialEmission.await() }

        database.fulfillmentCandidateDao().upsert(
            FulfillmentCandidateEntity(
                clientUuid = "candidate-1",
                carePlanClientUuid = "plan-1",
                recordClientUuid = "record-1",
                confirmedAt = 1L,
                updatedAt = 1L,
            ),
        )

        assertEquals(listOf(0L, 1L), emissions.await())
    }
}
