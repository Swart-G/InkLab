package dev.swart.inklab.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorLayoutPolicyTest {
    @Test
    fun permanentTopChromeFitsP2Budget() {
        assertEquals(112f, EditorLayoutPolicy.MAX_PERMANENT_TOP_DP, 0f)
    }

    @Test
    fun railAppearsOnlyWhenAtLeast480DpCanvasRemains() {
        assertFalse(EditorLayoutPolicy.useInlinePageRail(663.9f))
        assertTrue(EditorLayoutPolicy.useInlinePageRail(664f))
        assertTrue(EditorLayoutPolicy.useInlinePageRail(900f))
    }

    @Test
    fun invalidOrTinyWidthsNeverPinRail() {
        assertFalse(EditorLayoutPolicy.useInlinePageRail(Float.NaN))
        assertFalse(EditorLayoutPolicy.useInlinePageRail(Float.POSITIVE_INFINITY))
        assertFalse(EditorLayoutPolicy.useInlinePageRail(0f))
        assertFalse(EditorLayoutPolicy.useInlinePageRail(320f))
    }
}
