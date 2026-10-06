package com.lezi.babylog.feature.widget

import com.lezi.babylog.core.model.RecordType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WidgetComposerContractTest {
    @Test
    fun quickActionRouteTargetsNewComposerAndCarriesBabyAndType() {
        val route = WidgetComposerRoute(
            babyId = 42,
            type = RecordType.FORMULA,
        )

        assertEquals(
            "lezi://composer/new?babyId=42&type=formula",
            route.dataUri,
        )
        assertEquals(
            "com.lezi.babylog.action.OPEN_RECORD_COMPOSER",
            WidgetComposerContract.ACTION_OPEN_RECORD_COMPOSER,
        )
    }

    @Test
    fun targetDecoderRejectsUnknownTypeAndInvalidBaby() {
        assertEquals(
            WidgetComposerTarget(42, RecordType.FORMULA),
            WidgetComposerContract.decodeTarget(42, "formula"),
        )
        assertNull(WidgetComposerContract.decodeTarget(-1, "formula"))
        assertNull(WidgetComposerContract.decodeTarget(42, "future"))
    }

    @Test
    fun untrustedIntentDecoderRequiresCanonicalRouteToMatchExtras() {
        val canonical = WidgetComposerIntentSnapshot(
            action = WidgetComposerContract.ACTION_OPEN_RECORD_COMPOSER,
            scheme = "lezi",
            host = "composer",
            pathSegments = listOf("new"),
            queryBabyId = "42",
            queryRecordTypeKey = "formula",
            extraBabyId = 42,
            extraRecordTypeKey = "formula",
        )

        assertEquals(
            WidgetComposerTarget(42, RecordType.FORMULA),
            WidgetComposerContract.decodeUntrustedTarget(canonical),
        )
        assertNull(
            WidgetComposerContract.decodeUntrustedTarget(
                canonical.copy(queryBabyId = "43"),
            ),
        )
        assertNull(
            WidgetComposerContract.decodeUntrustedTarget(
                canonical.copy(pathSegments = listOf("edit")),
            ),
        )
        assertNull(
            WidgetComposerContract.decodeUntrustedTarget(
                canonical.copy(action = "attacker.action.OPEN"),
            ),
        )
    }
}
