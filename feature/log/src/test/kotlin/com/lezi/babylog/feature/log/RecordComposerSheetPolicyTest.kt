package com.lezi.babylog.feature.log

import org.junit.Assert.assertTrue
import org.junit.Test

class RecordComposerSheetPolicyTest {
    @Test
    fun composerSkipsPartialAnchorSoImeCannotReanchorOverFocusedFields() {
        assertTrue(
            "RecordComposer must start fully expanded before a numeric field opens the IME",
            RECORD_COMPOSER_SKIP_PARTIALLY_EXPANDED,
        )
    }
}
