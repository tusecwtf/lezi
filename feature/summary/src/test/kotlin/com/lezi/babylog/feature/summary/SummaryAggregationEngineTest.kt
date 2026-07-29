package com.lezi.babylog.feature.summary

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.model.Record
import com.lezi.babylog.core.model.RecordType
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test

class SummaryAggregationEngineTest {
    private val anchor = LocalDate.of(2026, 7, 23)
    private val zone = ZoneOffset.UTC

    @Test
    fun largeSummaryCalculationRunsOnProvidedComputationDispatcher() {
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "summary-computation")
        }.asCoroutineDispatcher().use { dispatcher ->
            val records = ThreadRecordingList(
                List(2_000) { index ->
                    record(index.toLong(), anchor, amountMl = 1)
                },
            )
            val engine = SummaryAggregationEngine(dispatcher)

            runBlocking {
                engine.calculate(
                    SummaryAggregationRequest(
                        records = records,
                        range = SummaryRange.Month,
                        anchorDate = anchor,
                        showAvgSleep = false,
                        babyName = "年年",
                        zone = zone,
                    ),
                )
            }

            assertThat(
                records.readerThreads.all { it.startsWith("summary-computation") },
            ).isTrue()
        }
    }

    @Test
    fun largeFixtureMatchesReferenceOutputAndFixedTotals() = runBlocking {
        val records = List(3_000) { index ->
            record(
                id = index.toLong(),
                date = anchor.minusDays((index % 30).toLong()),
                amountMl = index % 5 + 1,
            )
        }
        val request = SummaryAggregationRequest(
            records = records,
            range = SummaryRange.Month,
            anchorDate = anchor,
            showAvgSleep = false,
            babyName = "年年",
            zone = zone,
        )

        val actual = SummaryAggregationEngine().calculate(request)
        val reference = buildSummaryUi(
            records = records,
            range = SummaryRange.Month,
            anchorDate = anchor,
            showAvgSleep = false,
            babyName = "年年",
            zone = zone,
        )

        assertThat(actual).isEqualTo(reference)
        assertThat(actual.totals.feedMl).isEqualTo(9_000)
        assertThat(actual.totals.feedCount).isEqualTo(3_000)
        assertThat(actual.totals.dayValuesFeed).hasSize(30)
    }

    @Test
    fun cancellingLargeCalculationStopsReadingAndPublishesNoResult() {
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "summary-cancellation")
        }.asCoroutineDispatcher().use { dispatcher ->
            val firstRead = CountDownLatch(1)
            val allowRead = CountDownLatch(1)
            val records = BlockingReadList(
                records = List(100_000) { index ->
                    record(index.toLong(), anchor, amountMl = 1)
                },
                firstRead = firstRead,
                allowRead = allowRead,
            )
            val engine = SummaryAggregationEngine(dispatcher)

            var resultPublished = false
            runBlocking {
                val calculation = launch(start = CoroutineStart.UNDISPATCHED) {
                    engine.calculate(
                        SummaryAggregationRequest(
                            records = records,
                            range = SummaryRange.Month,
                            anchorDate = anchor,
                            showAvgSleep = false,
                            babyName = "年年",
                            zone = zone,
                        ),
                    )
                    resultPublished = true
                }
                check(firstRead.await(5, TimeUnit.SECONDS)) { "calculation did not start" }
                calculation.cancel()
                allowRead.countDown()
                joinAll(calculation)
            }

            assertThat(resultPublished).isFalse()
            assertThat(records.readCount.get()).isLessThan(records.size)
        }
    }

    @Test
    fun outputKeepsEmptySleepClippingCustomAndUnknownPayloadRules() = runBlocking {
        val anchorStart = anchor.atStartOfDay(zone).toInstant().toEpochMilli()
        val now = anchorStart + 24 * 60 * 60_000L
        val records = listOf(
            record(
                id = 1,
                type = RecordType.SLEEP,
                timestamp = anchorStart - 30 * 60_000L,
                endTimestamp = anchorStart + 30 * 60_000L,
                payloadJson = """{"is_nap":false,"anomaly_flag":false}""",
            ),
            record(
                id = 2,
                type = RecordType.SLEEP,
                timestamp = anchorStart + 22 * 60 * 60_000L,
                endTimestamp = null,
                payloadJson = """{"is_nap":false,"anomaly_flag":false}""",
            ),
            record(
                id = 3,
                type = RecordType.FORMULA,
                timestamp = anchorStart + 10 * 60 * 60_000L,
                payloadJson = """{"amount_ml":90}""",
            ),
            record(
                id = 4,
                type = RecordType.CUSTOM,
                timestamp = anchorStart + 11 * 60 * 60_000L,
                payloadJson = """{"custom_item_id":7,"text_value":"观察"}""",
            ),
            record(
                id = 5,
                type = RecordType.FORMULA,
                timestamp = anchorStart + 12 * 60 * 60_000L,
                payloadJson = """{"amount_ml":999}""",
                schemaVersion = 99,
            ),
        )
        val engine = SummaryAggregationEngine(nowMillis = { now })

        val ui = engine.calculate(request(records))
        val empty = engine.calculate(request(emptyList()))

        assertThat(ui.totals.feedMl).isEqualTo(90)
        assertThat(ui.totals.feedCount).isEqualTo(1)
        assertThat(ui.totals.sleepMin).isEqualTo(150)
        assertThat(ui.totals.sleepSegments).isEqualTo(1)
        assertThat(ui.empty).isFalse()
        assertThat(empty.totals.feedMl).isEqualTo(0)
        assertThat(empty.totals.feedCount).isEqualTo(0)
        assertThat(empty.totals.sleepMin).isEqualTo(0)
        assertThat(empty.totals.dayValuesFeed).containsExactly(0f)
        assertThat(empty.totals.dayValuesSleep).containsExactly(0f)
        assertThat(empty.empty).isTrue()
    }

    @Test
    fun newerRequestCancelsOlderCalculationAndOnlyPublishesNewestResult() {
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "summary-latest")
        }.asCoroutineDispatcher().use { dispatcher ->
            val firstRead = CountDownLatch(1)
            val allowRead = CountDownLatch(1)
            val oldRecords = BlockingReadList(
                records = List(100_000) { index ->
                    record(index.toLong(), anchor, amountMl = 1)
                },
                firstRead = firstRead,
                allowRead = allowRead,
            )
            val requests = MutableSharedFlow<SummaryAggregationRequest>()
            val published = mutableListOf<SummaryUi>()
            val engine = SummaryAggregationEngine(dispatcher)

            runBlocking {
                val collection = launch(Dispatchers.Default) {
                    requests.calculateLatest(engine).take(1).collect(published::add)
                }
                requests.subscriptionCount.first { it > 0 }
                requests.emit(request(oldRecords, babyName = "旧结果"))
                check(firstRead.await(5, TimeUnit.SECONDS)) { "old calculation did not start" }
                requests.emit(
                    request(
                        records = listOf(record(200_000, anchor, amountMl = 42)),
                        babyName = "新结果",
                    ),
                )
                allowRead.countDown()
                collection.join()
            }

            assertThat(published).hasSize(1)
            assertThat(published.single().babyName).isEqualTo("新结果")
            assertThat(published.single().totals.feedMl).isEqualTo(42)
            assertThat(oldRecords.readCount.get()).isLessThan(oldRecords.size)
        }
    }

    @Test
    fun initialUiIsExplicitlyCalculatingAndCompletedResultIsNot() = runBlocking {
        assertThat(SummaryUi().calculating).isTrue()

        val completed = SummaryAggregationEngine().calculate(request(emptyList()))

        assertThat(completed.calculating).isFalse()
    }

    private fun request(
        records: List<Record>,
        babyName: String = "年年",
    ) = SummaryAggregationRequest(
        records = records,
        range = SummaryRange.Day,
        anchorDate = anchor,
        showAvgSleep = false,
        babyName = babyName,
        zone = zone,
    )

    private fun record(id: Long, date: LocalDate, amountMl: Int): Record {
        val timestamp = date.atTime(10, 0).toInstant(zone).toEpochMilli()
        return record(
            id = id,
            type = RecordType.FORMULA,
            timestamp = timestamp,
            payloadJson = """{"amount_ml":$amountMl}""",
        )
    }

    private fun record(
        id: Long,
        type: RecordType,
        timestamp: Long,
        endTimestamp: Long? = null,
        payloadJson: String = "{}",
        schemaVersion: Int = 2,
    ): Record {
        return Record(
            id = id,
            clientUuid = "record-$id",
            babyId = 1,
            type = type,
            timestamp = timestamp,
            endTimestamp = endTimestamp,
            payloadJson = payloadJson,
            schemaVersion = schemaVersion,
            updatedAt = timestamp,
        )
    }
}

private class BlockingReadList(
    private val records: List<Record>,
    private val firstRead: CountDownLatch,
    private val allowRead: CountDownLatch,
) : AbstractList<Record>() {
    val readCount = AtomicInteger(0)

    override val size: Int get() = records.size

    override fun get(index: Int): Record {
        if (readCount.getAndIncrement() == 0) {
            firstRead.countDown()
            check(allowRead.await(5, TimeUnit.SECONDS)) { "read was not released" }
        }
        return records[index]
    }
}

private class ThreadRecordingList(
    private val records: List<Record>,
) : AbstractList<Record>() {
    val readerThreads = linkedSetOf<String>()

    override val size: Int get() = records.size

    override fun get(index: Int): Record {
        readerThreads += Thread.currentThread().name
        return records[index]
    }
}
