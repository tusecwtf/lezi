package com.lezi.babylog.feature.log

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.lezi.babylog.core.ui.RecordSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutDragSessionTest {
    @Test
    fun boundSlotReleasedOverLockedMoreIsAnExplicitNoOp() {
        val targets = LayoutVisibleTargetSnapshot(
            revision = 1,
            dockBounds = Rect(0f, 0f, 500f, 100f),
            quickSlotBounds = mapOf(
                0 to Rect(0f, 0f, 100f, 100f),
                1 to Rect(100f, 0f, 200f, 100f),
                2 to Rect(200f, 0f, 300f, 100f),
                3 to Rect(300f, 0f, 400f, 100f),
            ),
            lockedMoreBounds = Rect(400f, 0f, 500f, 100f),
        )
        val session = LayoutDragSession(
            token = 31L,
            source = LayoutDragSource.BoundSlot(slotIndex = 0, catalogKey = "pee"),
        )

        val update = session.update(
            token = 31L,
            pointerWindow = Offset(450f, 50f),
            targets = targets,
        )

        assertEquals(LayoutHitRegion.LockedMore, update.hitRegion)
        assertNull(update.currentTarget)
        assertNull(
            session.finish(
                token = 31L,
                pointerWindow = Offset(450f, 50f),
                targets = targets,
            ),
        )
    }

    @Test
    fun boundSlotReleasedInDockGapDoesNotClearTheSlot() {
        val targets = LayoutVisibleTargetSnapshot(
            revision = 1,
            dockBounds = Rect(0f, 0f, 500f, 100f),
            quickSlotBounds = mapOf(
                0 to Rect(0f, 0f, 90f, 100f),
                1 to Rect(100f, 0f, 190f, 100f),
                2 to Rect(200f, 0f, 290f, 100f),
                3 to Rect(300f, 0f, 390f, 100f),
            ),
            lockedMoreBounds = Rect(400f, 0f, 490f, 100f),
        )
        val session = LayoutDragSession(
            token = 32L,
            source = LayoutDragSource.BoundSlot(slotIndex = 0, catalogKey = "pee"),
        )

        val update = session.update(
            token = 32L,
            pointerWindow = Offset(395f, 50f),
            targets = targets,
        )

        assertEquals(LayoutHitRegion.DockGap, update.hitRegion)
        assertNull(update.currentTarget)
        assertNull(
            session.finish(
                token = 32L,
                pointerWindow = Offset(395f, 50f),
                targets = targets,
            ),
        )
    }

    @Test
    fun boundSlotReleasedOutsideDockClearsOnlyItsSourceSlot() {
        val targets = LayoutVisibleTargetSnapshot(
            revision = 1,
            dockBounds = Rect(0f, 0f, 500f, 100f),
            quickSlotBounds = emptyMap(),
            lockedMoreBounds = Rect(400f, 0f, 500f, 100f),
        )
        val session = LayoutDragSession(
            token = 33L,
            source = LayoutDragSource.BoundSlot(slotIndex = 2, catalogKey = "sleep"),
        )

        val update = session.update(
            token = 33L,
            pointerWindow = Offset(600f, 200f),
            targets = targets,
        )

        assertEquals(LayoutDropTarget.OutsideDock, update.currentTarget)
        assertEquals(
            LayoutEditIntent.ClearSlot(2),
            session.finish(
                token = 33L,
                pointerWindow = Offset(600f, 200f),
                targets = targets,
            ),
        )
    }

    @Test
    fun boundSlotReleasedOverCatalogStillCountsAsOutsideDockAndClears() {
        val targets = LayoutVisibleTargetSnapshot(
            revision = 1,
            dockBounds = Rect(0f, 0f, 500f, 100f),
            quickSlotBounds = emptyMap(),
            lockedMoreBounds = null,
            catalogItemBounds = mapOf(
                "formula" to LayoutCatalogTargetBounds(
                    section = RecordSection.Feeding,
                    toIndex = 1,
                    bounds = Rect(100f, 200f, 200f, 300f),
                ),
            ),
        )
        val session = LayoutDragSession(
            token = 330L,
            source = LayoutDragSource.BoundSlot(slotIndex = 2, catalogKey = "sleep"),
        )

        val update = session.update(
            token = 330L,
            pointerWindow = Offset(150f, 250f),
            targets = targets,
        )

        assertEquals(LayoutHitRegion.CatalogItem("formula"), update.hitRegion)
        assertEquals(LayoutDropTarget.OutsideDock, update.currentTarget)
        assertEquals(
            LayoutEditIntent.ClearSlot(2),
            session.finish(
                token = 330L,
                pointerWindow = Offset(150f, 250f),
                targets = targets,
            ),
        )
    }

    @Test
    fun boundSlotReleasedOnAnotherSlotSwapsThoseExactSlots() {
        val targets = LayoutVisibleTargetSnapshot(
            revision = 1,
            dockBounds = Rect(0f, 0f, 500f, 100f),
            quickSlotBounds = mapOf(
                0 to Rect(0f, 0f, 100f, 100f),
                3 to Rect(300f, 0f, 400f, 100f),
            ),
            lockedMoreBounds = Rect(400f, 0f, 500f, 100f),
        )
        val session = LayoutDragSession(
            token = 34L,
            source = LayoutDragSource.BoundSlot(slotIndex = 0, catalogKey = "pee"),
        )

        val update = session.update(
            token = 34L,
            pointerWindow = Offset(350f, 50f),
            targets = targets,
        )

        assertEquals(LayoutHitRegion.QuickSlot(3), update.hitRegion)
        assertEquals(LayoutDropTarget.QuickSlot(3), update.currentTarget)
        assertEquals(
            LayoutEditIntent.SwapSlots(0, 3),
            session.finish(
                token = 34L,
                pointerWindow = Offset(350f, 50f),
                targets = targets,
            ),
        )
    }

    @Test
    fun catalogItemReleasedOnSlotAssignsThatExactSlot() {
        val targets = LayoutVisibleTargetSnapshot(
            revision = 1,
            dockBounds = Rect(0f, 0f, 500f, 100f),
            quickSlotBounds = mapOf(1 to Rect(100f, 0f, 200f, 100f)),
            lockedMoreBounds = Rect(400f, 0f, 500f, 100f),
        )
        val session = LayoutDragSession(
            token = 35L,
            source = LayoutDragSource.CatalogItem(
                catalogKey = "bath",
                section = RecordSection.Routine,
            ),
        )

        val update = session.update(
            token = 35L,
            pointerWindow = Offset(150f, 50f),
            targets = targets,
        )

        assertEquals(LayoutDropTarget.QuickSlot(1), update.currentTarget)
        assertEquals(
            LayoutEditIntent.AssignToSlot(1, "bath"),
            session.finish(
                token = 35L,
                pointerWindow = Offset(150f, 50f),
                targets = targets,
            ),
        )
    }

    @Test
    fun deletedItemOverSlotRestoresWithoutAdvertisingSlotAssignment() {
        val targets = LayoutVisibleTargetSnapshot(
            revision = 1,
            dockBounds = Rect(0f, 0f, 500f, 100f),
            quickSlotBounds = mapOf(1 to Rect(100f, 0f, 200f, 100f)),
            lockedMoreBounds = Rect(400f, 0f, 500f, 100f),
            localDeletedBounds = Rect(0f, 300f, 500f, 400f),
        )
        val session = LayoutDragSession(
            token = 36L,
            source = LayoutDragSource.LocalDeleted(catalogKey = "sleep"),
        )

        val update = session.update(
            token = 36L,
            pointerWindow = Offset(150f, 50f),
            targets = targets,
        )

        assertEquals(LayoutHitRegion.QuickSlot(1), update.hitRegion)
        assertEquals(LayoutDropTarget.RestoreCatalog, update.currentTarget)
        assertEquals(
            LayoutEditIntent.RestoreFromLocalDeleted("sleep"),
            session.finish(
                token = 36L,
                pointerWindow = Offset(150f, 50f),
                targets = targets,
            ),
        )
    }

    @Test
    fun catalogItemReleasedInLocalDeletedAreaMovesThere() {
        val targets = LayoutVisibleTargetSnapshot(
            revision = 1,
            dockBounds = Rect(0f, 0f, 500f, 100f),
            quickSlotBounds = emptyMap(),
            lockedMoreBounds = Rect(400f, 0f, 500f, 100f),
            localDeletedBounds = Rect(0f, 300f, 500f, 400f),
        )
        val session = LayoutDragSession(
            token = 37L,
            source = LayoutDragSource.CatalogItem(
                catalogKey = "bath",
                section = RecordSection.Routine,
            ),
        )

        val update = session.update(
            token = 37L,
            pointerWindow = Offset(250f, 350f),
            targets = targets,
        )

        assertEquals(LayoutDropTarget.LocalDeleted, update.currentTarget)
        assertEquals(
            LayoutEditIntent.MoveToLocalDeleted("bath"),
            session.finish(
                token = 37L,
                pointerWindow = Offset(250f, 350f),
                targets = targets,
            ),
        )
    }

    @Test
    fun catalogItemReleasedOnSameSectionItemUsesItsExactSectionIndex() {
        val targets = LayoutVisibleTargetSnapshot(
            revision = 1,
            dockBounds = Rect(0f, 0f, 500f, 100f),
            quickSlotBounds = emptyMap(),
            lockedMoreBounds = null,
            catalogItemBounds = mapOf(
                "formula" to LayoutCatalogTargetBounds(
                    section = RecordSection.Feeding,
                    toIndex = 4,
                    bounds = Rect(100f, 200f, 200f, 300f),
                ),
            ),
        )
        val session = LayoutDragSession(
            token = 38L,
            source = LayoutDragSource.CatalogItem(
                catalogKey = "nursing",
                section = RecordSection.Feeding,
            ),
        )

        val update = session.update(
            token = 38L,
            pointerWindow = Offset(150f, 250f),
            targets = targets,
        )

        assertEquals(
            LayoutDropTarget.CatalogItem(catalogKey = "formula", toIndex = 4),
            update.currentTarget,
        )
        assertEquals(
            LayoutEditIntent.ReorderItemInSection("nursing", 4),
            session.finish(
                token = 38L,
                pointerWindow = Offset(150f, 250f),
                targets = targets,
            ),
        )
    }

    @Test
    fun staleTokenCannotResolveOrFinishCurrentSession() {
        val targets = LayoutVisibleTargetSnapshot(
            revision = 1,
            dockBounds = Rect(0f, 0f, 500f, 100f),
            quickSlotBounds = mapOf(1 to Rect(100f, 0f, 200f, 100f)),
            lockedMoreBounds = Rect(400f, 0f, 500f, 100f),
        )
        val session = LayoutDragSession(
            token = 41L,
            source = LayoutDragSource.BoundSlot(slotIndex = 0, catalogKey = "pee"),
        )

        val stale = session.update(
            token = 40L,
            pointerWindow = Offset(150f, 50f),
            targets = targets,
        )

        assertFalse(stale.accepted)
        assertNull(stale.currentTarget)
        assertNull(
            session.finish(
                token = 40L,
                pointerWindow = Offset(150f, 50f),
                targets = targets,
            ),
        )
        assertEquals(
            LayoutEditIntent.SwapSlots(0, 1),
            session.finish(
                token = 41L,
                pointerWindow = Offset(150f, 50f),
                targets = targets,
            ),
        )
    }

    @Test
    fun olderBoundsRevisionCannotOverrideCurrentVisibleTargets() {
        fun targets(revision: Long, slotIndex: Int) = LayoutVisibleTargetSnapshot(
            revision = revision,
            dockBounds = Rect(0f, 0f, 500f, 100f),
            quickSlotBounds = mapOf(slotIndex to Rect(100f, 0f, 200f, 100f)),
            lockedMoreBounds = Rect(400f, 0f, 500f, 100f),
        )
        val session = LayoutDragSession(
            token = 42L,
            source = LayoutDragSource.BoundSlot(slotIndex = 0, catalogKey = "pee"),
        )
        session.update(
            token = 42L,
            pointerWindow = Offset(150f, 50f),
            targets = targets(revision = 5, slotIndex = 2),
        )

        val stale = session.update(
            token = 42L,
            pointerWindow = Offset(150f, 50f),
            targets = targets(revision = 4, slotIndex = 1),
        )

        assertFalse(stale.accepted)
        assertNull(
            session.finish(
                token = 42L,
                pointerWindow = Offset(150f, 50f),
                targets = targets(revision = 4, slotIndex = 1),
            ),
        )
        assertEquals(
            LayoutEditIntent.SwapSlots(0, 3),
            session.finish(
                token = 42L,
                pointerWindow = Offset(150f, 50f),
                targets = targets(revision = 6, slotIndex = 3),
            ),
        )
    }

    @Test
    fun backCancellationIsIdempotentAndCannotEmitAnIntent() {
        val targets = LayoutVisibleTargetSnapshot(
            revision = 1,
            dockBounds = Rect(0f, 0f, 500f, 100f),
            quickSlotBounds = mapOf(1 to Rect(100f, 0f, 200f, 100f)),
            lockedMoreBounds = Rect(400f, 0f, 500f, 100f),
        )
        val session = LayoutDragSession(
            token = 43L,
            source = LayoutDragSource.BoundSlot(slotIndex = 0, catalogKey = "pee"),
        )

        assertTrue(session.cancel(LayoutDragCancelReason.Back))
        assertEquals(LayoutDragCancelReason.Back, session.cancellationReason)
        assertFalse(session.cancel(LayoutDragCancelReason.Back))
        assertNull(
            session.finish(
                token = 43L,
                pointerWindow = Offset(150f, 50f),
                targets = targets,
            ),
        )
    }

    @Test
    fun unregisteringAndMovingVisibleNodesRemovesTheirOldHitRects() {
        val registry = LayoutVisibleTargetRegistry()
        val node = LayoutTargetNode.CatalogItem(
            catalogKey = "formula",
            section = RecordSection.Feeding,
            toIndex = 1,
        )
        registry.register(node, Rect(0f, 100f, 100f, 200f))
        val beforeMove = registry.snapshot()

        registry.register(node, Rect(200f, 100f, 300f, 200f))
        val afterMove = registry.snapshot()

        assertTrue(beforeMove.catalogItemBounds.getValue("formula").bounds.contains(Offset(50f, 150f)))
        assertFalse(afterMove.catalogItemBounds.getValue("formula").bounds.contains(Offset(50f, 150f)))
        assertTrue(afterMove.catalogItemBounds.getValue("formula").bounds.contains(Offset(250f, 150f)))
        assertTrue(registry.unregister(node))
        assertFalse(registry.unregister(node))
        assertFalse(registry.snapshot().catalogItemBounds.containsKey("formula"))
    }

    @Test
    fun lockedMoreWinsOverAnOverlappingSlotAndRemainsANoOp() {
        val targets = LayoutVisibleTargetSnapshot(
            revision = 1,
            dockBounds = Rect(0f, 0f, 500f, 100f),
            quickSlotBounds = mapOf(3 to Rect(350f, 0f, 450f, 100f)),
            lockedMoreBounds = Rect(400f, 0f, 500f, 100f),
        )
        val session = LayoutDragSession(
            token = 44L,
            source = LayoutDragSource.BoundSlot(slotIndex = 0, catalogKey = "pee"),
        )

        val update = session.update(44L, Offset(425f, 50f), targets)

        assertEquals(LayoutHitRegion.LockedMore, update.hitRegion)
        assertNull(update.currentTarget)
        assertNull(session.finish(44L, Offset(425f, 50f), targets))
    }

    @Test
    fun catalogDropAcrossSectionsIsNoOp() {
        val targets = LayoutVisibleTargetSnapshot(
            revision = 1,
            dockBounds = Rect(0f, 0f, 500f, 100f),
            quickSlotBounds = emptyMap(),
            lockedMoreBounds = null,
            catalogItemBounds = mapOf(
                "pee" to LayoutCatalogTargetBounds(
                    section = RecordSection.Excretion,
                    toIndex = 0,
                    bounds = Rect(100f, 200f, 200f, 300f),
                ),
            ),
        )
        val session = LayoutDragSession(
            token = 45L,
            source = LayoutDragSource.CatalogItem("nursing", RecordSection.Feeding),
        )

        val update = session.update(45L, Offset(150f, 250f), targets)

        assertEquals(LayoutHitRegion.CatalogItem("pee"), update.hitRegion)
        assertNull(update.currentTarget)
        assertNull(session.finish(45L, Offset(150f, 250f), targets))
    }

    @Test
    fun boundSlotDroppedBackOnItselfIsNoOp() {
        val targets = LayoutVisibleTargetSnapshot(
            revision = 1,
            dockBounds = Rect(0f, 0f, 500f, 100f),
            quickSlotBounds = mapOf(2 to Rect(200f, 0f, 300f, 100f)),
            lockedMoreBounds = null,
        )
        val session = LayoutDragSession(
            token = 46L,
            source = LayoutDragSource.BoundSlot(slotIndex = 2, catalogKey = "sleep"),
        )

        val update = session.update(46L, Offset(250f, 50f), targets)

        assertEquals(LayoutHitRegion.QuickSlot(2), update.hitRegion)
        assertNull(update.currentTarget)
        assertNull(session.finish(46L, Offset(250f, 50f), targets))
    }

    @Test
    fun catalogBlankSpaceNeverClearsOrHides() {
        val targets = LayoutVisibleTargetSnapshot(
            revision = 1,
            dockBounds = Rect(0f, 0f, 500f, 100f),
            quickSlotBounds = emptyMap(),
            lockedMoreBounds = null,
        )
        val session = LayoutDragSession(
            token = 47L,
            source = LayoutDragSource.CatalogItem("nursing", RecordSection.Feeding),
        )

        assertNull(session.finish(47L, Offset(800f, 600f), targets))
    }

    @Test
    fun disposeAndConfigurationCancellationCannotEmitIntents() {
        for (reason in listOf(
            LayoutDragCancelReason.Dispose,
            LayoutDragCancelReason.ConfigurationChange,
        )) {
            val targets = LayoutVisibleTargetSnapshot(
                revision = 1,
                dockBounds = Rect(0f, 0f, 500f, 100f),
                quickSlotBounds = mapOf(1 to Rect(100f, 0f, 200f, 100f)),
                lockedMoreBounds = null,
            )
            val session = LayoutDragSession(
                token = 48L,
                source = LayoutDragSource.BoundSlot(slotIndex = 0, catalogKey = "pee"),
            )

            assertTrue(session.cancel(reason))
            assertNull(session.finish(48L, Offset(150f, 50f), targets))
        }
    }

    @Test
    fun lifecyclePolicyIgnoresInitialValuesAndMapsOnlyRealEditorExits() {
        val policy = LayoutDragLifecyclePolicy(
            initialCancelSignal = 7L,
            initialConfigurationKey = "portrait-light",
        )

        assertNull(policy.cancelReasonForSignal(7L))
        assertEquals(LayoutDragCancelReason.Back, policy.cancelReasonForSignal(8L))
        assertNull(policy.cancelReasonForConfiguration("portrait-light"))
        assertEquals(
            LayoutDragCancelReason.ConfigurationChange,
            policy.cancelReasonForConfiguration("landscape-light"),
        )
        assertEquals(LayoutDragCancelReason.Dispose, policy.disposeReason())
    }

    @Test
    fun staleCompositionOwnerCannotUnregisterCurrentReplacement() {
        val registry = LayoutVisibleTargetRegistry()
        val oldOwner = Any()
        val newOwner = Any()
        val node = LayoutTargetNode.QuickSlot(1)
        registry.register(node, Rect(0f, 0f, 100f, 100f), oldOwner)
        registry.register(node, Rect(200f, 0f, 300f, 100f), newOwner)

        assertFalse(registry.unregister(node, oldOwner))
        assertEquals(
            Rect(200f, 0f, 300f, 100f),
            registry.snapshot().quickSlotBounds.getValue(1),
        )
        assertTrue(registry.unregister(node, newOwner))
    }

    @Test
    fun categoryHeadingDropEmitsOneExactCategoryIndexIntent() {
        val targets = LayoutVisibleTargetSnapshot(
            revision = 1,
            dockBounds = null,
            quickSlotBounds = emptyMap(),
            lockedMoreBounds = null,
            categoryHeadingBounds = mapOf(
                RecordSection.Routine to LayoutCategoryHeadingTargetBounds(
                    toIndex = 2,
                    bounds = Rect(0f, 200f, 400f, 260f),
                ),
            ),
        )
        val session = LayoutDragSession(
            token = 49L,
            source = LayoutDragSource.CategoryHeading(RecordSection.Custom),
        )

        val update = session.update(49L, Offset(200f, 230f), targets)

        assertEquals(LayoutHitRegion.CategoryHeading(RecordSection.Routine), update.hitRegion)
        assertEquals(
            LayoutDropTarget.CategoryHeading(RecordSection.Routine, 2),
            update.currentTarget,
        )
        assertEquals(
            LayoutEditIntent.MoveCategoryToIndex(RecordSection.Custom, 2),
            session.finish(49L, Offset(200f, 230f), targets),
        )
    }

    @Test
    fun categoryHeadingSelfAndOutsideDropsAreNoOps() {
        val targets = LayoutVisibleTargetSnapshot(
            revision = 1,
            dockBounds = null,
            quickSlotBounds = emptyMap(),
            lockedMoreBounds = null,
            categoryHeadingBounds = mapOf(
                RecordSection.Feeding to LayoutCategoryHeadingTargetBounds(
                    toIndex = 0,
                    bounds = Rect(0f, 100f, 400f, 160f),
                ),
            ),
        )

        assertNull(
            LayoutDragSession(50L, LayoutDragSource.CategoryHeading(RecordSection.Feeding))
                .finish(50L, Offset(200f, 130f), targets),
        )
        assertNull(
            LayoutDragSession(51L, LayoutDragSource.CategoryHeading(RecordSection.Feeding))
                .finish(51L, Offset(800f, 800f), targets),
        )
    }

    @Test
    fun categoryHeadingsAndCatalogItemsRemainSeparateDragDomains() {
        val targets = LayoutVisibleTargetSnapshot(
            revision = 1,
            dockBounds = null,
            quickSlotBounds = emptyMap(),
            lockedMoreBounds = null,
            catalogItemBounds = mapOf(
                "nursing" to LayoutCatalogTargetBounds(
                    section = RecordSection.Feeding,
                    toIndex = 0,
                    bounds = Rect(0f, 200f, 200f, 300f),
                ),
            ),
            categoryHeadingBounds = mapOf(
                RecordSection.Routine to LayoutCategoryHeadingTargetBounds(
                    toIndex = 2,
                    bounds = Rect(0f, 100f, 400f, 160f),
                ),
            ),
        )

        assertNull(
            LayoutDragSession(52L, LayoutDragSource.CategoryHeading(RecordSection.Routine))
                .finish(52L, Offset(100f, 250f), targets),
        )
        assertNull(
            LayoutDragSession(
                53L,
                LayoutDragSource.CatalogItem("nursing", RecordSection.Feeding),
            ).finish(53L, Offset(200f, 130f), targets),
        )
    }

    @Test
    fun removedCategoryHeadingCannotRemainAVisibleTarget() {
        val registry = LayoutVisibleTargetRegistry()
        val node = LayoutTargetNode.CategoryHeading(RecordSection.Health, 3)
        registry.register(node, Rect(0f, 100f, 400f, 160f))
        assertTrue(registry.snapshot().categoryHeadingBounds.containsKey(RecordSection.Health))

        registry.unregister(node)

        assertFalse(registry.snapshot().categoryHeadingBounds.containsKey(RecordSection.Health))
    }
}
