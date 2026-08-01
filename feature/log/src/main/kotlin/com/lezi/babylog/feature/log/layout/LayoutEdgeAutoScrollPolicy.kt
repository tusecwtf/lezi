package com.lezi.babylog.feature.log.layout
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.lezi.babylog.feature.log.*
import com.lezi.babylog.feature.log.timeline.*
import com.lezi.babylog.feature.log.dock.*
import com.lezi.babylog.feature.log.composer.*
import com.lezi.babylog.feature.log.photo.*

internal object LayoutEdgeAutoScrollPolicy {
    fun stepPx(
        source: LayoutDragSource,
        pointerWindow: Offset,
        catalogViewport: Rect,
        hitRegion: LayoutHitRegion,
        canScrollBackward: Boolean,
        canScrollForward: Boolean,
        edgeBandPx: Float,
        maxStepPx: Float,
    ): Float {
        require(edgeBandPx > 0f)
        require(maxStepPx >= 0f)
        if (source !is LayoutDragSource.CatalogItem &&
            source !is LayoutDragSource.CategoryHeading
        ) {
            return 0f
        }
        when (hitRegion) {
            LayoutHitRegion.LocalDeleted,
            LayoutHitRegion.LockedMore,
            is LayoutHitRegion.QuickSlot,
            LayoutHitRegion.DockGap,
            -> return 0f
            is LayoutHitRegion.CatalogItem,
            is LayoutHitRegion.CategoryHeading,
            LayoutHitRegion.OutsideDock,
            -> Unit
        }
        val y = pointerWindow.y
        if (y !in catalogViewport.top..catalogViewport.bottom) return 0f
        val effectiveBandPx = minOf(edgeBandPx, catalogViewport.height / 2f)
        if (effectiveBandPx <= 0f) return 0f
        val topBandEnd = catalogViewport.top + effectiveBandPx
        val bottomBandStart = catalogViewport.bottom - effectiveBandPx
        return when {
            y < topBandEnd && canScrollBackward -> {
                -maxStepPx * ((topBandEnd - y) / effectiveBandPx).coerceIn(0f, 1f)
            }
            y > bottomBandStart && canScrollForward -> {
                maxStepPx * ((y - bottomBandStart) / effectiveBandPx).coerceIn(0f, 1f)
            }
            else -> 0f
        }
    }
}
