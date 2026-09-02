package dev.swart.inklab.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import dev.swart.inklab.core.model.InkPoint
import dev.swart.inklab.ui.EditorTool
import dev.swart.inklab.ui.EditorViewModel
import dev.swart.inklab.ui.theme.InkColors

@Composable
fun EditorCanvas(vm: EditorViewModel, modifier: Modifier = Modifier) {
    Canvas(
        modifier = modifier
            .background(InkColors.PaperRaised)
            .pointerInput(vm.tool, vm.penWidth) {
                awaitEachGesture {
                    val down = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.pressed } ?: return@awaitEachGesture
                    val start = down.position
                    val now = System.currentTimeMillis()
                    when (vm.tool) {
                        EditorTool.PEN -> vm.startStroke(InkPoint(start.x, start.y, now, 1f))
                        EditorTool.ERASER -> vm.eraseAt(start)
                        EditorTool.LASSO -> vm.startLasso(start)
                    }
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull() ?: break
                        if (!change.pressed) break
                        val p = change.position
                        when (vm.tool) {
                            EditorTool.PEN -> vm.addPoint(InkPoint(p.x, p.y, System.currentTimeMillis(), 1f))
                            EditorTool.ERASER -> vm.eraseAt(p)
                            EditorTool.LASSO -> vm.addLasso(p)
                        }
                        change.consume()
                    }
                    when (vm.tool) {
                        EditorTool.PEN -> vm.finishStroke()
                        EditorTool.LASSO -> vm.finishLasso()
                        else -> Unit
                    }
                }
            }
    ) {
        val spacing = 36.dp.toPx()
        var y = spacing
        while (y < size.height) {
            drawLine(InkColors.Line.copy(alpha = 0.55f), Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            y += spacing
        }

        fun drawStroke(points: List<InkPoint>, width: Float, selected: Boolean = false) {
            if (points.size < 2) return
            val path = Path().apply {
                moveTo(points.first().x, points.first().y)
                for (i in 1 until points.size) {
                    val prev = points[i - 1]
                    val cur = points[i]
                    val midX = (prev.x + cur.x) / 2f
                    val midY = (prev.y + cur.y) / 2f
                    quadraticBezierTo(prev.x, prev.y, midX, midY)
                }
                lineTo(points.last().x, points.last().y)
            }
            if (selected) {
                drawPath(path, InkColors.Accent.copy(alpha = 0.18f), style = Stroke(width + 10f, cap = StrokeCap.Round))
            }
            drawPath(path, InkColors.Ink, style = Stroke(width, cap = StrokeCap.Round))
        }

        vm.strokes.forEach { drawStroke(it.points, it.width, it.id in vm.selectedIds) }
        drawStroke(vm.currentPoints, vm.penWidth)

        if (vm.lassoPoints.size > 1) {
            val path = Path().apply {
                moveTo(vm.lassoPoints.first().x, vm.lassoPoints.first().y)
                vm.lassoPoints.drop(1).forEach { lineTo(it.x, it.y) }
            }
            drawPath(path, InkColors.Accent, style = Stroke(2.5f, cap = StrokeCap.Round))
        }
    }
}
