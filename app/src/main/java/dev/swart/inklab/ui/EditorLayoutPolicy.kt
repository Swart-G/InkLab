package dev.swart.inklab.ui

/**
 * Pure layout rules for the P2 editor chrome. Keeping these thresholds outside Compose makes the
 * ergonomics contract deterministic and testable across tablets, split screen and large font use.
 */
object EditorLayoutPolicy {
    const val TOP_BAR_MAX_DP = 56f
    const val TOOL_ROW_MAX_DP = 56f
    const val MAX_PERMANENT_TOP_DP = TOP_BAR_MAX_DP + TOOL_ROW_MAX_DP

    const val INLINE_PAGE_RAIL_WIDTH_DP = 184f
    const val MIN_CANVAS_AFTER_RAIL_DP = 480f

    /**
     * The page rail is pinned only when the canvas still retains the minimum useful width after
     * subtracting the actual rail width. On narrower windows pages must use an overlay/compact UI.
     */
    fun useInlinePageRail(workspaceWidthDp: Float): Boolean =
        workspaceWidthDp.isFinite() && workspaceWidthDp > 0f &&
            workspaceWidthDp - INLINE_PAGE_RAIL_WIDTH_DP >= MIN_CANVAS_AFTER_RAIL_DP
}
