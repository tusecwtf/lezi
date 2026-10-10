package com.lezi.babylog.domain.carelog

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.model.Record
import com.lezi.babylog.core.model.RecordType
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertThrows
import org.junit.Test

class SuspectedDuplicateBoundsTest {

    private val zone = ZoneOffset.UTC
    private val day = LocalDate.of(2024, 6, 1)
    private val dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli()
    private val now = dayStart + 20 * 60 * 60 * 1000L

    @Test
    fun projectionUsesInstantHaloAcrossMidnightButBoundsOnlyTargetDay() = runTest {
        val previousDay = formula(
            "previous",
            dayStart - 10 * 60_000L,
            100,
            "m1",
        )
        val targetDay = formula(
            "target",
            dayStart + 10 * 60_000L,
            120,
            "m2",
        )

        val projection = SuspectedDuplicateProjection.project(
            records = listOf(previousDay, targetDay),
            startDate = day,
            dayCount = 1,
            zone = zone,
            now = now,
        )

        assertThat(projection.openGroups.single().memberClientUuids)
            .containsExactly("previous", "target")
        assertThat(projection.bounds.formulaMl).isEqualTo(IntBound(0, 120))
    }

    @Test
    fun oneInterpretationPerGroupAcrossRangeNeverInventsZeroLowerBound() = runTest {
        val records = listOf(
            formula("before-midnight", dayStart + 23 * 60 * 60_000L + 50 * 60_000L, 100, "m1"),
            formula("after-midnight", dayStart + 24 * 60 * 60_000L + 10 * 60_000L, 120, "m2"),
        )

        val projection = SuspectedDuplicateProjection.project(
            records = records,
            startDate = day,
            dayCount = 2,
            zone = zone,
            now = dayStart + 3 * 24 * 60 * 60_000L,
        )

        // Legal whole-range interpretations are {100}, {120}, and {100, 120}.
        assertThat(projection.bounds.formulaMl).isEqualTo(IntBound(100, 220))
        assertThat(projection.bounds.feedMl).isEqualTo(IntBound(100, 220))
        assertThat(projection.bounds.feedCount).isEqualTo(IntBound(1, 2))
    }

    @Test
    fun polynomialSolverMatchesExhaustiveInterpretationsForEveryPublicMetric() = runTest {
        val t = dayStart + 2 * 60 * 60_000L
        val sleep = metricRecord(
            "sleep",
            RecordType.SLEEP,
            t,
            "solo",
            """{"is_nap":true,"anomaly_flag":false}""",
        ).copy(endTimestamp = t + 45 * 60_000L)
        val records = listOf(
            metricRecord("f1", RecordType.FORMULA, t, "a", """{"amount_ml":100}"""),
            metricRecord("f2", RecordType.FORMULA, t + 1, "b", """{"amount_ml":120}"""),
            metricRecord("p1", RecordType.PUMPED_FEED, t, "a", """{"amount_ml":70}"""),
            metricRecord("p2", RecordType.PUMPED_FEED, t + 1, "b", """{"amount_ml":90}"""),
            metricRecord(
                "n1",
                RecordType.NURSING,
                t,
                "a",
                """{"left_min":5,"right_min":7,"amount_ml":30,"order":"LR","record_mode":"end"}""",
            ),
            metricRecord(
                "n2",
                RecordType.NURSING,
                t + 1,
                "b",
                """{"left_min":8,"right_min":9,"amount_ml":40,"order":"LR","record_mode":"end"}""",
            ),
            metricRecord("pee1", RecordType.PEE, t, "a"),
            metricRecord("pee2", RecordType.PEE, t + 1, "b"),
            metricRecord("poop1", RecordType.POOP, t, "a"),
            metricRecord("poop2", RecordType.POOP, t + 1, "b"),
            metricRecord("both1", RecordType.BOTH_DIAPER, t, "a"),
            metricRecord("both2", RecordType.BOTH_DIAPER, t + 1, "b"),
            metricRecord("temp1", RecordType.TEMPERATURE, t, "a", """{"celsius":36.5}"""),
            metricRecord("temp2", RecordType.TEMPERATURE, t + 1, "b", """{"celsius":38.0}"""),
            sleep,
        )
        val projection = SuspectedDuplicateProjection.project(records, day, 1, zone, now)
        val interpretations = exhaustiveInterpretations(records, projection.openGroups)
            .map { CareAggregation.range(it, day, 1, zone, now) }
        val bounds = projection.bounds

        assertThat(bounds.formulaMl).isEqualTo(intOracle(interpretations) { it.days.sumOf(CareDay::formulaMl) })
        assertThat(bounds.pumpedFeedMl)
            .isEqualTo(intOracle(interpretations) { it.days.sumOf(CareDay::pumpedFeedMl) })
        assertThat(bounds.nursingMl).isEqualTo(intOracle(interpretations) { it.days.sumOf(CareDay::nursingMl) })
        assertThat(bounds.nursingMinutes)
            .isEqualTo(longOracle(interpretations, CareRange::nursingMinutes))
        assertThat(bounds.feedMl).isEqualTo(intOracle(interpretations, CareRange::feedMl))
        assertThat(bounds.feedCount).isEqualTo(intOracle(interpretations, CareRange::feedCount))
        assertThat(bounds.peeCount).isEqualTo(intOracle(interpretations, CareRange::peeCount))
        assertThat(bounds.poopCount).isEqualTo(intOracle(interpretations, CareRange::poopCount))
        assertThat(bounds.sleepMinutes)
            .isEqualTo(longOracle(interpretations, CareRange::sleepMinutes))
        assertThat(bounds.sleepSegments)
            .isEqualTo(intOracle(interpretations, CareRange::sleepSegments))
        assertThat(bounds.temperatureCount)
            .isEqualTo(intOracle(interpretations) { it.temperatures.size })
        val averages = interpretations.mapNotNull { range ->
            range.temperatures.takeIf(List<Double>::isNotEmpty)?.average()
        }
        assertThat(bounds.temperatureAverage).isEqualTo(
            DoubleBound(averages.min(), averages.max()),
        )
        assertThat(bounds.hasUncertainty).isTrue()
    }

