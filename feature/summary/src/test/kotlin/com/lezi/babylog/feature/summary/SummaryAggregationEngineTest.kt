package com.lezi.babylog.feature.summary

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.model.Record
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.domain.carelog.SuspectedDuplicateProjection
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
    fun largeFixtureKeepsFixedTotals() = runBlocking {
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

    @Test
    fun summaryAndLogDayUseTheSameDuplicateProjectionBoundsAcrossMidnight() = runBlocking {
        val start = anchor.atStartOfDay(zone).toInstant().toEpochMilli()
        val records = listOf(
            record(
                id = 1,
                type = RecordType.FORMULA,
                timestamp = start - 10 * 60_000L,
                payloadJson = """{"amount_ml":100}""",
            ).copy(createdByMembershipId = "m1"),
            record(
                id = 2,
                type = RecordType.FORMULA,
                timestamp = start + 10 * 60_000L,
                payloadJson = """{"amount_ml":120}""",
            ).copy(createdByMembershipId = "m2"),
        )
        val now = start + 12 * 60 * 60_000L
        val directLogProjection = SuspectedDuplicateProjection.project(
            records = records,
            startDate = anchor,
            dayCount = 1,
            zone = zone,
            now = now,
        )
        val summary = SummaryAggregationEngine(nowMillis = { now }).calculate(
            request(records),
        )

        assertThat(summary.totals.feedMlMin)
            .isEqualTo(directLogProjection.bounds.feedMl.min)
        assertThat(summary.totals.feedMlMax)
            .isEqualTo(directLogProjection.bounds.feedMl.max)
        assertThat(summary.totals.dayValuesFeed).containsExactly(0f)
        assertThat(summary.totals.feedMlLabel).isEqualTo("0–120ml")
        assertThat(summary.totals.hasDuplicateUncertainty).isTrue()
    }

    @Test
    fun unresolvedDuplicateBarsUseTheDayLowerBoundNotTheMemberSum() {
        runBlocking {
            val at = millis(anchor, 9)
            val records = listOf(
                record(
                    id = 1,
                    type = RecordType.FORMULA,
                    timestamp = at,
                    payloadJson = """{"amount_ml":100}""",
                ).copy(createdByMembershipId = "m1"),
                record(
                    id = 2,
                    type = RecordType.FORMULA,
                    timestamp = at + 60_000L,
                    payloadJson = """{"amount_ml":120}""",
                ).copy(createdByMembershipId = "m2"),
            )

            val summary = SummaryAggregationEngine(nowMillis = { at + 3_600_000L }).calculate(
                request(records),
            )

            assertThat(summary.totals.dayValuesFeed).containsExactly(100f)
            assertThat(summary.totals.feedMlLabel).isEqualTo("100–220ml")
            assertThat(summary.totals.feedMlMin).isEqualTo(100)
            assertThat(summary.totals.feedMlMax).isEqualTo(220)
        }
    }

    @Test
    fun `food panel is absent when the range has no baby_food records`() = runBlocking {
        val records = listOf(
            record(
                id = 1,
                type = RecordType.FORMULA,
                timestamp = millis(anchor, 8),
                payloadJson = """{"amount_ml":120}""",
            ),
            foodRecord(2, millis(anchor, 9), "米粉", "1碗")
                .copy(deletedAt = millis(anchor, 10)),
            foodRecord(3, millis(anchor, 11), "饼干", "3块", type = RecordType.SNACK),
            foodRecord(4, millis(anchor, 12), "果汁", "50ml", type = RecordType.DRINK),
        )

        val summary = SummaryAggregationEngine().calculate(request(records))

        assertThat(summary.food).isNull()
    }

    @Test
    fun `food panel sums each lane per day and ignores snack and drink records`() = runBlocking {
        val records = listOf(
            foodRecord(1, millis(anchor.minusDays(1), 18), "南瓜", "2块"),
            foodRecord(2, millis(anchor, 8), " 米粉 ", "0.5碗"),
            foodRecord(3, millis(anchor, 12), "米粉", "1碗"),
            foodRecord(4, millis(anchor, 15), "饼干", "3块", type = RecordType.SNACK),
            foodRecord(5, millis(anchor, 16), "果汁", "50ml", type = RecordType.DRINK),
        )

        val summary = SummaryAggregationEngine().calculate(request(records, SummaryRange.Week))

        val panel = summary.food
        assertThat(panel).isNotNull()
        assertThat(panel!!.recordCount).isEqualTo(3)
        assertThat(panel.kindCount).isEqualTo(2)
        assertThat(panel.unparseableCount).isEqualTo(0)
        assertThat(panel.unparseableDayCounts).containsExactly(0, 0, 0, 0, 0, 0, 0).inOrder()
        // Rolling week 7/17–7/23: 7/22 is index 5, anchor 7/23 is index 6.
        assertThat(panel.lanes.map { it.name }).containsExactly("南瓜", "米粉").inOrder()
        val pumpkin = panel.lanes[0]
        assertThat(pumpkin.total).isWithin(1e-9).of(2.0)
        assertThat(pumpkin.dayValues).hasSize(7)
        assertThat(pumpkin.dayValues[5]).isWithin(1e-9).of(2.0)
        assertThat(pumpkin.dayValues[6]).isWithin(1e-9).of(0.0)
        val rice = panel.lanes[1]
        assertThat(rice.total).isWithin(1e-9).of(1.5)
        assertThat(rice.dayValues[6]).isWithin(1e-9).of(1.5)
    }

    @Test
    fun `food panel keeps top three lanes and merges the rest into 其他`() = runBlocking {
        // Rolling week 7/17–7/23: these Mon–Wed dates are window indices 3–5.
        val weekStart = LocalDate.of(2026, 7, 20)
        val records = listOf(
            foodRecord(1, millis(weekStart, 8), "米粉", "5碗"),
            foodRecord(2, millis(weekStart.plusDays(1), 8), "米粉", "5碗"),
            foodRecord(3, millis(weekStart, 9), "南瓜", "6块"),
            foodRecord(4, millis(weekStart.plusDays(1), 9), "苹果", "4块"),
            foodRecord(5, millis(weekStart.plusDays(2), 10), "面条", "3碗"),
            foodRecord(6, millis(weekStart.plusDays(2), 11), "酸奶", "2盒"),
        )

        val summary = SummaryAggregationEngine().calculate(request(records, SummaryRange.Week))

        val panel = summary.food!!
        assertThat(panel.recordCount).isEqualTo(6)
        assertThat(panel.kindCount).isEqualTo(5)
        assertThat(panel.lanes.map { it.name })
            .containsExactly("米粉", "南瓜", "苹果", "其他")
            .inOrder()
        assertThat(panel.lanes[0].total).isWithin(1e-9).of(10.0)
        assertThat(panel.lanes[1].total).isWithin(1e-9).of(6.0)
        assertThat(panel.lanes[2].total).isWithin(1e-9).of(4.0)
        val merged = panel.lanes.last()
        assertThat(merged.total).isWithin(1e-9).of(5.0)
        assertThat(merged.dayValues[3]).isWithin(1e-9).of(0.0)
        assertThat(merged.dayValues[5]).isWithin(1e-9).of(5.0)
    }

    @Test
    fun `food panel with only unparsable amounts still renders lanes with zero values`() = runBlocking {
        val records = listOf(
            foodRecord(1, millis(anchor, 8), "米粉", "一些"),
            foodRecord(2, millis(anchor, 9), "猪肝粉"),
        )

        val summary = SummaryAggregationEngine().calculate(request(records))

        // ≥1 record keeps the panel alive: zero-height bars + 未填量 note, not an empty state.
        val panel = summary.food!!
        assertThat(panel.recordCount).isEqualTo(2)
        assertThat(panel.kindCount).isEqualTo(2)
        assertThat(panel.lanes.map { it.name }).containsExactly("猪肝粉", "米粉").inOrder()
        assertThat(panel.unparseableCount).isEqualTo(2)
        assertThat(panel.unparseableDayCounts).containsExactly(2)
        assertThat(panel.lanes).isNotEmpty()
        panel.lanes.forEach { lane ->
            assertThat(lane.total).isWithin(1e-9).of(0.0)
            assertThat(lane.dayValues.single()).isWithin(1e-9).of(0.0)
        }
    }

    @Test
    fun `overflow amounts are unparseable and empty content gets a safe lane name`() = runBlocking {
        val records = listOf(
            foodRecord(1, millis(anchor, 8), "", "1碗"),
            foodRecord(2, millis(anchor, 9), "米粉", "9".repeat(400)),
        )

        val panel = SummaryAggregationEngine().calculate(request(records)).food!!

        assertThat(panel.recordCount).isEqualTo(2)
        assertThat(panel.unparseableCount).isEqualTo(1)
        assertThat(panel.unparseableDayCounts).containsExactly(1)
        assertThat(panel.lanes.map { it.name }).containsExactly("未注明", "米粉").inOrder()
        assertThat(panel.lanes[0].total).isWithin(1e-9).of(1.0)
        assertThat(panel.lanes[1].total).isWithin(1e-9).of(0.0)
    }

    @Test
    fun `merged lane is renamed when a real lane is literally named 其他`() = runBlocking {
        val records = listOf(
            foodRecord(1, millis(anchor, 8), "米粉", "5碗"),
            foodRecord(2, millis(anchor, 9), "其他", "4碗"),
            foodRecord(3, millis(anchor, 10), "南瓜", "3块"),
            foodRecord(4, millis(anchor, 11), "面条", "2碗"),
        )

        val summary = SummaryAggregationEngine().calculate(request(records, SummaryRange.Week))

        val panel = summary.food!!
        assertThat(panel.kindCount).isEqualTo(4)
        assertThat(panel.lanes.map { it.name })
            .containsExactly("米粉", "其他", "南瓜", "其余食材")
            .inOrder()
        assertThat(panel.lanes.last().total).isWithin(1e-9).of(2.0)
    }

    @Test
    fun `records without a parsable amount stay out of bars but count toward 未填量`() = runBlocking {
        val records = listOf(
            foodRecord(1, millis(anchor, 8), "米粉", "1.5碗"),
            foodRecord(2, millis(anchor, 9), "米粉", "一些"),
            foodRecord(3, millis(anchor, 10), "猪肝粉"),
            foodRecord(4, millis(anchor, 11), "猪肝粉", "半勺"),
        )

        val summary = SummaryAggregationEngine().calculate(request(records))

        val panel = summary.food!!
        assertThat(panel.recordCount).isEqualTo(4)
        assertThat(panel.kindCount).isEqualTo(2)
        assertThat(panel.unparseableCount).isEqualTo(3)
        assertThat(panel.unparseableDayCounts).containsExactly(3)
        assertThat(panel.lanes.map { it.name }).containsExactly("米粉", "猪肝粉").inOrder()
        assertThat(panel.lanes[0].total).isWithin(1e-9).of(1.5)
        assertThat(panel.lanes[0].dayValues.single()).isWithin(1e-9).of(1.5)
        assertThat(panel.lanes[1].total).isWithin(1e-9).of(0.0)
    }

    @Test
    fun `food panel tracks unparseable records on their natural days`() = runBlocking {
        val records = listOf(
            foodRecord(1, millis(anchor.minusDays(1), 8), "米粉", "几口"),
            foodRecord(2, millis(anchor, 8), "米粉", "一些"),
            foodRecord(3, millis(anchor, 9), "南瓜", "半2碗"),
            foodRecord(4, millis(anchor, 10), "米粉", "1碗"),
        )

        val panel = SummaryAggregationEngine()
            .calculate(request(records, SummaryRange.Week))
            .food!!

        assertThat(panel.unparseableCount).isEqualTo(3)
        assertThat(panel.unparseableDayCounts)
            .containsExactly(0, 0, 0, 0, 0, 1, 2)
            .inOrder()
    }

    @Test
    fun `food day attribution splits at midnight and drops records at or after anchor end`() = runBlocking {
        val records = listOf(
            // Before the rolling week window (starts 2026-07-17) → excluded.
            foodRecord(1, millis(anchor.minusDays(8), 8), "米粉", "7碗"),
            // 7/22 23:50 and 7/23 00:10 land on their own local days.
            foodRecord(2, millis(anchor.minusDays(1), 23, 50), "米粉", "1碗"),
            foodRecord(3, millis(anchor, 0, 10), "米粉", "2碗"),
            // At/after the anchor-day end (7/24 00:00) → excluded even inside the week.
            foodRecord(4, millis(anchor.plusDays(1), 0, 5), "米粉", "9碗"),
            foodRecord(5, millis(anchor.plusDays(1), 12), "南瓜", "1碗"),
        )

        val summary = SummaryAggregationEngine().calculate(request(records, SummaryRange.Week))

        val panel = summary.food!!
        assertThat(panel.recordCount).isEqualTo(2)
        assertThat(panel.kindCount).isEqualTo(1)
        assertThat(panel.lanes.single().name).isEqualTo("米粉")
        assertThat(panel.lanes.single().dayValues[5]).isWithin(1e-9).of(1.0)
        assertThat(panel.lanes.single().dayValues[6]).isWithin(1e-9).of(2.0)
        assertThat(panel.lanes.single().dayValues.count { it != 0.0 }).isEqualTo(2)
    }

    @Test
    fun `food panel follows day week and month windows`() = runBlocking {
        val records = listOf(
            foodRecord(1, millis(anchor, 8), "米粉", "1碗"),
            foodRecord(2, millis(anchor.minusDays(3), 8), "南瓜", "2块"),
            foodRecord(3, millis(anchor.minusDays(20), 8), "苹果", "4块"),
        )

        val day = SummaryAggregationEngine().calculate(request(records, SummaryRange.Day)).food!!
        val week = SummaryAggregationEngine().calculate(request(records, SummaryRange.Week)).food!!
        val month = SummaryAggregationEngine().calculate(request(records, SummaryRange.Month)).food!!

        assertThat(day.lanes.map { it.name }).containsExactly("米粉")
        assertThat(day.lanes.single().dayValues).hasSize(1)
        assertThat(day.lanes.single().dayValues.single()).isWithin(1e-9).of(1.0)
        assertThat(week.kindCount).isEqualTo(2)
        assertThat(week.lanes.map { it.name }).containsExactly("南瓜", "米粉").inOrder()
        assertThat(week.lanes[0].dayValues).hasSize(7)
        // 南瓜 sits on 7/20 = rolling-week window index 3.
        assertThat(week.lanes[0].dayValues[3]).isWithin(1e-9).of(2.0)
        assertThat(month.kindCount).isEqualTo(3)
        assertThat(month.lanes.map { it.name }).containsExactly("苹果", "南瓜", "米粉").inOrder()
        assertThat(month.lanes[0].dayValues).hasSize(30)
        assertThat(month.lanes[0].dayValues[9]).isWithin(1e-9).of(4.0)
    }

    @Test
    fun `food panel consumes duplicate projection main interpretation and drops source-role rows`() = runBlocking {
        val base = millis(anchor, 10)
        val records = listOf(
            // Suspected duplicate pair (different authors within 30 min): the panel
            // draws the lower bound, not the sum of both bowls.
            foodRecord(1, base, "米粉", "1碗").copy(createdByMembershipId = "m1"),
            foodRecord(2, base + 60_000L, "米粉", "1碗").copy(createdByMembershipId = "m2"),
            foodRecord(3, base + 120_000L, "南瓜", "5块"),
            // Source-role row: live for 来源详情, excluded from ordinary stats.
            foodRecord(4, base + 180_000L, "酸奶", "2盒")
                .copy(clientUuid = "source-role"),
        )

        val summary = SummaryAggregationEngine().calculate(
            request(records).copy(sourceRoleClientUuids = setOf("source-role")),
        )
        // The pair really forms an unresolved suspected-duplicate group…
        val projection = SuspectedDuplicateProjection.project(
            records = records,
            startDate = anchor,
            dayCount = 1,
            zone = zone,
        )
        assertThat(projection.openGroups).hasSize(1)
        // Rice is the unresolved pair (lower 1, upper 2). Pumpkin is exact.
        // The source-role row stays live only for 来源详情.
        val panel = summary.food!!
        assertThat(panel.recordCount).isEqualTo(3)
        assertThat(panel.kindCount).isEqualTo(2)
        assertThat(panel.lanes.map { it.name }).containsExactly("南瓜", "米粉").inOrder()
        assertThat(panel.lanes[0].total).isWithin(1e-9).of(5.0)
        assertThat(panel.lanes[1].total).isWithin(1e-9).of(1.0)
        assertThat(panel.lanes[1].dayValues.single()).isWithin(1e-9).of(1.0)
        assertThat(panel.amountMin).isWithin(1e-9).of(6.0)
        assertThat(panel.amountMax).isWithin(1e-9).of(7.0)
        assertThat(formatFoodAmountBound(panel.amountMin, panel.amountMax)).isEqualTo("6–7")
    }

    private fun millis(date: LocalDate, hour: Int, minute: Int = 0): Long =
        date.atTime(hour, minute).toInstant(zone).toEpochMilli()

    private fun request(
        records: List<Record>,
        range: SummaryRange,
    ): SummaryAggregationRequest = request(records).copy(range = range)

    private fun foodRecord(
        id: Long,
        timestamp: Long,
        content: String,
        amount: String? = null,
        type: RecordType = RecordType.BABY_FOOD,
    ): Record {
        val payloadJson = if (amount == null) {
            """{"content":"$content"}"""
        } else {
            """{"content":"$content","amount":"$amount"}"""
        }
        return record(
            id = id,
            type = type,
            timestamp = timestamp,
            payloadJson = payloadJson,
        )
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
