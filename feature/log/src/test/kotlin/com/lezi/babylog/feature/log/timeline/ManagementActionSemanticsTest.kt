package com.lezi.babylog.feature.log.timeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.lezi.babylog.feature.log.*
import com.lezi.babylog.feature.log.dock.*
import com.lezi.babylog.feature.log.composer.*
import com.lezi.babylog.feature.log.layout.*
import com.lezi.babylog.feature.log.photo.*

class ManagementActionSemanticsTest {
    @Test
    fun authorizedPlanExposesEditDeleteAndSkipWithoutGesture() {
        val invoked = mutableListOf<String>()
        val actions = rowManagementCustomActions(
            targetLabel = "用药护理计划",
            canEdit = true,
            canDelete = true,
            canSkip = true,
            skipEnabled = true,
            onEdit = { invoked += "edit" },
            onDelete = { invoked += "delete" },
            onSkip = { invoked += "skip"; true },
        )

        assertEquals(
            listOf("编辑用药护理计划", "删除用药护理计划", "跳过用药护理计划"),
            actions.map { it.label },
        )
        actions.forEach { assertTrue(it.action()) }
        assertEquals(listOf("edit", "delete", "skip"), invoked)
    }

    @Test
    fun unavailableActionsAreAbsentAndBusySkipCannotBeInvoked() {
        var skipped = false
        val actions = rowManagementCustomActions(
            targetLabel = "记录",
            canEdit = false,
            canDelete = false,
            canSkip = true,
            skipEnabled = false,
            onEdit = {},
            onDelete = {},
            onSkip = { skipped = true; true },
        )

        assertTrue(actions.isEmpty())
        assertFalse(skipped)
    }
}
