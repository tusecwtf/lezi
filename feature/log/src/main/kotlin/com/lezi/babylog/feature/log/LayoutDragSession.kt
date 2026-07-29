package com.lezi.babylog.feature.log

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.lezi.babylog.core.ui.RecordSection
import kotlin.math.abs

internal sealed interface LayoutDragSource {
    data class CatalogItem(
        val catalogKey: String,
        val section: RecordSection,
    ) : LayoutDragSource

    data class BoundSlot(
        val slotIndex: Int,
        val catalogKey: String,
    ) : LayoutDragSource

    data class LocalDeleted(val catalogKey: String) : LayoutDragSource
}

internal sealed interface LayoutHitRegion {
    data object LocalDeleted : LayoutHitRegion
    data object LockedMore : LayoutHitRegion
    data class QuickSlot(val slotIndex: Int) : LayoutHitRegion
    data class CatalogItem(val catalogKey: String) : LayoutHitRegion
    data object DockGap : LayoutHitRegion
    data object OutsideDock : LayoutHitRegion
}

internal sealed interface LayoutDropTarget {
    data object LocalDeleted : LayoutDropTarget
    data class QuickSlot(val slotIndex: Int) : LayoutDropTarget
    data class CatalogItem(
        val catalogKey: String,
        val toIndex: Int,
    ) : LayoutDropTarget
    data object OutsideDock : LayoutDropTarget
    data object RestoreCatalog : LayoutDropTarget
}

internal data class LayoutCatalogTargetBounds(
    val section: RecordSection,
    val toIndex: Int,
    val bounds: Rect,
)

internal data class LayoutVisibleTargetSnapshot(
    val revision: Long,
    val dockBounds: Rect?,
    val quickSlotBounds: Map<Int, Rect>,
    val lockedMoreBounds: Rect?,
    val localDeletedBounds: Rect? = null,
    val catalogItemBounds: Map<String, LayoutCatalogTargetBounds> = emptyMap(),
)

internal sealed interface LayoutTargetNode {
    data object Dock : LayoutTargetNode
    data object LockedMore : LayoutTargetNode
    data object LocalDeleted : LayoutTargetNode
    data class QuickSlot(val slotIndex: Int) : LayoutTargetNode
    data class CatalogItem(
        val catalogKey: String,
        val section: RecordSection,
        val toIndex: Int,
    ) : LayoutTargetNode
}

/** Current composition's target bounds. Removed and moved nodes cannot leave hit-test ghosts. */
internal class LayoutVisibleTargetRegistry {
    private data class Registration(
        val owner: Any,
        val bounds: Rect,
    )

    private val registrations = linkedMapOf<LayoutTargetNode, Registration>()
    private var revision: Long = 0L

    fun register(node: LayoutTargetNode, bounds: Rect, owner: Any = node): Boolean {
        if (bounds.width <= 0f || bounds.height <= 0f) return unregister(node, owner)
        val current = registrations[node]
        if (current?.owner === owner && current.bounds == bounds) return false
        registrations[node] = Registration(owner = owner, bounds = bounds)
        revision += 1L
        return true
    }

    fun unregister(node: LayoutTargetNode, owner: Any? = null): Boolean {
        val current = registrations[node] ?: return false
        if (owner != null && current.owner !== owner) return false
        registrations.remove(node)
        revision += 1L
        return true
    }

    fun snapshot(): LayoutVisibleTargetSnapshot = LayoutVisibleTargetSnapshot(
        revision = revision,
        dockBounds = registrations[LayoutTargetNode.Dock]?.bounds,
        quickSlotBounds = registrations.entries.mapNotNull { (node, registration) ->
            (node as? LayoutTargetNode.QuickSlot)?.let { it.slotIndex to registration.bounds }
        }.toMap(),
        lockedMoreBounds = registrations[LayoutTargetNode.LockedMore]?.bounds,
        localDeletedBounds = registrations[LayoutTargetNode.LocalDeleted]?.bounds,
        catalogItemBounds = registrations.entries.mapNotNull { (node, registration) ->
            (node as? LayoutTargetNode.CatalogItem)?.let {
                it.catalogKey to LayoutCatalogTargetBounds(
                    section = it.section,
                    toIndex = it.toIndex,
                    bounds = registration.bounds,
                )
            }
        }.toMap(),
    )
}

internal data class LayoutDragResolution(
    val hitRegion: LayoutHitRegion,
    val currentTarget: LayoutDropTarget?,
    val accepted: Boolean = true,
)

internal enum class LayoutDragCancelReason {
    Back,
    Dispose,
    ConfigurationChange,
}

internal class LayoutDragLifecyclePolicy(
    initialCancelSignal: Long,
    initialConfigurationKey: Any,
) {
    private var cancelSignal = initialCancelSignal
    private var configurationKey = initialConfigurationKey

    fun cancelReasonForSignal(current: Long): LayoutDragCancelReason? {
        if (current == cancelSignal) return null
        cancelSignal = current
        return LayoutDragCancelReason.Back
    }

    fun cancelReasonForConfiguration(current: Any): LayoutDragCancelReason? {
        if (current == configurationKey) return null
        configurationKey = current
        return LayoutDragCancelReason.ConfigurationChange
    }

    fun disposeReason(): LayoutDragCancelReason = LayoutDragCancelReason.Dispose
}

