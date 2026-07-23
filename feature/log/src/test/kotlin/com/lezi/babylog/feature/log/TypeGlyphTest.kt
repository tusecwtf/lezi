package com.lezi.babylog.feature.log

import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.ui.presentation
import com.lezi.babylog.designsystem.LeziRecordGlyph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TypeGlyphTest {
    @Test
    fun logUsesTheSharedPresentationForEveryRecordType() {
        RecordType.entries.forEach { type ->
            assertEquals(type.name, type.presentation.label, typeLabel(type))
            assertEquals(type.name, type.presentation.glyph, typeGlyph(type))
        }
    }

    @Test
    fun frequentActionsHaveDistinctPurposeBuiltGlyphs() {
        assertEquals(LeziRecordGlyph.Nursing, typeGlyph(RecordType.NURSING))
        assertEquals(LeziRecordGlyph.Bottle, typeGlyph(RecordType.FORMULA))
        assertEquals(LeziRecordGlyph.Pee, typeGlyph(RecordType.PEE))
        assertEquals(LeziRecordGlyph.Poop, typeGlyph(RecordType.POOP))
        assertEquals(LeziRecordGlyph.Sleep, typeGlyph(RecordType.SLEEP))

        assertNotEquals(typeGlyph(RecordType.NURSING), typeGlyph(RecordType.FORMULA))
        assertNotEquals(typeGlyph(RecordType.PEE), typeGlyph(RecordType.POOP))
    }

    @Test
    fun moreRecordAccessibilityLabelContainsOnlyThePrimaryLabel() {
        RecordType.entries.forEach { type ->
            assertEquals(
                type.name,
                "添加${type.presentation.label}",
                moreRecordContentDescription(type),
            )
        }
    }
}
