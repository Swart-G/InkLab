package dev.swart.inklab.core.ink

import androidx.compose.ui.geometry.Offset
import dev.swart.inklab.core.model.InkPoint
import dev.swart.inklab.core.model.InkStroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InkGeometryTest {
    @Test
    fun pointInPolygon_handlesInsideAndOutside() {
        val square = listOf(Offset(0f, 0f), Offset(100f, 0f), Offset(100f, 100f), Offset(0f, 100f))
        assertTrue(pointInPolygon(Offset(50f, 50f), square))
        assertFalse(pointInPolygon(Offset(150f, 50f), square))
    }

    @Test
    fun strokeEraser_hitsSparseSegment() {
        val stroke = stroke(listOf(Offset(0f, 0f), Offset(100f, 0f)))
        assertTrue(strokeIntersectsCircle(stroke, Offset(50f, 3f), 5f))
    }

    @Test
    fun pixelEraser_splitsStroke() {
        val stroke = stroke((0..10).map { Offset(it * 10f, 0f) })
        val fragments = splitStrokeByCircle(stroke, Offset(50f, 0f), 8f)
        assertEquals(2, fragments.size)
        assertTrue(fragments.first().points.maxOf { it.x } < 50f)
        assertTrue(fragments.last().points.minOf { it.x } > 50f)
    }

    private fun stroke(points: List<Offset>) = InkStroke(
        points = points.mapIndexed { index, point -> InkPoint(point.x, point.y, index.toLong()) }
    )
}
