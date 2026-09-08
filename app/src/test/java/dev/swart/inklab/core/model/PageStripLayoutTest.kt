package dev.swart.inklab.core.model

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageStripLayoutTest {
    @Test
    fun mixedSizePagesAreCenteredAndStackedOnce() {
        val pages = listOf(
            InkPage(id = "wide", width = 1000f, height = 1400f),
            InkPage(id = "narrow", width = 700f, height = 900f, originX = -50f, originY = 100f)
        )

        val layout = PageStripLayout.from(pages)
        val wide = layout.placement(0)!!
        val narrow = layout.placement(1)!!

        assertEquals(1000f, layout.width, 0f)
        assertEquals(2328f, layout.height, 0f)
        assertEquals(0f, wide.stripLeft, 0f)
        assertEquals(0f, wide.stripTop, 0f)
        assertEquals(150f, narrow.stripLeft, 0f)
        assertEquals(1428f, narrow.stripTop, 0f)
    }

    @Test
    fun negativeLegacyOriginRoundTripsThroughStripCoordinates() {
        val page = InkPage(
            id = "legacy",
            width = 800f,
            height = 1200f,
            originX = -240f,
            originY = -110f
        )
        val placement = PageStripLayout.from(listOf(page)).placement(0)!!
        val local = Offset(-175.5f, 42.25f)

        val strip = placement.localToStrip(local)
        val restored = placement.stripToLocal(strip)

        assertOffsetEquals(local, restored)
        assertTrue(placement.localBounds.contains(local))
    }

    @Test
    fun pageLocalScreenRoundTripIsStableUnderZoomAndPan() {
        val pages = listOf(
            InkPage(id = "a", width = 1000f, height = 1414f),
            InkPage(id = "b", width = 820f, height = 1050f, originX = -80f, originY = 35f)
        )
        val layout = PageStripLayout.from(pages)
        val scale = 2.375f
        val viewport = Offset(-431.2f, 95.7f)
        val local = Offset(143.75f, 612.125f)

        val screen = layout.pageLocalToScreen(1, local, scale, viewport)!!
        val restored = layout.screenToPageLocal(1, screen, scale, viewport)!!

        assertOffsetEquals(local, restored, 0.001f)
    }

    @Test
    fun reorderMovesPaperButNeverMutatesPageLocalCoordinates() {
        val a = InkPage(id = "a", width = 1000f, height = 1000f, originX = -25f, originY = 10f)
        val b = InkPage(id = "b", width = 700f, height = 800f, originX = 50f, originY = -70f)
        val before = PageStripLayout.from(listOf(a, b))
        val after = PageStripLayout.from(listOf(b, a))
        val local = Offset(125f, 220f)

        val beforePlacement = before.placement("b")!!
        val afterPlacement = after.placement("b")!!
        assertTrue(beforePlacement.stripTop != afterPlacement.stripTop)
        assertOffsetEquals(local, beforePlacement.stripToLocal(beforePlacement.localToStrip(local)))
        assertOffsetEquals(local, afterPlacement.stripToLocal(afterPlacement.localToStrip(local)))
    }

    @Test
    fun hitTestingUsesPaperBoundsAndRejectsGap() {
        val pages = listOf(
            InkPage(id = "a", width = 1000f, height = 1000f),
            InkPage(id = "b", width = 600f, height = 800f)
        )
        val layout = PageStripLayout.from(pages)
        val scale = 1.5f
        val viewport = Offset(20f, -100f)
        val second = layout.placement(1)!!
        val secondCenter = second.stripBounds.center * scale + viewport
        val gapPoint = Offset(500f, 1014f) * scale + viewport

        assertEquals(1, layout.pageAtScreen(secondCenter, scale, viewport))
        assertNull(layout.pageAtScreen(gapPoint, scale, viewport))
    }

    @Test
    fun invalidViewportScaleDoesNotCreateCoordinates() {
        val layout = PageStripLayout.from(listOf(InkPage(id = "a")))
        assertNull(layout.pageAtScreen(Offset.Zero, 0f, Offset.Zero))
        assertNull(layout.pageLocalToScreen(0, Offset.Zero, Float.NaN, Offset.Zero))
        assertNull(layout.screenToPageLocal(0, Offset.Zero, -1f, Offset.Zero))
    }

    private fun assertOffsetEquals(expected: Offset, actual: Offset, tolerance: Float = 0f) {
        assertEquals(expected.x, actual.x, tolerance)
        assertEquals(expected.y, actual.y, tolerance)
    }
}
