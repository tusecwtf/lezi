package com.lezi.babylog.feature.log

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned

internal fun Modifier.layoutTargetRegistration(
    node: LayoutTargetNode,
    registry: LayoutVisibleTargetRegistry,
    onRegistryChanged: () -> Unit,
): Modifier = composed {
    val registrationOwner = remember(registry, node) { Any() }
    DisposableEffect(registry, node) {
        onDispose {
            if (registry.unregister(node, registrationOwner)) onRegistryChanged()
        }
    }
    this@layoutTargetRegistration.onGloballyPositioned { coordinates ->
        if (registry.register(node, coordinates.boundsInWindow(), registrationOwner)) {
            onRegistryChanged()
        }
    }
}

internal fun Modifier.draggableLayoutSource(
    dragKey: String,
    onDragStart: (Offset) -> Long,
    onDrag: (Long, Offset) -> Unit,
    onDragEnd: (Long, Offset) -> Unit,
    onDragCancel: (Long) -> Unit,
): Modifier {
    var coordinates: LayoutCoordinates? = null
    return this
        .onGloballyPositioned { coords ->
            coordinates = coords
        }
        .pointerInput(dragKey) {
            var lastWindow = Offset.Zero
            var activeToken: Long? = null
            detectDragGesturesAfterLongPress(
                onDragStart = start@{ local ->
                    val currentCoordinates = coordinates
                        ?.takeIf(LayoutCoordinates::isAttached)
                        ?: return@start
                    lastWindow = currentCoordinates.localToWindow(local)
                    activeToken = onDragStart(lastWindow)
                },
                onDrag = drag@{ change, _ ->
                    change.consume()
                    val currentCoordinates = coordinates
                        ?.takeIf(LayoutCoordinates::isAttached)
                    if (currentCoordinates == null) {
                        activeToken?.let(onDragCancel)
                        activeToken = null
                        return@drag
                    }
                    lastWindow = currentCoordinates.localToWindow(change.position)
                    activeToken?.let { onDrag(it, lastWindow) }
                },
                onDragEnd = {
                    activeToken?.let { token ->
                        if (coordinates?.isAttached == true) {
                            onDragEnd(token, lastWindow)
                        } else {
                            onDragCancel(token)
                        }
                    }
                    activeToken = null
                },
                onDragCancel = {
                    activeToken?.let(onDragCancel)
                    activeToken = null
                },
            )
        }
}
