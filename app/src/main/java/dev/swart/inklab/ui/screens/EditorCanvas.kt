package dev.swart.inklab.ui.screens

import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.input.pointer.motionEventSpy
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.DisposableEffect
import dev.swart.inklab.ui.theme.*
import dev.swart.inklab.core.input.CanvasInputController
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import dev.swart.inklab.core.ink.autoRecognizeShape
import dev.swart.inklab.core.model.ConvertedInkKind
import dev.swart.inklab.core.model.DocumentFormat
import dev.swart.inklab.core.model.InkPoint
import dev.swart.inklab.core.model.InkStroke
import dev.swart.inklab.core.model.PaperPattern
import dev.swart.inklab.ui.CanvasInputSource
import dev.swart.inklab.ui.EditorTool
import dev.swart.inklab.ui.EditorViewModel
import dev.swart.inklab.ui.theme.InkColors
import kotlinx.coroutines.delay
import ru.noties.jlatexmath.JLatexMathDrawable
import kotlin.math.floor
import kotlin.math.abs
import kotlin.math.min

private const val SHAPE_HOLD_MS = 520L

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun EditorCanvas(vm: EditorViewModel, modifier: Modifier = Modifier) {
    val palette = LocalInkPalette.current
    val context = LocalContext.current
    val controller = remember(vm, vm.currentBoardId) { CanvasInputController(vm, android.view.ViewConfiguration.get(context).scaledTouchSlop.toFloat()) }
    DisposableEffect(controller) { onDispose { controller.cancel() } }
    val night = palette.dark && vm.inputPreferences.nightPaper
    val boardSettings = vm.currentBoard?.settings
    val notebook = vm.currentBoard?.format == DocumentFormat.NOTEBOOK
    val paperColor = paperDisplayColor(boardSettings?.paperColor?.let(::Color) ?: palette.PaperRaised, night)
    val ruleColor = if (paperColor.red < 0.5f) Color(0xFF565860) else Color(0xFFC9C5BC)
    fun displayInk(color: Color) = inkDisplayColor(color, paperColor, night)
    val mathCache = remember { object : LinkedHashMap<String, JLatexMathDrawable?>(32, 0.75f, true) { override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, JLatexMathDrawable?>?) = size > 64 } }
    val textPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("cursive", Typeface.NORMAL)
            isSubpixelText = true
        }
    }
    var shapePreview by remember { mutableStateOf<InkStroke?>(null) }
    val lastPointTimestamp = vm.currentPoints.lastOrNull()?.timestamp

    LaunchedEffect(lastPointTimestamp, vm.stylusInContact, vm.tool, vm.inputPreferences.autoShapes) {
        shapePreview = null
        if (
            vm.stylusInContact &&
            vm.tool == EditorTool.PEN &&
            vm.inputPreferences.autoShapes &&
            lastPointTimestamp != null &&
            vm.currentPoints.size > 1
        ) {
            delay(SHAPE_HOLD_MS)
            if (
                vm.stylusInContact &&
                vm.currentPoints.lastOrNull()?.timestamp == lastPointTimestamp &&
                vm.currentPoints.size > 1
            ) {
                val raw = InkStroke(points = vm.currentPoints.toList(), width = vm.penWidth, color = vm.penColor)
                val corrected = autoRecognizeShape(raw)
                if (corrected.points != raw.points) shapePreview = corrected
            }
        }
    }

    Canvas(
        modifier = modifier
            .clipToBounds()
            .background(if (notebook) palette.Paper else paperColor)
            .onSizeChanged { vm.resizeViewport(it.width.toFloat(), it.height.toFloat()) }
            .motionEventSpy { event ->
                if (event.actionMasked in android.view.MotionEvent.ACTION_HOVER_MOVE..android.view.MotionEvent.ACTION_HOVER_EXIT) controller.hover(event)
            }
            .pointerInteropFilter { controller.event(it) }
    ) {
        val scale = vm.viewportScale
        val offset = vm.viewportOffset
        val pages = vm.currentBoard?.pages ?: emptyList()
        pages.forEachIndexed { index, page ->
            val active = index == vm.currentPageIndex
            val pageX = if (notebook) vm.pageLeft(index) else 0f
            val pageY = if (notebook) vm.pageTop(index) else 0f
            if (notebook && ((pageY + page.height)*scale + offset.y < 0 || pageY*scale + offset.y > size.height)) return@forEachIndexed
            if (!notebook && !active) return@forEachIndexed
            val worldLeft = if (notebook) page.originX else -offset.x / scale
            val worldTop = if (notebook) page.originY else -offset.y / scale
            val worldRight = if (notebook) worldLeft + page.width else worldLeft + size.width / scale
            val worldBottom = if (notebook) worldTop + page.height else worldTop + size.height / scale
            val spacing = (boardSettings?.spacing ?: 36f).coerceAtLeast(12f)
            withTransform({
                translate(offset.x, offset.y)
                scale(scale, scale, pivot = Offset.Zero)
                if (notebook) translate(pageX-page.originX,pageY-page.originY)
            }) {
            if (notebook) {
                drawRect(palette.Ink.copy(alpha=0.06f), Offset(worldLeft-2f/scale, worldTop+3f/scale), androidx.compose.ui.geometry.Size(page.width+4f/scale,page.height+2f/scale))
            }
            clipRect(worldLeft, worldTop, worldRight, worldBottom) {
            drawRect(paperColor, Offset(worldLeft,worldTop), androidx.compose.ui.geometry.Size(worldRight-worldLeft,worldBottom-worldTop))
            when (boardSettings?.pattern ?: PaperPattern.RULED) {
                PaperPattern.RULED -> {
                    var y = floor(worldTop / spacing) * spacing
                    while (y < worldBottom) {
                        drawLine(ruleColor.copy(alpha = 0.58f), Offset(worldLeft, y), Offset(worldRight, y), 1f / scale)
                        y += spacing
                    }
                }
                PaperPattern.GRID -> {
                    var y = floor(worldTop / spacing) * spacing
                    while (y < worldBottom) {
                        drawLine(ruleColor.copy(alpha = 0.48f), Offset(worldLeft, y), Offset(worldRight, y), 1f / scale)
                        y += spacing
                    }
                    var x = floor(worldLeft / spacing) * spacing
                    while (x < worldRight) {
                        drawLine(ruleColor.copy(alpha = 0.48f), Offset(x, worldTop), Offset(x, worldBottom), 1f / scale)
                        x += spacing
                    }
                }
                PaperPattern.DOTS -> {
                    var y = floor(worldTop / spacing) * spacing
                    while (y < worldBottom) {
                        var x = floor(worldLeft / spacing) * spacing
                        while (x < worldRight) {
                            drawCircle(ruleColor, 1.2f / scale, Offset(x, y))
                            x += spacing
                        }
                        y += spacing
                    }
                }
                PaperPattern.BLANK -> Unit
            }
            if (boardSettings?.showMargin == true) {
                drawLine(palette.Rose.copy(alpha = 0.8f), Offset(74f, worldTop), Offset(74f, worldBottom), 1.4f / scale)
            }

            fun drawStroke(points: List<InkPoint>, width: Float, color: Color, selected: Boolean = false) {
                if (points.size == 1) drawCircle(displayInk(color), width / 2f, points.first().offset())
                if (points.size < 2) return
                points.zipWithNext().forEach { (a, b) ->
                    val pressure = ((a.pressure + b.pressure) / 2f).coerceIn(0.15f, 1f)
                    val segmentWidth = width * (0.55f + pressure * 0.75f)
                    if (selected) {
                        drawLine(palette.Accent.copy(alpha = 0.16f), a.offset(), b.offset(), segmentWidth + 10f / scale, StrokeCap.Round)
                    }
                    drawLine(displayInk(color), a.offset(), b.offset(), segmentWidth, StrokeCap.Round)
                }
            }

            (if (active) vm.strokes else page.strokes).forEach { drawStroke(it.points, it.width, it.color, active && it.id in vm.selectedIds) }
            if (active) { shapePreview?.let { preview ->
                drawStroke(preview.points, preview.width, preview.color)
            } ?: drawStroke(vm.currentPoints, vm.penWidth, vm.penColor) }

            (if (active) vm.convertedObjects else page.convertedObjects).forEach { item ->
                if (item.kind == ConvertedInkKind.TEXT) {
                    drawIntoCanvas { canvas ->
                        textPaint.color = displayInk(item.color).toArgb()
                        textPaint.textSize = item.textSize
                        textPaint.typeface = Typeface.create("cursive", Typeface.NORMAL)
                        val native = canvas.nativeCanvas
                        val paint = android.text.TextPaint(textPaint)
                        val layout = android.text.StaticLayout.Builder.obtain(item.content, 0, item.content.length, paint, item.width.toInt().coerceAtLeast(1))
                            .setIncludePad(false).setLineSpacing(0f, 1.16f).build()
                        val save = native.save()
                        native.translate(item.x, item.y)
                        layout.draw(native)
                        native.restoreToCount(save)
                    }
                } else {
                    val cacheKey = "${item.content}|${item.textSize.toInt()}|${displayInk(item.color).toArgb()}"
                    val drawable = mathCache.getOrPut(cacheKey) {
                        runCatching {
                            JLatexMathDrawable.builder(item.content)
                                .textSize(item.textSize)
                                .color(displayInk(item.color).toArgb())
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
                            textPaint.color = displayInk(item.color).toArgb()
                            textPaint.textSize = item.textSize * 0.62f
                            textPaint.typeface = Typeface.MONOSPACE
                            canvas.nativeCanvas.drawText(item.content, item.x, item.y + item.textSize, textPaint)
                        }
                    }
                }

                if (active && item.id == vm.selectedConvertedId) {
                    drawRoundRect(
                        color = palette.Accent.copy(alpha = 0.07f),
                        topLeft = item.bounds().topLeft - Offset(8f / scale, 8f / scale),
                        size = androidx.compose.ui.geometry.Size(item.width + 16f / scale, item.height + 16f / scale),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(11f / scale),
                        style = Fill
                    )
                    drawRoundRect(
                        color = palette.Accent,
                        topLeft = item.bounds().topLeft - Offset(8f / scale, 8f / scale),
                        size = androidx.compose.ui.geometry.Size(item.width + 16f / scale, item.height + 16f / scale),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(11f / scale),
                        style = Stroke(1.7f / scale, pathEffect = PathEffect.dashPathEffect(floatArrayOf(7f / scale, 5f / scale)))
                    )
                }
            }

            if (active && vm.lassoPoints.size > 1) {
                val path = Path().apply {
                    moveTo(vm.lassoPoints.first().x, vm.lassoPoints.first().y)
                    vm.lassoPoints.drop(1).forEach { lineTo(it.x, it.y) }
                }
                drawPath(path, palette.Accent.copy(alpha = 0.08f), style = Fill)
                drawPath(path, palette.Accent, style = Stroke(2.2f / scale, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f / scale, 6f / scale))))
            }

            if (active) vm.currentSelectionBounds?.let { bounds ->
                drawRoundRect(
                    color = palette.Accent,
                    topLeft = bounds.topLeft - Offset(7f / scale, 7f / scale),
                    size = androidx.compose.ui.geometry.Size(bounds.width + 14f / scale, bounds.height + 14f / scale),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(10f / scale),
                    style = Stroke(1.6f / scale, pathEffect = PathEffect.dashPathEffect(floatArrayOf(7f / scale, 5f / scale)))
                )
            }

            if (active) vm.eraserCursor?.let { cursor ->
                drawCircle(palette.Accent.copy(alpha = 0.08f), vm.inputPreferences.eraserRadius / scale, cursor)
                drawCircle(palette.Accent.copy(alpha = 0.65f), vm.inputPreferences.eraserRadius / scale, cursor, style = Stroke(1.4f / scale))
            }
        }
    }
}
}
}