internal class LayoutDragSession(
    private val token: Long,
    val source: LayoutDragSource,
) {
    private var active: Boolean = true
    private var latestBoundsRevision: Long = Long.MIN_VALUE
    var cancellationReason: LayoutDragCancelReason? = null
        private set

    fun update(
        token: Long,
        pointerWindow: Offset,
        targets: LayoutVisibleTargetSnapshot,
    ): LayoutDragResolution {
        if (!active || token != this.token || targets.revision < latestBoundsRevision) {
            return LayoutDragResolution(
                hitRegion = LayoutHitRegion.OutsideDock,
                currentTarget = null,
                accepted = false,
            )
        }
        latestBoundsRevision = targets.revision
        val slotHit = targets.quickSlotBounds.entries
            .filter { it.value.contains(pointerWindow) }
            .minByOrNull { abs(it.value.center.x - pointerWindow.x) }
            ?.key
        val catalogHit = targets.catalogItemBounds.entries
            .filter { it.value.bounds.contains(pointerWindow) }
            .minByOrNull {
                abs(it.value.bounds.center.x - pointerWindow.x) +
                    abs(it.value.bounds.center.y - pointerWindow.y)
            }
        val hit = when {
            targets.localDeletedBounds?.contains(pointerWindow) == true -> {
                LayoutHitRegion.LocalDeleted
            }
            targets.lockedMoreBounds?.contains(pointerWindow) == true -> {
                LayoutHitRegion.LockedMore
            }
            slotHit != null -> LayoutHitRegion.QuickSlot(slotHit)
            catalogHit != null -> LayoutHitRegion.CatalogItem(catalogHit.key)
            targets.dockBounds?.contains(pointerWindow) == true -> LayoutHitRegion.DockGap
            else -> LayoutHitRegion.OutsideDock
        }
        val currentTarget = when (val dragSource = source) {
            is LayoutDragSource.LocalDeleted -> {
                if (hit == LayoutHitRegion.LocalDeleted) null else LayoutDropTarget.RestoreCatalog
            }
            is LayoutDragSource.BoundSlot -> when (hit) {
                LayoutHitRegion.LocalDeleted -> LayoutDropTarget.LocalDeleted
                is LayoutHitRegion.QuickSlot -> if (dragSource.slotIndex != hit.slotIndex) {
                    LayoutDropTarget.QuickSlot(hit.slotIndex)
                } else {
                    null
                }
                LayoutHitRegion.LockedMore,
                LayoutHitRegion.DockGap,
                -> null
                is LayoutHitRegion.CatalogItem,
                LayoutHitRegion.OutsideDock,
                -> LayoutDropTarget.OutsideDock
            }
            is LayoutDragSource.CatalogItem -> when (hit) {
                LayoutHitRegion.LocalDeleted -> LayoutDropTarget.LocalDeleted
                is LayoutHitRegion.QuickSlot -> LayoutDropTarget.QuickSlot(hit.slotIndex)
                is LayoutHitRegion.CatalogItem -> {
                    val bounds = targets.catalogItemBounds[hit.catalogKey]
                    if (
                        dragSource.catalogKey != hit.catalogKey &&
                        dragSource.section == bounds?.section
                    ) {
                        LayoutDropTarget.CatalogItem(
                            catalogKey = hit.catalogKey,
                            toIndex = bounds.toIndex,
                        )
                    } else {
                        null
                    }
                }
                LayoutHitRegion.LockedMore,
                LayoutHitRegion.DockGap,
                LayoutHitRegion.OutsideDock,
                -> null
            }
        }
        return LayoutDragResolution(hit, currentTarget)
    }

    fun finish(
        token: Long,
        pointerWindow: Offset,
        targets: LayoutVisibleTargetSnapshot,
    ): LayoutEditIntent? {
        if (!active || token != this.token) return null
        val resolution = update(token, pointerWindow, targets)
        if (!resolution.accepted) return null
        active = false
        return when {
            source is LayoutDragSource.CatalogItem &&
                resolution.currentTarget is LayoutDropTarget.CatalogItem -> {
                LayoutEditIntent.ReorderItemInSection(
                    source.catalogKey,
                    resolution.currentTarget.toIndex,
                )
            }
            source is LayoutDragSource.CatalogItem &&
                resolution.currentTarget == LayoutDropTarget.LocalDeleted -> {
                LayoutEditIntent.MoveToLocalDeleted(source.catalogKey)
            }
            source is LayoutDragSource.BoundSlot &&
                resolution.currentTarget == LayoutDropTarget.LocalDeleted -> {
                LayoutEditIntent.MoveToLocalDeleted(source.catalogKey)
            }
            source is LayoutDragSource.LocalDeleted &&
                resolution.currentTarget == LayoutDropTarget.RestoreCatalog -> {
                LayoutEditIntent.RestoreFromLocalDeleted(source.catalogKey)
            }
            source is LayoutDragSource.CatalogItem &&
                resolution.currentTarget is LayoutDropTarget.QuickSlot -> {
                LayoutEditIntent.AssignToSlot(
                    resolution.currentTarget.slotIndex,
                    source.catalogKey,
                )
            }
            source is LayoutDragSource.BoundSlot &&
                resolution.currentTarget is LayoutDropTarget.QuickSlot -> {
                LayoutEditIntent.SwapSlots(
                    source.slotIndex,
                    resolution.currentTarget.slotIndex,
                )
            }
            source is LayoutDragSource.BoundSlot &&
                resolution.currentTarget == LayoutDropTarget.OutsideDock -> {
                LayoutEditIntent.ClearSlot(source.slotIndex)
            }
            else -> null
        }
    }

    fun cancel(reason: LayoutDragCancelReason): Boolean {
        if (!active) return false
        cancellationReason = reason
        active = false
        return true
    }
}