    @Test
    fun boundaryAndFactCutoffCountsAndAveragesMatchSmallExhaustiveOracle() = runTest {
        val dayEnd = dayStart + 24 * 3_600_000L
        data class Cut(val boundary: Long, val factEnd: Long, val clock: Long)
        val cuts = listOf(
            Cut(dayStart, dayEnd, dayEnd),             // left halo has a zero-contribution choice
            Cut(dayEnd, dayEnd, dayEnd + 3_600_000L),  // right halo likewise
            Cut(dayStart + 12 * 3_600_000L, dayStart + 12 * 3_600_000L, dayEnd),
            Cut(dayStart + 12 * 3_600_000L, dayEnd, dayStart + 12 * 3_600_000L - 1),
            Cut(dayStart + 12 * 3_600_000L, dayEnd, dayStart + 12 * 3_600_000L),
        )
        for (cut in cuts) for (withFixedTemperature in listOf(false, true)) {
            // Three two-member groups give only 3^3 = 27 interpretations.
            // The second member is exactly on the boundary: factEnd is exclusive,
            // while now is inclusive. The oracle below does not call aggregation.
            val records = buildList {
                for ((suffix, timestamp, member) in listOf(
                    Triple("a", cut.boundary - 1, "a"),
                    Triple("b", cut.boundary, "b"),
                )) {
                    add(formula("feed-$suffix", timestamp, if (suffix == "a") 100 else 120, member))
                    add(pee("pee-$suffix", timestamp, member))
                    add(metricRecord("temp-$suffix", RecordType.TEMPERATURE, timestamp, member,
                        if (suffix == "a") """{"celsius":36.5}""" else """{"celsius":38.0}"""))
                }
                if (withFixedTemperature) add(metricRecord(
                    "fixed-temp", RecordType.TEMPERATURE,
                    minOf(cut.factEnd, cut.clock) - 3_600_000L, "solo",
                    """{"celsius":37.0}""",
                ))
            }
            val projection = SuspectedDuplicateProjection.project(
                records, day, 1, zone, cut.clock, factEndExclusive = cut.factEnd,
            )
            assertThat(projection.openGroups.map { it.memberClientUuids.toSet() })
                .containsExactly(setOf("feed-a", "feed-b"), setOf("pee-a", "pee-b"), setOf("temp-a", "temp-b"))
            val interpretations = exhaustiveInterpretations(records, projection.openGroups)
            assertThat(interpretations).hasSize(27)
            val eligible = interpretations.map { choice ->
                choice.filter { it.timestamp >= dayStart && it.timestamp < cut.factEnd && it.timestamp <= cut.clock }
            }
            fun count(type: RecordType): IntBound {
                val counts = eligible.map { choice -> choice.count { it.type == type } }
                return IntBound(counts.min(), counts.max())
            }
            val bounds = projection.bounds
            assertThat(bounds.feedCount).isEqualTo(count(RecordType.FORMULA))
            assertThat(bounds.peeCount).isEqualTo(count(RecordType.PEE))
            assertThat(bounds.temperatureCount).isEqualTo(count(RecordType.TEMPERATURE))
            val volumes = eligible.map { choice -> choice.fold(0) { total, record ->
                total + when (record.clientUuid) { "feed-a" -> 100; "feed-b" -> 120; else -> 0 }
            } }
            assertThat(bounds.formulaMl).isEqualTo(IntBound(volumes.min(), volumes.max()))
            val temperatures = mapOf("temp-a" to 36.5, "temp-b" to 38.0, "fixed-temp" to 37.0)
            val averages = eligible.mapNotNull { choice ->
                choice.mapNotNull { temperatures[it.clientUuid] }.takeIf { it.isNotEmpty() }?.average()
            }
            val expectedAverage = averages.takeIf { it.isNotEmpty() }?.let { DoubleBound(it.min(), it.max()) }
            assertThat(bounds.temperatureAverage).isEqualTo(expectedAverage)
        }
    }

