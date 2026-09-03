package dev.swart.inklab.ui.screens

import android.graphics.Paint
import android.graphics.Typeface
import android.view.MotionEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.nativeEvent
import dev.swart.inklab.core.model.ConvertedInkKind
import dev.swart.inklab.core.model.InkPoint
import dev.swart.inklab.core.model.PaperPattern
import dev.swart.inklab.ui.CanvasInputSource
import dev.swart.inklab.ui.EditorTool
import dev.swart.inklab.ui.EditorViewModel
import dev.swart.inklab.ui.theme.InkColors
import ru.noties.jlatexmath.JLatexMathDrawable
import kotlin.math.floor
import kotlin.math.abs
import kotlin.math.min

@Composable
fun EditorCanvas(vm: EditorViewModel, modifier: Modifier = Modifier) {
    val boardSettings = vm.currentBoard?.settings
    val paperColor = boardSettings?.paperColor?.let(::Color) ?: InkColors.PaperRaised
    val mathCache = remember { mutableMapOf<String, JLatexMathDrawable?>() }
    val textPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("cursive", Typeface.NORMAL)
            isSubpixelText = true
        }
    }

    Canvas(
        modifier = modifier
            .background(paperColor)
            .pointerInput(vm.tool, vm.inputPreferences) {
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

                    if (source == CanvasInputSource.TOUCH) {
                        val startedAt = System.currentTimeMillis()
                        val startPosition = down.position
                        var event = firstEvent
                        var usedTwoFingers = false
                        var transformed = false
                        var oneFingerDrag = false
                        while (event.changes.any { it.pressed }) {
                            val pressed = event.changes.filter { it.pressed }
                            val centroid = event.calculateCentroid(useCurrent = true)
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            if (pressed.size >= 2) {
                                usedTwoFingers = true
                                if (zoom.isFinite() && abs(zoom - 1f) > 0.002f) {
                                    vm.zoomBy(zoom, centroid)
                                    if (abs(zoom - 1f) > 0.015f) transformed = true
                                }
                                if (pan.getDistance() > 0.7f) {
                                    vm.panBy(pan)
                                    if (pan.getDistance() > viewConfiguration.touchSlop / 2f) transformed = true
                                }
                            } else if (!usedTwoFingers && pressed.isNotEmpty()) {
                                val distance = (pressed.first().position - startPosition).getDistance()
                                if (distance > viewConfiguration.touchSlop) oneFingerDrag = true
                                if (oneFingerDrag && pan != Offset.Zero) vm.panBy(pan)
                            }
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                            event = awaitPointerEvent(PointerEventPass.Initial)
                        }
                        val shortTap = System.currentTimeMillis() - startedAt < 420L
                        when {
                            usedTwoFingers && !transformed && shortTap -> vm.undo()
                            !usedTwoFingers && !oneFingerDrag && shortTap -> vm.selectObjectAt(vm.screenToCanvas(startPosition))
                        }
                        return@awaitEachGesture
                    }

                    val sideButton = firstEvent.hasStylusButton()
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

            vm.convertedObjects.forEach { item ->
                if (item.kind == ConvertedInkKind.TEXT) {
                    drawIntoCanvas { canvas ->
                        textPaint.color = item.color.toArgb()
                        textPaint.textSize = item.textSize
                        textPaint.typeface = Typeface.create("cursive", Typeface.NORMAL)
                        val native = canvas.nativeCanvas
                        val words = item.content.replace('\n', ' ').split(Regex("\\s+")).filter { it.isNotBlank() }
                        val maxWidth = item.width.coerceAtLeast(item.textSize * 2f)
                        val lineHeight = item.textSize * 1.16f
                        var line = ""
                        var y = item.y + item.textSize
                        for (word in words) {
                            val candidate = if (line.isEmpty()) word else "$line $word"
                            if (line.isNotEmpty() && textPaint.measureText(candidate) > maxWidth) {
                                native.drawText(line, item.x, y, textPaint)
                                line = word
                                y += lineHeight
                            } else {
                                line = candidate
                            }
                        }
                        if (line.isNotEmpty()) native.drawText(line, item.x, y, textPaint)
                    }
                } else {
                    val cacheKey = "${item.content}|${item.textSize.toInt()}|${item.color.toArgb()}"
                    val drawable = mathCache.getOrPut(cacheKey) {
                        runCatching {
                            JLatexMathDrawable.builder(item.content)
                                .textSize(item.textSize)
                                .color(item.color.toArgb())
                                .align(JLatexMathDrawable.ALIGN_LEFT)
                                .build()
                        }.getOrNull()
                    }
                    if (drawable != null) {
                        drawIntoCanvas { canvas ->
                            val intrinsicWidth = drawable.intrinsicWidth.coerceAtLeast(1)
                            val intrinsicHeight = drawable.intrinsicHeight.coerceAtLeast(1)
                            val fit = min(item.width / intrinsicWidth, item.height / intrinsicHeight).coerceAtMost(1f)
                            val dx = item.x
                            val dy = item.y + (item.height - intrinsicHeight * fit) / 2f
                            val native = canvas.nativeCanvas
                            val save = native.save()
                            native.translate(dx, dy)
                            native.scale(fit, fit)
                            drawable.setBounds(0, 0, intrinsicWidth, intrinsicHeight)
                            drawable.draw(native)
                            native.restoreToCount(save)
                        }
                    } else {
                        drawIntoCanvas { canvas ->
                            textPaint.color = item.color.toArgb()
                            textPaint.textSize = item.textSize * 0.62f
                            textPaint.typeface = Typeface.MONOSPACE
                            canvas.nativeCanvas.drawText(item.content, item.x, item.y + item.textSize, textPaint)
                        }
                    }
                }

                if (item.id == vm.selectedConvertedId) {
                    drawRoundRect(
                        color = InkColors.Accent.copy(alpha = 0.07f),
                        topLeft = item.bounds().topLeft - Offset(8f / scale, 8f / scale),
                        size = androidx.compose.ui.geometry.Size(item.width + 16f / scale, item.height + 16f / scale),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(11f / scale),
                        style = Fill
                    )
                    drawRoundRect(
                        color = InkColors.Accent,
                        topLeft = item.bounds().topLeft - Offset(8f / scale, 8f / scale),
                        size = androidx.compose.ui.geometry.Size(item.width + 16f / scale, item.height + 16f / scale),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(11f / scale),
                        style = Stroke(1.7f / scale, pathEffect = PathEffect.dashPathEffect(floatArrayOf(7f / scale, 5f / scale)))
                    )
                }
            }

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

private fun PointerEvent.hasStylusButton(): Boolean {
    val nativeButtons = nativeEvent.buttonState
    return buttons.isSecondaryPressed || buttons.isTertiaryPressed ||
        nativeButtons and MotionEvent.BUTTON_STYLUS_PRIMARY != 0 ||
        nativeButtons and MotionEvent.BUTTON_STYLUS_SECONDARY != 0
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
