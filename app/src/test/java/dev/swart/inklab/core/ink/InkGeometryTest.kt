package dev.swart.inklab.core.ink

import androidx.compose.ui.geometry.Offset
import dev.swart.inklab.core.model.InkPoint
import dev.swart.inklab.core.model.InkStroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

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

    @Test
    fun autoShape_straightensConfidentLine() {
        val input = stroke((0..20).map { index -> Offset(index * 5f, index % 2 * 0.4f) })
        val result = autoRecognizeShape(input)
        assertEquals(2, result.points.size)
        assertEquals(0f, result.points.first().x)
        assertEquals(100f, result.points.last().x)
    }

    @Test
    fun autoShape_regularizesLargeCircle() {
        val input = stroke((0..48).map { index ->
            val angle = index / 48f * (2f * PI).toFloat()
            Offset(100f + cos(angle) * 55f, 120f + sin(angle) * 48f)
        })
        val result = autoRecognizeShape(input)
        assertEquals(49, result.points.size)
        assertTrue((result.points.first().offset() - result.points.last().offset()).getDistance() < 0.01f)
    }

    private fun stroke(points: List<Offset>) = InkStroke(
        points = points.mapIndexed { index, point -> InkPoint(point.x, point.y, index.toLong()) }
    )
}
