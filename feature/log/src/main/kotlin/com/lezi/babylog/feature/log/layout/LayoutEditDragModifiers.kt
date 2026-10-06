package com.lezi.babylog.feature.log.layout
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import com.lezi.babylog.feature.log.*
import com.lezi.babylog.feature.log.timeline.*
import com.lezi.babylog.feature.log.dock.*
import com.lezi.babylog.feature.log.composer.*
import com.lezi.babylog.feature.log.photo.*

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
): Modifier = composed {
    val latestOnDragStart by rememberUpdatedState(onDragStart)
    val latestOnDrag by rememberUpdatedState(onDrag)
    val latestOnDragEnd by rememberUpdatedState(onDragEnd)
    val latestOnDragCancel by rememberUpdatedState(onDragCancel)
    val coordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    this@draggableLayoutSource
        .onGloballyPositioned { coords ->
            coordinates[0] = coords
        }
        .pointerInput(dragKey) {
            var lastWindow = Offset.Zero
            var activeToken: Long? = null
            detectDragGesturesAfterLongPress(
                onDragStart = start@{ local ->
                    val currentCoordinates = coordinates[0]
                        ?.takeIf(LayoutCoordinates::isAttached)
                        ?: return@start
                    lastWindow = currentCoordinates.localToWindow(local)
                    activeToken = latestOnDragStart(lastWindow)
                },
                onDrag = drag@{ change, _ ->
                    change.consume()
                    val currentCoordinates = coordinates[0]
                        ?.takeIf(LayoutCoordinates::isAttached)
                    if (currentCoordinates == null) {
                        activeToken?.let(latestOnDragCancel)
                        activeToken = null
                        return@drag
                    }
                    lastWindow = currentCoordinates.localToWindow(change.position)
                    activeToken?.let { latestOnDrag(it, lastWindow) }
                },
                onDragEnd = {
                    activeToken?.let { token ->
                        if (coordinates[0]?.isAttached == true) {
                            latestOnDragEnd(token, lastWindow)
                        } else {
                            latestOnDragCancel(token)
                        }
                    }
                    activeToken = null
                },
                onDragCancel = {
                    activeToken?.let(latestOnDragCancel)
                    activeToken = null
                },
            )
        }
}