    @Test
    fun oneHundredTwentyGroupsFinishInsideFixedTimeoutWithoutCartesianMaterialization() = runTest {
        val records = buildList {
            repeat(120) { group ->
                val timestamp = dayStart + group * 31L * 60_000L
                add(formula("g${group}a", timestamp, 1, "a"))
                add(formula("g${group}b", timestamp + 1, 2, "b"))
            }
        }

        val projection = withTimeout(5_000) {
            SuspectedDuplicateProjection.project(
                records = records,
                startDate = day,
                dayCount = 4,
                zone = zone,
                now = dayStart + 5 * 24 * 60 * 60_000L,
            )
        }

        assertThat(projection.openGroups).hasSize(120)
        assertThat(projection.bounds.formulaMl).isEqualTo(IntBound(120, 360))
    }

    @Test
    fun cancelledProjectionStopsBeforeReadingLargeSnapshot() {
        val firstRead = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val records = BlockingRecordList(
            records = List(100_000) { index ->
                formula("cancel-$index", dayStart + index, 1, "m${index % 2}")
            },
            firstRead = firstRead,
            releaseRead = releaseRead,
        )

        runBlocking {
            val calculation = launch(Dispatchers.Default) {
                SuspectedDuplicateProjection.project(records, day, 1, zone, now)
            }
            check(firstRead.await(5, TimeUnit.SECONDS)) { "projection did not start" }
            calculation.cancel()
            releaseRead.countDown()
            calculation.cancelAndJoin()
        }

        assertThat(records.readCount.get()).isLessThan(records.size)
    }

    @Test
    fun postFilterLinearStagesCheckCancellationBeforeScanningTheirSnapshots() {
        val records = CountingRecordList(
            List(1_000) { index ->
                formula("late-cancel-$index", dayStart + index, 1, "m${index % 2}")
            },
        )
        val cancel = { throw CancellationException("test cancellation") }

        assertThrows(CancellationException::class.java) {
            SuspectedDuplicateGrouping.group(records, emptySet(), cancel)
        }
        assertThat(records.readCount.get()).isLessThan(records.size)

        records.readCount.set(0)
        assertThrows(CancellationException::class.java) {
            SuspectedDuplicateBounds.range(records, emptyList(), day, 1, zone, now, cancel)
        }
        assertThat(records.readCount.get()).isLessThan(records.size)

        records.readCount.set(0)
        assertThrows(CancellationException::class.java) {
            DuplicateTimelineIndex.build(records, emptyList(), emptySet(), cancel)
        }
        assertThat(records.readCount.get()).isLessThan(records.size)
    }

    @Test
    fun instantHaloUsesActualDstDayBoundariesForTwentyThreeAndTwentyFiveHourDays() = runTest {
        val newYork = ZoneId.of("America/New_York")
        listOf(
            LocalDate.of(2024, 3, 10) to 23L,
            LocalDate.of(2024, 11, 3) to 25L,
        ).forEach { (target, expectedHours) ->
            val start = target.atStartOfDay(newYork).toInstant().toEpochMilli()
            val end = target.plusDays(1).atStartOfDay(newYork).toInstant().toEpochMilli()
            assertThat((end - start) / 3_600_000L).isEqualTo(expectedHours)
            val projection = SuspectedDuplicateProjection.project(
                records = listOf(
                    formula("${target}-before", start - 10 * 60_000L, 100, "a"),
                    formula("${target}-inside", start + 10 * 60_000L, 120, "b"),
                ),
                startDate = target,
                dayCount = 1,
                zone = newYork,
                now = end,
            )
            assertThat(projection.openGroups).hasSize(1)
            assertThat(projection.bounds.formulaMl).isEqualTo(IntBound(0, 120))
        }
    }

