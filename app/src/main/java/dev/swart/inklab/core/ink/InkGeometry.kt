package dev.swart.inklab.core.ink

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import dev.swart.inklab.core.model.InkPoint
import dev.swart.inklab.core.model.InkStroke
import kotlin.math.max
import kotlin.math.min
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

fun pointInPolygon(point: Offset, polygon: List<Offset>): Boolean {
    if (polygon.size < 3) return false
    var inside = false
    var j = polygon.lastIndex
    for (i in polygon.indices) {
        val a = polygon[i]
        val b = polygon[j]
        val crosses = (a.y > point.y) != (b.y > point.y)
        if (crosses) {
            val x = (b.x - a.x) * (point.y - a.y) / (b.y - a.y) + a.x
            if (point.x < x) inside = !inside
        }
        j = i
    }
    return inside
}

fun selectionBounds(strokes: List<InkStroke>, selectedIds: Set<String>): Rect? {
    val points = strokes.asSequence()
        .filter { it.id in selectedIds }
        .flatMap { it.points.asSequence() }
        .toList()
    if (points.isEmpty()) return null
    return Rect(
        left = points.minOf { it.x },
        top = points.minOf { it.y },
        right = points.maxOf { it.x },
        bottom = points.maxOf { it.y }
    )
}

fun strokeIntersectsCircle(stroke: InkStroke, center: Offset, radius: Float): Boolean {
    if (stroke.points.isEmpty()) return false
    if (stroke.points.size == 1) return (stroke.points.first().offset() - center).getDistance() <= radius
    return stroke.points.zipWithNext().any { (a, b) ->
        distanceToSegment(center, a.offset(), b.offset()) <= radius + stroke.width / 2f
    }
}

fun strokeBounds(stroke: InkStroke): Rect? {
    if (stroke.points.isEmpty()) return null
    return Rect(
        stroke.points.minOf { it.x },
        stroke.points.minOf { it.y },
        stroke.points.maxOf { it.x },
        stroke.points.maxOf { it.y }
    )
}

fun strokeHitTest(stroke: InkStroke, point: Offset, tolerance: Float): Boolean =
    strokeIntersectsCircle(stroke, point, tolerance)

/**
 * Turns a confidently drawn one-stroke line, rectangle or ellipse into clean vector ink.
 * Conservative thresholds intentionally leave letters and small marks untouched.
 */
fun autoRecognizeShape(stroke: InkStroke): InkStroke {
    val points = stroke.points
    if (points.size < 5) return stroke
    val bounds = strokeBounds(stroke) ?: return stroke
    val width = bounds.width
    val height = bounds.height
    val diagonal = hypot(width, height)
    if (diagonal < 48f) return stroke

    val pathLength = points.zipWithNext().sumOf { (a, b) ->
        hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble())
    }.toFloat()
    if (pathLength <= 0f) return stroke
    val endpoints = (points.last().offset() - points.first().offset()).getDistance()

    // A straight stroke has almost no detour compared with its end-to-end distance.
    if (endpoints > 42f && endpoints / pathLength > 0.965f) {
        return stroke.copy(points = listOf(points.first(), points.last()))
    }

    val closed = endpoints < diagonal * 0.24f
    if (!closed || width < 38f || height < 38f) return stroke

    val rectanglePerimeter = 2f * (width + height)
    val corners = listOf(
        Offset(bounds.left, bounds.top), Offset(bounds.right, bounds.top),
        Offset(bounds.right, bounds.bottom), Offset(bounds.left, bounds.bottom)
    )
    val visitsEveryCorner = corners.all { corner ->
        points.any { (it.offset() - corner).getDistance() < diagonal * 0.14f }
    }
    val nearEdgeRatio = points.count { point ->
        minOf(
            abs(point.x - bounds.left), abs(point.x - bounds.right),
            abs(point.y - bounds.top), abs(point.y - bounds.bottom)
        ) < diagonal * 0.055f
    }.toFloat() / points.size
    if (visitsEveryCorner && nearEdgeRatio > 0.72f && pathLength / rectanglePerimeter in 0.72f..1.38f) {
        val time = points.first().timestamp
        val pressure = points.map { it.pressure }.average().toFloat()
        return stroke.copy(points = listOf(
            InkPoint(bounds.left, bounds.top, time, pressure),
            InkPoint(bounds.right, bounds.top, time + 1, pressure),
            InkPoint(bounds.right, bounds.bottom, time + 2, pressure),
            InkPoint(bounds.left, bounds.bottom, time + 3, pressure),
            InkPoint(bounds.left, bounds.top, time + 4, pressure)
        ))
    }

    val center = bounds.center
    val rx = width / 2f
    val ry = height / 2f
    val radialError = points.map { point ->
        abs(hypot((point.x - center.x) / rx, (point.y - center.y) / ry) - 1f)
    }.average().toFloat()
    if (radialError < 0.18f) {
        val time = points.first().timestamp
        val pressure = points.map { it.pressure }.average().toFloat()
        val clean = (0..48).map { index ->
            val angle = index / 48f * (2f * PI).toFloat()
            InkPoint(center.x + cos(angle) * rx, center.y + sin(angle) * ry, time + index, pressure)
        }
        return stroke.copy(points = clean)
    }

    return stroke
}

/** Removes only the touched portions and returns the remaining stroke fragments. */
fun splitStrokeByCircle(stroke: InkStroke, center: Offset, radius: Float): List<InkStroke> {
    if (stroke.points.size < 2) return if (strokeIntersectsCircle(stroke, center, radius)) emptyList() else listOf(stroke)
    val keep = BooleanArray(stroke.points.size) { true }
    val hitRadius = radius + stroke.width / 2f
    for (i in 0 until stroke.points.lastIndex) {
        val hit = distanceToSegment(center, stroke.points[i].offset(), stroke.points[i + 1].offset()) <= hitRadius
        if (hit) {
            keep[i] = false
            keep[i + 1] = false
        }
    }
    val result = mutableListOf<InkStroke>()
    var run = mutableListOf<InkPoint>()
    fun flush() {
        if (run.size >= 2) result += stroke.copy(id = java.util.UUID.randomUUID().toString(), points = run.toList())
        run = mutableListOf()
    }
    stroke.points.forEachIndexed { index, point ->
        if (keep[index]) run += point else flush()
    }
    flush()
    return result
}

internal fun distanceToSegment(point: Offset, start: Offset, end: Offset): Float {
    val segment = end - start
    val lengthSquared = segment.x * segment.x + segment.y * segment.y
    if (lengthSquared <= 0.0001f) return (point - start).getDistance()
    val projection = ((point - start).x * segment.x + (point - start).y * segment.y) / lengthSquared
    val t = max(0f, min(1f, projection))
    return (point - (start + segment * t)).getDistance()
}
