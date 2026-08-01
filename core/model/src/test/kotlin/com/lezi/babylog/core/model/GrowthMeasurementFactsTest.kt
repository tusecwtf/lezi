package com.lezi.babylog.core.model

import com.google.common.truth.Truth.assertThat
import java.time.LocalDate
import org.junit.Test

class GrowthMeasurementFactsTest {
    @Test
    fun weightAndLengthUseOneCanonicalPayloadDecision() {
        assertThat(GrowthMeasurementFacts.payload(RecordType.WEIGHT, 6.35))
            .isEqualTo(MeasurementPayload(RecordType.WEIGHT, 6_350.0, "g"))
        assertThat(GrowthMeasurementFacts.payload(RecordType.HEIGHT, 66.5))
            .isEqualTo(MeasurementPayload(RecordType.HEIGHT, 66.5, "cm"))
        assertThat(GrowthMeasurementFacts.payload(RecordType.WEIGHT, 0.0)).isNull()
    }

    @Test
    fun chronologicalAgeAndReferenceInterpolationAreStableFacts() {
        val birth = LocalDate.of(2026, 1, 31)
        val measured = LocalDate.of(2026, 4, 15)
        val monthAge = GrowthMeasurementFacts.monthAge(
            birthday = birth,
            measuredDate = measured,
        )
        val reference = GrowthMeasurementFacts.referenceAt(
            monthAge = 3f,
            bands = listOf(
                GrowthReferenceBand(0f, 2.5f, 3.3f, 4.5f),
                GrowthReferenceBand(6f, 6.5f, 8.0f, 9.5f),
            ),
        )

        assertThat(monthAge).isWithin(0.001f).of(2.5f)
        assertThat(reference!!.p3).isWithin(0.001f).of(4.5f)
        assertThat(reference.p50).isWithin(0.001f).of(5.65f)
        assertThat(reference.p97).isWithin(0.001f).of(7.0f)
        assertThat(reference.warningFor(7.0f)).contains("达到或高于")
        assertThat(reference.warningFor(4.4f)).contains("低于")
        assertThat(reference.warningFor(5.5f)).isNull()
    }

    @Test
    fun chronologicalAgeUsesCalendarMonthBoundaries() {
        assertThat(
            GrowthMeasurementFacts.monthAge(
                birthday = LocalDate.of(2026, 1, 31),
                measuredDate = LocalDate.of(2026, 2, 28),
            ),
        ).isEqualTo(1f)
        assertThat(
            GrowthMeasurementFacts.monthAge(
                birthday = LocalDate.of(2024, 2, 29),
                measuredDate = LocalDate.of(2025, 2, 28),
            ),
        ).isEqualTo(12f)
        assertThat(
            GrowthMeasurementFacts.monthAge(
                birthday = LocalDate.of(2026, 1, 31),
                measuredDate = LocalDate.of(2026, 1, 1),
            ),
        ).isEqualTo(0f)
    }

    @Test
    fun referenceAtAbstainsOutsideTheAvailableMonthRange() {
        val bands = listOf(
            GrowthReferenceBand(0f, 2.5f, 3.3f, 4.5f),
            GrowthReferenceBand(6f, 6.5f, 8.0f, 9.5f),
        )

        assertThat(GrowthMeasurementFacts.referenceAt(-0.01f, bands)).isNull()
        assertThat(GrowthMeasurementFacts.referenceAt(6.01f, bands)).isNull()
        assertThat(GrowthMeasurementFacts.referenceAt(0f, bands)).isNotNull()
        assertThat(GrowthMeasurementFacts.referenceAt(6f, bands)).isNotNull()
    }

    @Test
    fun referenceAtTreatsTheConfiguredMaximumMonthAsExclusive() {
        val bands = listOf(
            GrowthReferenceBand(81f, 18.2f, 23.4f, 30.6f),
            GrowthReferenceBand(84f, 18.2f, 23.4f, 30.6f),
        )

        assertThat(
            GrowthMeasurementFacts.referenceAt(
                monthAge = 83.99f,
                bands = bands,
                validUntilMonthExclusive = 84f,
            ),
        ).isNotNull()
        assertThat(
            GrowthMeasurementFacts.referenceAt(
                monthAge = 84f,
                bands = bands,
                validUntilMonthExclusive = 84f,
            ),
        ).isNull()
    }
}
