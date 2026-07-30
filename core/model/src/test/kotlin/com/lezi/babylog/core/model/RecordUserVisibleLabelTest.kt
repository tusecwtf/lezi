package com.lezi.babylog.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RecordUserVisibleLabelTest {
    @Test
    fun everyRecordTypeHasAnExplicitUserVisibleLabel() {
        val labels = RecordType.entries.associateWith(RecordType::businessLabel)

        assertEquals(RecordType.entries.toSet(), labels.keys)
        labels.forEach { (type, label) ->
            assertFalse("$type label must not be blank", label.isBlank())
            assertFalse("$type label must not expose enum name", label == type.name)
            assertFalse("$type label must not expose storage key", label == type.key)
        }
    }

    @Test
    fun storageKeyLookupUsesTheAuthorityAndUnknownTypesHaveANeutralFallback() {
        assertEquals("配方奶", recordTypeLabel("formula"))
        assertEquals("未知记录", recordTypeLabel("future_record_type"))
        assertEquals("未知记录", recordTypeLabel(""))
    }
}