    @Test
    fun temperatureOnlyGroupMarksUncertaintyWhileNonMetricGroupDoesNot() = runTest {
        val timestamp = dayStart + 1_000L
        val temperature = SuspectedDuplicateProjection.project(
            records = listOf(
                metricRecord("t1", RecordType.TEMPERATURE, timestamp, "a", """{"celsius":36.5}"""),
                metricRecord("t2", RecordType.TEMPERATURE, timestamp + 1, "b", """{"celsius":38.0}"""),
            ),
            startDate = day,
            dayCount = 1,
            zone = zone,
            now = now,
        ).bounds
        val bath = SuspectedDuplicateProjection.project(
            records = listOf(
                metricRecord("b1", RecordType.BATH, timestamp, "a"),
                metricRecord("b2", RecordType.BATH, timestamp + 1, "b"),
            ),
            startDate = day,
            dayCount = 1,
            zone = zone,
            now = now,
        ).bounds

        assertThat(temperature.temperatureCount).isEqualTo(IntBound(1, 2))
        assertThat(temperature.temperatureAverage).isEqualTo(DoubleBound(36.5, 38.0))
        assertThat(temperature.hasUncertainty).isTrue()
        assertThat(bath.hasUncertainty).isFalse()
    }

    @Test
    fun noOpenGroups_minEqualsMaxAndMatchesOrdinaryDay() {
        val records = listOf(
            formula("a", dayStart + 3_600_000L, 100, "m1"),
            formula("b", dayStart + 7_200_000L, 120, "m1"),
        )
        val bounds = SuspectedDuplicateBounds.day(records, emptyList(), day, zone, now)
        val ordinary = CareAggregation.day(records, day, zone, now)
        assertThat(bounds.formulaMl.min).isEqualTo(ordinary.formulaMl)
        assertThat(bounds.formulaMl.max).isEqualTo(ordinary.formulaMl)
        assertThat(bounds.feedCount.min).isEqualTo(2)
        assertThat(bounds.feedCount.max).isEqualTo(2)
        assertThat(bounds.hasUncertainty).isFalse()
    }

    @Test
    fun unresolvedFormulaPair_formulaMlAndFeedCountSpanSingleThroughBoth() {
        // Two cross-member formulas 100ml + 120ml in one open group.
        val records = listOf(
            formula("a", dayStart + 3_600_000L, 100, "m-owner"),
            formula("b", dayStart + 3_600_000L + 5 * 60_000L, 120, "m-member"),
        )
        val groups = SuspectedDuplicateGrouping.group(records)
        assertThat(groups).hasSize(1)
        val bounds = SuspectedDuplicateBounds.day(records, groups, day, zone, now)
        // min: one display source → min(100,120)=100, feedCount=1
        // max: both independent → 220, feedCount=2
        assertThat(bounds.formulaMl).isEqualTo(IntBound(100, 220))
        assertThat(bounds.feedMl).isEqualTo(IntBound(100, 220))
        assertThat(bounds.feedCount).isEqualTo(IntBound(1, 2))
        assertThat(bounds.hasUncertainty).isTrue()
        assertThat(bounds.formulaMl.formatRange()).isEqualTo("100–220")
    }

    @Test
    fun resolvedDisplayOnly_collapsesToSingleValueWithoutDeletingSourceRows() {
        val display = formula("display", dayStart + 3_600_000L, 100, "m1")
        val source = formula("source", dayStart + 3_600_000L + 1_000L, 120, "m2")
        val all = listOf(display, source)
        // Projection for stats drops source-role UUID; raw rows remain live.
        val projected = SuspectedDuplicateBounds.filterDisplayProjection(
            all,
            sourceRoleClientUuids = setOf("source"),
        )
        assertThat(projected.map { it.clientUuid }).containsExactly("display")
        val bounds = SuspectedDuplicateBounds.day(projected, emptyList(), day, zone, now)
        assertThat(bounds.formulaMl).isEqualTo(IntBound(100, 100))
        assertThat(source.deletedAt).isNull()
    }

    @Test
    fun peePair_countBoundsOneToTwo() {
        val records = listOf(
            pee("a", dayStart + 1_000L, "m1"),
            pee("b", dayStart + 2_000L, "m2"),
        )
        val groups = SuspectedDuplicateGrouping.group(records)
        val bounds = SuspectedDuplicateBounds.day(records, groups, day, zone, now)
        assertThat(bounds.peeCount).isEqualTo(IntBound(1, 2))
    }

