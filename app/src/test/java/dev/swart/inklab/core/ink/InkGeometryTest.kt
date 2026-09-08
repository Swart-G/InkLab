package dev.swart.inklab.core.ink

import androidx.compose.ui.geometry.Offset
import dev.swart.inklab.core.model.InkPoint
import dev.swart.inklab.core.model.InkStroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
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
    fun autoShape_regularizesConfidentRectangle() {
        val points = buildList {
            for (x in 0..100 step 10) add(Offset(x.toFloat(), 0f))
            for (y in 10..70 step 10) add(Offset(100f, y.toFloat()))
            for (x in 90 downTo 0 step 10) add(Offset(x.toFloat(), 70f))
            for (y in 60 downTo 0 step 10) add(Offset(0f, y.toFloat()))
        }
        val result = autoRecognizeShape(stroke(points))
        assertEquals(5, result.points.size)
        assertEquals(Offset(0f, 0f), result.points[0].offset())
        assertEquals(Offset(100f, 0f), result.points[1].offset())
        assertEquals(Offset(100f, 70f), result.points[2].offset())
        assertEquals(Offset(0f, 70f), result.points[3].offset())
        assertEquals(result.points.first().offset(), result.points.last().offset())
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

    @Test
    fun autoShape_leavesSmallHandwrittenMarkUntouched() {
        val input = stroke(
            listOf(
                Offset(2f, 4f), Offset(9f, 1f), Offset(14f, 11f),
                Offset(21f, 3f), Offset(27f, 13f), Offset(31f, 5f)
            )
        )
        assertSame(input, autoRecognizeShape(input))
    }

    private fun stroke(points: List<Offset>) = InkStroke(
        points = points.mapIndexed { index, point -> InkPoint(point.x, point.y, index.toLong()) }
    )
}
