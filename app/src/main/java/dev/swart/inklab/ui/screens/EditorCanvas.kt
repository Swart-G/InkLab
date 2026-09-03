package dev.swart.inklab.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.unit.dp
import dev.swart.inklab.core.model.InkPoint
import dev.swart.inklab.core.model.PaperPattern
import dev.swart.inklab.core.storage.FingerAction
import dev.swart.inklab.ui.CanvasInputSource
import dev.swart.inklab.ui.EditorTool
import dev.swart.inklab.ui.EditorViewModel
import dev.swart.inklab.ui.theme.InkColors
import kotlin.math.floor

@Composable
fun EditorCanvas(vm: EditorViewModel, modifier: Modifier = Modifier) {
    val boardSettings = vm.currentBoard?.settings
    val paperColor = boardSettings?.paperColor?.let(::Color) ?: InkColors.PaperRaised
    Canvas(
        modifier = modifier
            .background(paperColor)
            .pointerInput(vm.tool, vm.inputPreferences, vm.viewportScale) {
                awaitEachGesture {
                    val firstEvent = awaitPointerEvent(PointerEventPass.Initial)
                    val down = firstEvent.changes.firstOrNull { it.pressed } ?: return@awaitEachGesture
                    val source = when (down.type) {
                        PointerType.Stylus -> CanvasInputSource.STYLUS
                        PointerType.Eraser -> CanvasInputSource.STYLUS_ERASER
                        PointerType.Touch -> CanvasInputSource.TOUCH
                        else -> CanvasInputSource.MOUSE
                    }

                    if (source == CanvasInputSource.TOUCH && vm.inputPreferences.palmRejection && vm.stylusInContact) {
                        while (firstEvent.changes.any { it.pressed }) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            event.changes.forEach { it.consume() }
                            if (event.changes.none { it.pressed }) break
                        }
                        return@awaitEachGesture
                    }

                    if (source == CanvasInputSource.TOUCH && vm.inputPreferences.fingerAction == FingerAction.PAN) {
                        var event = firstEvent
                        while (event.changes.any { it.pressed }) {
                            val centroid = event.calculateCentroid(useCurrent = true)
                            val zoom = event.calculateZoom()
                            if (zoom.isFinite() && zoom != 1f) vm.zoomBy(zoom, centroid)
                            val pan = event.calculatePan()
                            if (pan != Offset.Zero) vm.panBy(pan)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                            event = awaitPointerEvent(PointerEventPass.Initial)
                        }
                        return@awaitEachGesture
                    }

                    val sideButton = firstEvent.buttons.isSecondaryPressed || firstEvent.buttons.isTertiaryPressed
                    val activeTool = vm.effectiveTool(source, sideButton)
                    if (activeTool == null) {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.changes.none { it.pressed }) break
                        }
                        return@awaitEachGesture
                    }

                    val isStylus = source == CanvasInputSource.STYLUS || source == CanvasInputSource.STYLUS_ERASER
                    if (isStylus) vm.setStylusContact(true)
                    val start = vm.screenToCanvas(down.position)
                    when (activeTool) {
                        EditorTool.PEN -> vm.startStroke(down.toInkPoint(start, vm.inputPreferences.pressureEnabled))
                        EditorTool.ERASER -> {
                            vm.beginErase()
                            vm.eraserCursor = start
                            vm.eraseAt(start)
                        }
                        EditorTool.LASSO -> vm.startLasso(start)
                    }
                    down.consume()

                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: event.changes.firstOrNull()
                        if (change == null || !change.pressed) break
                        val point = vm.screenToCanvas(change.position)
                        when (activeTool) {
                            EditorTool.PEN -> vm.addPoint(change.toInkPoint(point, vm.inputPreferences.pressureEnabled))
                            EditorTool.ERASER -> {
                                vm.eraserCursor = point
                                vm.eraseAt(point)
                            }
                            EditorTool.LASSO -> vm.addLasso(point)
                        }
                        change.consume()
                    }

                    when (activeTool) {
                        EditorTool.PEN -> vm.finishStroke()
                        EditorTool.ERASER -> vm.finishErase()
                        EditorTool.LASSO -> vm.finishLasso()
                    }
                    if (isStylus) vm.setStylusContact(false)
                }
            }
    ) {
        val scale = vm.viewportScale
        val offset = vm.viewportOffset
        val worldLeft = -offset.x / scale
        val worldTop = -offset.y / scale
        val worldRight = worldLeft + size.width / scale
        val worldBottom = worldTop + size.height / scale
        val spacing = (boardSettings?.spacing ?: 36f).coerceAtLeast(12f)

        withTransform({
            translate(offset.x, offset.y)
            scale(scale, scale, pivot = Offset.Zero)
        }) {
            when (boardSettings?.pattern ?: PaperPattern.RULED) {
                PaperPattern.RULED -> {
                    var y = floor(worldTop / spacing) * spacing
                    while (y < worldBottom) {
                        drawLine(InkColors.Line.copy(alpha = 0.58f), Offset(worldLeft, y), Offset(worldRight, y), 1f / scale)
                        y += spacing
                    }
                }
                PaperPattern.GRID -> {
                    var y = floor(worldTop / spacing) * spacing
                    while (y < worldBottom) {
                        drawLine(InkColors.Line.copy(alpha = 0.48f), Offset(worldLeft, y), Offset(worldRight, y), 1f / scale)
                        y += spacing
                    }
                    var x = floor(worldLeft / spacing) * spacing
                    while (x < worldRight) {
                        drawLine(InkColors.Line.copy(alpha = 0.48f), Offset(x, worldTop), Offset(x, worldBottom), 1f / scale)
                        x += spacing
                    }
                }
                PaperPattern.DOTS -> {
                    var y = floor(worldTop / spacing) * spacing
                    while (y < worldBottom) {
                        var x = floor(worldLeft / spacing) * spacing
                        while (x < worldRight) {
                            drawCircle(InkColors.Line, 1.2f / scale, Offset(x, y))
                            x += spacing
                        }
                        y += spacing
                    }
                }
                PaperPattern.BLANK -> Unit
            }
            if (boardSettings?.showMargin == true) {
                drawLine(InkColors.Rose.copy(alpha = 0.8f), Offset(74f, worldTop), Offset(74f, worldBottom), 1.4f / scale)
            }

            fun drawStroke(points: List<InkPoint>, width: Float, color: Color, selected: Boolean = false) {
                if (points.size < 2) return
                points.zipWithNext().forEach { (a, b) ->
                    val pressure = ((a.pressure + b.pressure) / 2f).coerceIn(0.15f, 1f)
                    val segmentWidth = width * (0.55f + pressure * 0.75f)
                    if (selected) {
                        drawLine(InkColors.Accent.copy(alpha = 0.16f), a.offset(), b.offset(), segmentWidth + 10f / scale, StrokeCap.Round)
                    }
                    drawLine(color, a.offset(), b.offset(), segmentWidth, StrokeCap.Round)
                }
            }

            vm.strokes.forEach { drawStroke(it.points, it.width, it.color, it.id in vm.selectedIds) }
            drawStroke(vm.currentPoints, vm.penWidth, vm.penColor)

            if (vm.lassoPoints.size > 1) {
                val path = Path().apply {
                    moveTo(vm.lassoPoints.first().x, vm.lassoPoints.first().y)
                    vm.lassoPoints.drop(1).forEach { lineTo(it.x, it.y) }
                }
                drawPath(path, InkColors.Accent.copy(alpha = 0.08f), style = Fill)
                drawPath(path, InkColors.Accent, style = Stroke(2.2f / scale, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f / scale, 6f / scale))))
            }

            vm.currentSelectionBounds?.let { bounds ->
                drawRoundRect(
                    color = InkColors.Accent,
                    topLeft = bounds.topLeft - Offset(7f / scale, 7f / scale),
                    size = androidx.compose.ui.geometry.Size(bounds.width + 14f / scale, bounds.height + 14f / scale),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(10f / scale),
                    style = Stroke(1.6f / scale, pathEffect = PathEffect.dashPathEffect(floatArrayOf(7f / scale, 5f / scale)))
                )
            }

            vm.eraserCursor?.let { cursor ->
                drawCircle(InkColors.Accent.copy(alpha = 0.08f), vm.inputPreferences.eraserRadius / scale, cursor)
                drawCircle(InkColors.Accent.copy(alpha = 0.65f), vm.inputPreferences.eraserRadius / scale, cursor, style = Stroke(1.4f / scale))
            }
        }
    }
}

private fun androidx.compose.ui.input.pointer.PointerInputChange.toInkPoint(
    canvasPoint: Offset,
    pressureEnabled: Boolean
) = InkPoint(
    x = canvasPoint.x,
    y = canvasPoint.y,
    timestamp = System.currentTimeMillis(),
    pressure = if (pressureEnabled) pressure.coerceIn(0.15f, 1f) else 0.6f
)
