package com.lezi.babylog.feature.log.layout
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.lezi.babylog.core.ui.RecordSection
import org.junit.Assert.assertEquals
import org.junit.Test
import com.lezi.babylog.feature.log.*
import com.lezi.babylog.feature.log.timeline.*
import com.lezi.babylog.feature.log.dock.*
import com.lezi.babylog.feature.log.composer.*
import com.lezi.babylog.feature.log.photo.*

class LayoutEdgeAutoScrollPolicyTest {
    private val viewport = Rect(left = 0f, top = 100f, right = 400f, bottom = 500f)
    private val catalogSource = LayoutDragSource.CatalogItem(
        catalogKey = "nursing",
        section = RecordSection.Feeding,
    )

    @Test
    fun authorizedDragSpeedChangesMonotonicallyAndStopsOutsideEdgeBands() {
        val speeds = listOf(100f, 140f, 300f, 460f, 500f).map { pointerY ->
            LayoutEdgeAutoScrollPolicy.stepPx(
                source = catalogSource,
                pointerWindow = Offset(200f, pointerY),
                catalogViewport = viewport,
                hitRegion = LayoutHitRegion.OutsideDock,
                canScrollBackward = true,
                canScrollForward = true,
                edgeBandPx = 80f,
                maxStepPx = 12f,
            )
        }

        assertEquals(listOf(-12f, -6f, 0f, 6f, 12f), speeds)
    }

    @Test
    fun onlyCatalogItemsAndCategoryHeadingsAuthorizeScrolling() {
        val sources = listOf(
            catalogSource,
            LayoutDragSource.CategoryHeading(RecordSection.Feeding),
            LayoutDragSource.BoundSlot(slotIndex = 0, catalogKey = "nursing"),
            LayoutDragSource.LocalDeleted(catalogKey = "nursing"),
        )

        val speeds = sources.map { source ->
            LayoutEdgeAutoScrollPolicy.stepPx(
                source = source,
                pointerWindow = Offset(200f, 460f),
                catalogViewport = viewport,
                hitRegion = LayoutHitRegion.OutsideDock,
                canScrollBackward = true,
                canScrollForward = true,
                edgeBandPx = 80f,
                maxStepPx = 12f,
            )
        }

        assertEquals(listOf(6f, 6f, 0f, 0f), speeds)
    }

    @Test
    fun fixedTargetsStopScrollingWhileCatalogTargetsRemainEligible() {
        val hitRegions = listOf(
            LayoutHitRegion.LocalDeleted,
            LayoutHitRegion.QuickSlot(slotIndex = 0),
            LayoutHitRegion.LockedMore,
            LayoutHitRegion.DockGap,
            LayoutHitRegion.CatalogItem(catalogKey = "formula"),
            LayoutHitRegion.CategoryHeading(RecordSection.Feeding),
            LayoutHitRegion.OutsideDock,
        )

        val speeds = hitRegions.map { hitRegion ->
            LayoutEdgeAutoScrollPolicy.stepPx(
                source = catalogSource,
                pointerWindow = Offset(200f, 460f),
                catalogViewport = viewport,
                hitRegion = hitRegion,
                canScrollBackward = true,
                canScrollForward = true,
                edgeBandPx = 80f,
                maxStepPx = 12f,
            )
        }

        assertEquals(listOf(0f, 0f, 0f, 0f, 6f, 6f, 6f), speeds)
    }

    @Test
    fun smallViewportKeepsTopAndBottomBandsSeparate() {
        val smallViewport = Rect(left = 0f, top = 100f, right = 400f, bottom = 200f)

        val speeds = listOf(100f, 125f, 150f, 175f, 200f).map { pointerY ->
            LayoutEdgeAutoScrollPolicy.stepPx(
                source = catalogSource,
                pointerWindow = Offset(200f, pointerY),
                catalogViewport = smallViewport,
                hitRegion = LayoutHitRegion.OutsideDock,
                canScrollBackward = true,
                canScrollForward = true,
                edgeBandPx = 80f,
                maxStepPx = 12f,
            )
        }

        assertEquals(listOf(-12f, -6f, 0f, 6f, 12f), speeds)
    }

    @Test
    fun contentBoundaryAndLeavingTheViewportStopScrolling() {
        val topBoundary = LayoutEdgeAutoScrollPolicy.stepPx(
            source = catalogSource,
            pointerWindow = Offset(200f, 100f),
            catalogViewport = viewport,
            hitRegion = LayoutHitRegion.OutsideDock,
            canScrollBackward = false,
            canScrollForward = true,
            edgeBandPx = 80f,
            maxStepPx = 12f,
        )
        val bottomBoundary = LayoutEdgeAutoScrollPolicy.stepPx(
            source = catalogSource,
            pointerWindow = Offset(200f, 500f),
            catalogViewport = viewport,
            hitRegion = LayoutHitRegion.OutsideDock,
            canScrollBackward = true,
            canScrollForward = false,
            edgeBandPx = 80f,
            maxStepPx = 12f,
        )
        val outside = listOf(99f, 501f).map { pointerY ->
            LayoutEdgeAutoScrollPolicy.stepPx(
                source = catalogSource,
                pointerWindow = Offset(200f, pointerY),
                catalogViewport = viewport,
                hitRegion = LayoutHitRegion.OutsideDock,
                canScrollBackward = true,
                canScrollForward = true,
                edgeBandPx = 80f,
                maxStepPx = 12f,
            )
        }

        assertEquals(listOf(0f, 0f, 0f, 0f), listOf(topBoundary, bottomBoundary) + outside)
    }

    @Test
    fun nonPositiveEdgeBandOrStepDoesNotThrow() {
        val zeroBand = LayoutEdgeAutoScrollPolicy.stepPx(
            source = catalogSource,
            pointerWindow = Offset(200f, 100f),
            catalogViewport = viewport,
            hitRegion = LayoutHitRegion.OutsideDock,
            canScrollBackward = true,
            canScrollForward = true,
            edgeBandPx = 0f,
            maxStepPx = 12f,
        )
        val negativeBand = LayoutEdgeAutoScrollPolicy.stepPx(
            source = catalogSource,
            pointerWindow = Offset(200f, 100f),
            catalogViewport = viewport,
            hitRegion = LayoutHitRegion.OutsideDock,
            canScrollBackward = true,
            canScrollForward = true,
            edgeBandPx = -8f,
            maxStepPx = 12f,
        )
        val negativeStep = LayoutEdgeAutoScrollPolicy.stepPx(
            source = catalogSource,
            pointerWindow = Offset(200f, 100f),
            catalogViewport = viewport,
            hitRegion = LayoutHitRegion.OutsideDock,
            canScrollBackward = true,
            canScrollForward = true,
            edgeBandPx = 80f,
            maxStepPx = -12f,
        )

        assertEquals(0f, zeroBand, 0f)
        assertEquals(0f, negativeBand, 0f)
        assertEquals(0f, negativeStep, 0f)
    }
}
