package com.lezi.babylog.feature.log

import com.lezi.babylog.core.model.RecordItemIdentity
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.SettingsLocal
import com.lezi.babylog.domain.CustomRecordItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MoreSheetCatalogTest {
    @Test
    fun catalogIncludesCurrentBuiltInsAndConcreteCustoms() {
        val settings = SettingsLocal(hiddenItems = emptySet())
        val customs = listOf(
            CustomRecordItem(id = 3, name = "抚触", iconSlot = 1, sortOrder = 0),
            CustomRecordItem(id = 5, name = "游泳", iconSlot = 2, sortOrder = 1),
        )

        val catalog = moreSheetCatalog(settings, customs)
        val keys = catalog.map { it.identity.catalogKey }

        assertFalse(keys.contains("custom"))
        assertTrue(keys.contains("diary"))
        assertTrue(keys.contains("pee"))
        assertTrue(keys.contains("custom:3"))
        assertTrue(keys.contains("custom:5"))
        assertEquals(
            listOf("抚触", "游泳"),
            catalog.filterIsInstance<MoreCatalogEntry.Custom>().map { it.label },
        )
    }

    @Test
    fun catalogRespectsHiddenBuiltInsAndCustomKeys() {
        val settings = SettingsLocal(
            hiddenItems = setOf("pee", RecordItemIdentity.customCatalogKey(3L)),
        )
        val customs = listOf(
            CustomRecordItem(id = 3, name = "抚触", iconSlot = 1, sortOrder = 0),
            CustomRecordItem(id = 5, name = "游泳", iconSlot = 2, sortOrder = 1),
        )

        val keys = moreSheetCatalog(settings, customs).map { it.identity.catalogKey }

        assertFalse(keys.contains("pee"))
        assertFalse(keys.contains("custom:3"))
        assertTrue(keys.contains("custom:5"))
    }

    @Test
    fun quickSuggestionsOnlyOfferConcreteBuiltIns() {
        val suggestions = moreSheetQuickSuggestions(SettingsLocal(), emptyList())
        val types = suggestions.filterIsInstance<MoreCatalogEntry.BuiltIn>().map { it.type }

        assertFalse(types.contains(RecordType.CUSTOM))
        assertTrue(types.contains(RecordType.DIARY) || types.contains(RecordType.POOP))
    }

    @Test
    fun customAccessibilityUsesDefinitionName() {
        val entry = MoreCatalogEntry.Custom(
            CustomRecordItem(id = 9, name = "抚触", iconSlot = 0, sortOrder = 0),
        )
        assertEquals("添加抚触", moreRecordContentDescription(entry))
    }

    @Test
    fun catalogPlacesCustomsUnderCustomSectionAndRespectsCategoryOrder() {
        val settings = SettingsLocal(
            categoryOrderJson = """["custom","feeding","excretion","routine","health","growth"]""",
            itemOrderJson = """["custom:5","custom:3","formula","nursing"]""",
        )
        val customs = listOf(
            CustomRecordItem(id = 3, name = "抚触", iconSlot = 1, sortOrder = 0),
            CustomRecordItem(id = 5, name = "游泳", iconSlot = 2, sortOrder = 1),
        )
        val catalog = moreSheetCatalog(settings, customs)
        assertEquals(
            com.lezi.babylog.core.ui.RecordSection.Custom,
            catalog.first { it.identity.catalogKey == "custom:5" }.section,
        )
        val keys = catalog.map { it.identity.catalogKey }
        assertTrue(keys.indexOf("custom:5") < keys.indexOf("custom:3"))
        assertTrue(keys.indexOf("custom:3") < keys.indexOf("formula"))
    }
}
