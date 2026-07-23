package com.lezi.babylog.feature.log

import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.designsystem.LeziGlyph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TypeGlyphTest {
    @Test
    fun everyRecordType_hasExplicitNonDiaperFallbackSemantics() {
        // Diaper-only types may use Pin.
        assertEquals(LeziGlyph.Pin, typeGlyph(RecordType.POOP))
        assertEquals(LeziGlyph.Pin, typeGlyph(RecordType.BOTH_DIAPER))

        // Regression: former emoji types must not render as diaper.
        for (t in listOf(
            RecordType.MEMO, RecordType.DIARY, RecordType.BATH, RecordType.WALK,
        )) {
            assertNotEquals("$t must not use Pin", LeziGlyph.Pin, typeGlyph(t))
        }

        // Full table — keep in sync with typeGlyph() in LogScreen.kt
        val expected = mapOf(
            RecordType.NURSING to LeziGlyph.Bottle,
            RecordType.FORMULA to LeziGlyph.Bottle,
            RecordType.PUMPED_FEED to LeziGlyph.Bottle,
            RecordType.PUMP_EXPRESS to LeziGlyph.Bottle,
            RecordType.BABY_FOOD to LeziGlyph.Bottle,
            RecordType.SNACK to LeziGlyph.Bottle,
            RecordType.DRINK to LeziGlyph.Bottle,
            RecordType.PEE to LeziGlyph.Drop,
            RecordType.POOP to LeziGlyph.Pin,
            RecordType.BOTH_DIAPER to LeziGlyph.Pin,
            RecordType.SLEEP to LeziGlyph.Moon,
            RecordType.TEMPERATURE to LeziGlyph.Plus,
            RecordType.MEDICINE to LeziGlyph.Plus,
            RecordType.HOSPITAL to LeziGlyph.Plus,
            RecordType.COUGH to LeziGlyph.Plus,
            RecordType.RASH to LeziGlyph.Plus,
            RecordType.VOMIT to LeziGlyph.Plus,
            RecordType.INJURY to LeziGlyph.Plus,
            RecordType.VACCINE to LeziGlyph.Plus,
            RecordType.OTHER to LeziGlyph.Plus,
            RecordType.HEIGHT to LeziGlyph.Plus,
            RecordType.WEIGHT to LeziGlyph.Plus,
            RecordType.HEAD to LeziGlyph.Plus,
            RecordType.CHEST to LeziGlyph.Plus,
            RecordType.FOOT_SIZE to LeziGlyph.Plus,
            RecordType.MEMO to LeziGlyph.Dot,
            RecordType.DIARY to LeziGlyph.Dot,
            RecordType.BATH to LeziGlyph.Dot,
            RecordType.WALK to LeziGlyph.Dot,
            RecordType.CUSTOM to LeziGlyph.Dot,
        )
        assertEquals(RecordType.entries.toSet(), expected.keys)
        for ((type, glyph) in expected) {
            assertEquals(type.name, glyph, typeGlyph(type))
        }
    }
}
