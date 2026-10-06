package com.lezi.babylog.core.database

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RecentNotePageSqlTest {
    @Test
    fun pageQueryDoesNotCollapseWithSqliteTrim() {
        assertThat(RECENT_NOTE_PAGE_SQL).doesNotContain("TRIM")
        assertThat(RECENT_NOTE_PAGE_SQL).contains("ORDER BY r.timestamp DESC, r.id DESC")
        assertThat(RECENT_NOTE_PAGE_SQL).contains("LIMIT :limit OFFSET :offset")
        assertThat(RECENT_NOTE_PAGE_SQL).contains("adoptionStatus = 'conflict_not_adopted'")
    }
}
