package dev.swart.inklab.core.ink

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import dev.swart.inklab.core.model.InkPoint
import dev.swart.inklab.core.model.InkStroke
import kotlin.math.max
import kotlin.math.min

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

private fun distanceToSegment(point: Offset, start: Offset, end: Offset): Float {
    val segment = end - start
    val lengthSquared = segment.x * segment.x + segment.y * segment.y
    if (lengthSquared <= 0.0001f) return (point - start).getDistance()
    val projection = ((point - start).x * segment.x + (point - start).y * segment.y) / lengthSquared
    val t = max(0f, min(1f, projection))
    return (point - (start + segment * t)).getDistance()
}
