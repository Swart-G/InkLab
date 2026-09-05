package dev.swart.inklab.core.ink

import androidx.compose.ui.geometry.Rect
import dev.swart.inklab.core.model.*
import org.junit.Assert.*
import org.junit.Test

class PageClippingTest {
    private val bounds=Rect(0f,0f,100f,100f)
    @Test fun crossingEdgeIsClippedWithInterpolatedTime() {
        val result=clipStrokeToPage(InkStroke(points=listOf(InkPoint(50f,50f,100),InkPoint(150f,50f,200))),bounds).single()
        assertEquals(100f,result.points.last().x);assertEquals(150L,result.points.last().timestamp)
    }
    @Test fun outsideExcursionDoesNotCreateConnectingLine() {
        val result=clipStrokeToPage(InkStroke(points=listOf(InkPoint(50f,20f,1),InkPoint(150f,20f,2),InkPoint(150f,80f,3),InkPoint(50f,80f,4))),bounds)
        assertEquals(2,result.size);assertEquals(20f,result[0].points.last().y);assertEquals(80f,result[1].points.first().y)
    }
    @Test fun entirelyOutsideIsDiscarded() {
        assertTrue(clipStrokeToPage(InkStroke(points=listOf(InkPoint(-10f,0f,1),InkPoint(-10f,100f,2))),bounds).isEmpty())
    }
}
