package dev.swart.inklab.core.model

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

/**
 * Immutable geometry for the notebook's vertical page strip.
 *
 * Ink coordinates remain page-local. A page can keep a non-zero or negative legacy origin while
 * its visible paper is placed in strip coordinates. Viewport pan/zoom is applied only after the
 * page-local -> strip transform, so navigating or reordering pages never mutates ink coordinates.
 */
data class PageStripPlacement(
    val index: Int,
    val pageId: String,
    val stripLeft: Float,
    val stripTop: Float,
    val width: Float,
    val height: Float,
    val originX: Float,
    val originY: Float
) {
    val stripBounds: Rect
        get() = Rect(stripLeft, stripTop, stripLeft + width, stripTop + height)

    val localBounds: Rect
        get() = Rect(originX, originY, originX + width, originY + height)

    fun localToStrip(point: Offset): Offset = Offset(
        x = stripLeft + (point.x - originX),
        y = stripTop + (point.y - originY)
    )

    fun stripToLocal(point: Offset): Offset = Offset(
        x = originX + (point.x - stripLeft),
        y = originY + (point.y - stripTop)
    )
}

data class PageStripLayout(
    val placements: List<PageStripPlacement>,
    val width: Float,
    val height: Float,
    val gap: Float
) {
    fun placement(index: Int): PageStripPlacement? = placements.getOrNull(index)

    fun placement(pageId: String): PageStripPlacement? = placements.firstOrNull { it.pageId == pageId }

    fun pageAtStrip(point: Offset): Int? = placements.firstOrNull { it.stripBounds.contains(point) }?.index

    fun pageAtScreen(point: Offset, scale: Float, viewportOffset: Offset): Int? {
        if (!scale.isFinite() || scale <= 0f) return null
        return pageAtStrip((point - viewportOffset) / scale)
    }

    fun pageLocalToScreen(index: Int, point: Offset, scale: Float, viewportOffset: Offset): Offset? {
        if (!scale.isFinite() || scale <= 0f) return null
        val placement = placement(index) ?: return null
        return placement.localToStrip(point) * scale + viewportOffset
    }

    fun screenToPageLocal(index: Int, point: Offset, scale: Float, viewportOffset: Offset): Offset? {
        if (!scale.isFinite() || scale <= 0f) return null
        val placement = placement(index) ?: return null
        return placement.stripToLocal((point - viewportOffset) / scale)
    }

    companion object {
        const val DEFAULT_GAP = 28f

        private data class CacheEntry(
            val pages: List<InkPage>,
            val gap: Float,
            val layout: PageStripLayout
        )

        /**
         * The editor replaces its page list on every committed document mutation. Reusing the
         * layout while that exact list instance is stable keeps S Pen historical-point transforms
         * allocation-free without tying the cache to stroke/object equality.
         */
        @Volatile
        private var cacheEntry: CacheEntry? = null

        fun from(pages: List<InkPage>, gap: Float = DEFAULT_GAP): PageStripLayout {
            require(gap.isFinite() && gap >= 0f) { "Page gap must be finite and non-negative" }
            cacheEntry?.let { cached ->
                if (cached.pages === pages && cached.gap == gap) return cached.layout
            }

            val layout = build(pages, gap)
            cacheEntry = CacheEntry(pages, gap, layout)
            return layout
        }

        private fun build(pages: List<InkPage>, gap: Float): PageStripLayout {
            if (pages.isEmpty()) return PageStripLayout(emptyList(), 0f, 0f, gap)

            pages.forEach { page ->
                require(page.width.isFinite() && page.width > 0f)
                require(page.height.isFinite() && page.height > 0f)
                require(page.originX.isFinite() && page.originY.isFinite())
            }

            val stripWidth = pages.maxOf { it.width }
            var top = 0f
            val placements = pages.mapIndexed { index, page ->
                PageStripPlacement(
                    index = index,
                    pageId = page.id,
                    stripLeft = (stripWidth - page.width) / 2f,
                    stripTop = top,
                    width = page.width,
                    height = page.height,
                    originX = page.originX,
                    originY = page.originY
                ).also {
                    top += page.height + gap
                }
            }
            val stripHeight = (top - gap).coerceAtLeast(0f)
            return PageStripLayout(placements, stripWidth, stripHeight, gap)
        }
    }
}