    @Test
    fun independentPlusGroup_composesDeterministically() {
        // Independent is >30min away so it cannot join the suspected pair.
        val independent = formula("solo", dayStart + 1_000L, 50, "m1")
        val g1 = formula("g1", dayStart + 2 * 60 * 60_000L, 100, "m1")
        val g2 = formula("g2", dayStart + 2 * 60 * 60_000L + 1_000L, 100, "m2")
        val records = listOf(independent, g1, g2)
        val groups = SuspectedDuplicateGrouping.group(records)
        assertThat(groups).hasSize(1)
        assertThat(groups.single().memberClientUuids).containsExactly("g1", "g2").inOrder()
        val bounds = SuspectedDuplicateBounds.day(records, groups, day, zone, now)
        // fixed 50 + min(100,100)=100 → 150; fixed 50 + 200 → 250
        assertThat(bounds.formulaMl).isEqualTo(IntBound(150, 250))
        assertThat(bounds.feedCount).isEqualTo(IntBound(2, 3))
    }

    @Test
    fun groupDoesNotMutateRecordsOrFulfillmentState() {
        val records = listOf(
            formula("a", dayStart + 1_000L, 80, "m1"),
            formula("b", dayStart + 2_000L, 90, "m2"),
        )
        val before = records.map { it.copy() }
        val groups = SuspectedDuplicateGrouping.group(records)
        SuspectedDuplicateBounds.day(records, groups, day, zone, now)
        assertThat(records).isEqualTo(before)
        assertThat(records.all { it.deletedAt == null }).isTrue()
        assertThat(records.all { !it.syncDirty || it.syncDirty }).isTrue()
    }

    private fun formula(
        uuid: String,
        ts: Long,
        ml: Int,
        membership: String,
    ): Record = Record(
        id = uuid.hashCode().toLong().and(0xffffL),
        clientUuid = uuid,
        babyId = 1,
        type = RecordType.FORMULA,
        timestamp = ts,
        payloadJson = """{"amount_ml":$ml}""",
        updatedAt = ts,
        createdByMembershipId = membership,
    )

    private fun pee(uuid: String, ts: Long, membership: String): Record = Record(
        id = uuid.hashCode().toLong().and(0xffffL),
        clientUuid = uuid,
        babyId = 1,
        type = RecordType.PEE,
        timestamp = ts,
        payloadJson = """{"pee_amount":2}""",
        updatedAt = ts,
        createdByMembershipId = membership,
    )

    private fun metricRecord(
        uuid: String,
        type: RecordType,
        timestamp: Long,
        membership: String,
        payload: String = "{}",
    ): Record = Record(
        id = uuid.hashCode().toLong().and(0xffffL),
        clientUuid = uuid,
        babyId = 1,
        type = type,
        timestamp = timestamp,
        payloadJson = payload,
        updatedAt = timestamp,
        createdByMembershipId = membership,
    )

    private fun exhaustiveInterpretations(
        records: List<Record>,
        groups: List<SuspectedDuplicateGroup>,
    ): List<List<Record>> {
        val byUuid = records.associateBy(Record::clientUuid)
        val grouped = groups.flatMapTo(mutableSetOf(), SuspectedDuplicateGroup::memberClientUuids)
        var interpretations = listOf(records.filter { it.clientUuid !in grouped })
        groups.forEach { group ->
            val members = group.memberClientUuids.map(byUuid::getValue)
            val choices = members.map(::listOf) + listOf(members)
            interpretations = interpretations.flatMap { base -> choices.map { base + it } }
        }
        return interpretations
    }

    private fun intOracle(values: List<CareRange>, metric: (CareRange) -> Int): IntBound =
        IntBound(values.minOf(metric), values.maxOf(metric))

    private fun longOracle(values: List<CareRange>, metric: (CareRange) -> Long): LongBound =
        LongBound(values.minOf(metric), values.maxOf(metric))
}

private class BlockingRecordList(
    private val records: List<Record>,
    private val firstRead: CountDownLatch,
    private val releaseRead: CountDownLatch,
) : AbstractList<Record>() {
    val readCount = AtomicInteger()
    override val size: Int get() = records.size

    override fun get(index: Int): Record {
        if (readCount.getAndIncrement() == 0) {
            firstRead.countDown()
            check(releaseRead.await(5, TimeUnit.SECONDS)) { "record read was not released" }
        }
        return records[index]
    }
}

private class CountingRecordList(
    private val records: List<Record>,
) : AbstractList<Record>() {
    val readCount = AtomicInteger()
    override val size: Int get() = records.size

    override fun get(index: Int): Record {
        readCount.incrementAndGet()
        return records[index]
    }
}
