package dev.swart.inklab.ui.screens

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import dev.swart.inklab.core.model.InkPoint
import dev.swart.inklab.core.model.InkStroke

internal data class StrokeRenderData(
    val bounds: Rect,
    val centerline: Path,
    val pressurePaths: List<Pair<Float, Path>>,
    val maxWidthFactor: Float,
    val pointCount: Int
)

/**
 * Bounded render cache for immutable strokes. The document can contain millions of samples, so the
 * budget is expressed in source points rather than entry count. Geometry is rebuilt only after an
 * actual stroke mutation and old, off-screen entries are evicted in access order.
 */
internal class StrokeRenderCache(private val maxPoints: Int = 300_000) {
    private data class Entry(val source: InkStroke, val render: StrokeRenderData)
    private val entries = LinkedHashMap<String, Entry>(128, 0.75f, true)
    private var cachedPoints = 0

    init { require(maxPoints > 0) }

    fun get(stroke: InkStroke): StrokeRenderData {
        entries[stroke.id]?.takeIf { it.source == stroke }?.let { return it.render }
        entries.remove(stroke.id)?.also { cachedPoints -= it.render.pointCount }
        val render = build(stroke.points, stroke.width)
        entries[stroke.id] = Entry(stroke, render)
        cachedPoints += render.pointCount
        trim()
        return render
    }

    fun buildTransient(points: List<InkPoint>, width: Float): StrokeRenderData = build(points, width)

    fun clear() {
        entries.clear()
        cachedPoints = 0
    }

    internal fun cachedPointCount(): Int = cachedPoints
    internal fun entryCount(): Int = entries.size

    private fun trim() {
        val iterator = entries.entries.iterator()
        while (cachedPoints > maxPoints && entries.size > 1 && iterator.hasNext()) {
            cachedPoints -= iterator.next().value.render.pointCount
            iterator.remove()
        }
    }

    private fun build(points: List<InkPoint>, width: Float): StrokeRenderData {
        if (points.isEmpty()) return StrokeRenderData(Rect.Zero, Path(), emptyList(), 1f, 0)
        val radius = width * 0.75f
        val bounds = Rect(
            points.minOf { it.x } - radius,
            points.minOf { it.y } - radius,
            points.maxOf { it.x } + radius,
            points.maxOf { it.y } + radius
        )
        val centerline = smoothCenterline(points)
        if (points.size < 2) return StrokeRenderData(bounds, centerline, emptyList(), 1f, points.size)

        val paths = Array(PRESSURE_BUCKETS) { Path() }
        var filtered = points.first().pressure.coerceIn(MIN_PRESSURE, 1f)
        var maxFactor = 1f
        for (index in 0 until points.lastIndex) {
            val a = points[index]
            val b = points[index + 1]
            val measured = ((a.pressure + b.pressure) / 2f).coerceIn(MIN_PRESSURE, 1f)
            filtered += (measured - filtered) * PRESSURE_FILTER
            val factor = widthFactor(filtered)
            maxFactor = maxOf(maxFactor, factor)
            val bucket = (((factor - MIN_WIDTH_FACTOR) / (MAX_WIDTH_FACTOR - MIN_WIDTH_FACTOR)) *
                (PRESSURE_BUCKETS - 1)).toInt().coerceIn(0, PRESSURE_BUCKETS - 1)
            val start = if (index == 0) a.offset() else midpoint(points[index - 1], a)
            val end = if (index == points.lastIndex - 1) b.offset() else midpoint(a, b)
            paths[bucket].moveTo(start.x, start.y)
            paths[bucket].quadraticTo(a.x, a.y, end.x, end.y)
        }
        val pressurePaths = paths.mapIndexedNotNull { bucket, path ->
            if (path.isEmpty) null else bucketFactor(bucket) to path
        }
        return StrokeRenderData(bounds, centerline, pressurePaths, maxFactor, points.size)
    }

    private fun smoothCenterline(points: List<InkPoint>) = Path().apply {
        moveTo(points.first().x, points.first().y)
        if (points.size == 1) return@apply
        for (index in 1 until points.lastIndex) {
            val end = midpoint(points[index], points[index + 1])
            quadraticTo(points[index].x, points[index].y, end.x, end.y)
        }
        lineTo(points.last().x, points.last().y)
    }

    private fun midpoint(a: InkPoint, b: InkPoint) = Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f)
    private fun widthFactor(pressure: Float) = MIN_WIDTH_FACTOR + pressure * 0.75f
    private fun bucketFactor(bucket: Int) = MIN_WIDTH_FACTOR +
        (MAX_WIDTH_FACTOR - MIN_WIDTH_FACTOR) * bucket / (PRESSURE_BUCKETS - 1).toFloat()

    private companion object {
        const val PRESSURE_BUCKETS = 7
        const val PRESSURE_FILTER = 0.42f
        const val MIN_PRESSURE = 0.15f
        const val MIN_WIDTH_FACTOR = 0.55f
        const val MAX_WIDTH_FACTOR = 1.30f
    }
}
