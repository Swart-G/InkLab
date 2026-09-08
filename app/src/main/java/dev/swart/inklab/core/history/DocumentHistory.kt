package dev.swart.inklab.core.history

import dev.swart.inklab.core.model.ConvertedInkObject
import dev.swart.inklab.core.model.InkBoard
import dev.swart.inklab.core.model.InkPage
import dev.swart.inklab.core.model.InkStroke

/** Operation-based editor history with count and estimated-memory budgets. */
class DocumentHistory(
    private val maxEntries: Int = 100,
    private val maxEstimatedBytes: Long = 32L * 1024L * 1024L
) {
    data class Result(val board: InkBoard, val pageIndex: Int)
    private data class Pending(val board: InkBoard, val pageIndex: Int)

    private sealed interface Operation {
        val beforePageIndex: Int
        val afterPageIndex: Int
        val estimatedBytes: Long
        fun forward(board: InkBoard): InkBoard
        fun backward(board: InkBoard): InkBoard
    }

    private data class StructureOperation(
        val beforePages: List<InkPage>,
        val afterPages: List<InkPage>,
        val beforeTrash: List<InkPage>,
        val afterTrash: List<InkPage>,
        override val beforePageIndex: Int,
        override val afterPageIndex: Int
    ) : Operation {
        override val estimatedBytes: Long =
            estimatePages(beforePages) + estimatePages(afterPages) + estimatePages(beforeTrash) + estimatePages(afterTrash)
        override fun forward(board: InkBoard): InkBoard = board.copy(
            pages = afterPages,
            trashedPages = afterTrash,
            lastPageIndex = afterPageIndex.coerceIn(0, afterPages.lastIndex)
        )
        override fun backward(board: InkBoard): InkBoard = board.copy(
            pages = beforePages,
            trashedPages = beforeTrash,
            lastPageIndex = beforePageIndex.coerceIn(0, beforePages.lastIndex)
        )
    }

    private data class ContentOperation(
        val deltas: List<PageDelta>,
        override val beforePageIndex: Int,
        override val afterPageIndex: Int
    ) : Operation {
        override val estimatedBytes: Long = deltas.sumOf { it.estimatedBytes }
        override fun forward(board: InkBoard): InkBoard = board.copy(
            pages = board.pages.map { page -> deltas.firstOrNull { it.pageId == page.id }?.forward(page) ?: page },
            lastPageIndex = afterPageIndex.coerceIn(0, board.pages.lastIndex)
        )
        override fun backward(board: InkBoard): InkBoard = board.copy(
            pages = board.pages.map { page -> deltas.firstOrNull { it.pageId == page.id }?.backward(page) ?: page },
            lastPageIndex = beforePageIndex.coerceIn(0, board.pages.lastIndex)
        )
    }

    private data class PageDelta(
        val pageId: String,
        val beforeStrokeOrder: List<String>,
        val afterStrokeOrder: List<String>,
        val beforeStrokePayload: Map<String, InkStroke>,
        val afterStrokePayload: Map<String, InkStroke>,
        val beforeObjectOrder: List<String>,
        val afterObjectOrder: List<String>,
        val beforeObjectPayload: Map<String, ConvertedInkObject>,
        val afterObjectPayload: Map<String, ConvertedInkObject>
    ) {
        val estimatedBytes: Long =
            estimateIds(beforeStrokeOrder) + estimateIds(afterStrokeOrder) +
                estimateIds(beforeObjectOrder) + estimateIds(afterObjectOrder) +
                beforeStrokePayload.values.sumOf(::estimateStroke) + afterStrokePayload.values.sumOf(::estimateStroke) +
                beforeObjectPayload.values.sumOf(::estimateObject) + afterObjectPayload.values.sumOf(::estimateObject)

        fun forward(page: InkPage): InkPage = page.copy(
            strokes = rebuild(page.strokes, afterStrokeOrder, afterStrokePayload),
            convertedObjects = rebuild(page.convertedObjects, afterObjectOrder, afterObjectPayload)
        )
        fun backward(page: InkPage): InkPage = page.copy(
            strokes = rebuild(page.strokes, beforeStrokeOrder, beforeStrokePayload),
            convertedObjects = rebuild(page.convertedObjects, beforeObjectOrder, beforeObjectPayload)
        )

        private fun <T : Any> rebuild(current: List<T>, order: List<String>, payload: Map<String, T>): List<T> {
            val currentMap = current.associateBy { idOf(it) }
            return order.map { id -> payload[id] ?: currentMap[id] ?: error("History payload missing $id") }
        }
    }

    private val undo = ArrayDeque<Operation>()
    private val redo = ArrayDeque<Operation>()
    private var undoEstimatedBytes = 0L
    private var pending: Pending? = null

    val canUndo: Boolean get() = undo.isNotEmpty()
    val canRedo: Boolean get() = redo.isNotEmpty()
    val undoSize: Int get() = undo.size
    val estimatedBytes: Long get() = undoEstimatedBytes

    fun begin(board: InkBoard, pageIndex: Int) {
        if (pending == null) pending = Pending(board, pageIndex)
    }

    fun cancelPending() { pending = null }

    fun commit(board: InkBoard, pageIndex: Int) {
        val before = pending ?: return
        pending = null
        if (before.board.id != board.id) return
        val operation = buildOperation(before.board, board, before.pageIndex, pageIndex) ?: return
        undo.addLast(operation)
        undoEstimatedBytes += operation.estimatedBytes
        redo.clear()
        trim()
    }

    fun undo(current: InkBoard): Result? {
        pending = null
        val operation = undo.removeLastOrNull() ?: return null
        undoEstimatedBytes = (undoEstimatedBytes - operation.estimatedBytes).coerceAtLeast(0L)
        val board = operation.backward(current)
        redo.addLast(operation)
        return Result(board, operation.beforePageIndex.coerceIn(0, board.pages.lastIndex))
    }

    fun redo(current: InkBoard): Result? {
        pending = null
        val operation = redo.removeLastOrNull() ?: return null
        val board = operation.forward(current)
        undo.addLast(operation)
        undoEstimatedBytes += operation.estimatedBytes
        trim(clearRedo = false)
        return Result(board, operation.afterPageIndex.coerceIn(0, board.pages.lastIndex))
    }

    fun clear() {
        pending = null
        undo.clear()
        redo.clear()
        undoEstimatedBytes = 0L
    }

    private fun trim(clearRedo: Boolean = true) {
        while (undo.size > maxEntries || (undoEstimatedBytes > maxEstimatedBytes && undo.size > 1)) {
            val removed = undo.removeFirstOrNull() ?: break
            undoEstimatedBytes = (undoEstimatedBytes - removed.estimatedBytes).coerceAtLeast(0L)
        }
        if (clearRedo) redo.clear()
    }

    private fun buildOperation(before: InkBoard, after: InkBoard, beforeIndex: Int, afterIndex: Int): Operation? {
        if (before.pages == after.pages && before.trashedPages == after.trashedPages) return null
        val sameStructure = before.pages.map { it.id } == after.pages.map { it.id } && before.trashedPages == after.trashedPages
        if (!sameStructure) return structure(before, after, beforeIndex, afterIndex)
        val metadataChanged = before.pages.zip(after.pages).any { (oldPage, newPage) ->
            oldPage.copy(strokes = emptyList(), convertedObjects = emptyList()) !=
                newPage.copy(strokes = emptyList(), convertedObjects = emptyList())
        }
        if (metadataChanged) return structure(before, after, beforeIndex, afterIndex)
        val pageDeltas = before.pages.zip(after.pages).mapNotNull { (oldPage, newPage) ->
            if (oldPage == newPage) null else pageDelta(oldPage, newPage)
        }
        return if (pageDeltas.isEmpty()) null else ContentOperation(pageDeltas, beforeIndex, afterIndex)
    }

    private fun structure(before: InkBoard, after: InkBoard, beforeIndex: Int, afterIndex: Int) = StructureOperation(
        beforePages = before.pages,
        afterPages = after.pages,
        beforeTrash = before.trashedPages,
        afterTrash = after.trashedPages,
        beforePageIndex = beforeIndex,
        afterPageIndex = afterIndex
    )

    private fun pageDelta(before: InkPage, after: InkPage): PageDelta {
        val beforeStrokes = before.strokes.associateBy { it.id }
        val afterStrokes = after.strokes.associateBy { it.id }
        val changedStrokeIds = (beforeStrokes.keys + afterStrokes.keys).filter { beforeStrokes[it] != afterStrokes[it] }.toSet()
        val beforeObjects = before.convertedObjects.associateBy { it.id }
        val afterObjects = after.convertedObjects.associateBy { it.id }
        val changedObjectIds = (beforeObjects.keys + afterObjects.keys).filter { beforeObjects[it] != afterObjects[it] }.toSet()
        return PageDelta(
            pageId = before.id,
            beforeStrokeOrder = before.strokes.map { it.id },
            afterStrokeOrder = after.strokes.map { it.id },
            beforeStrokePayload = beforeStrokes.filterKeys { it in changedStrokeIds },
            afterStrokePayload = afterStrokes.filterKeys { it in changedStrokeIds },
            beforeObjectOrder = before.convertedObjects.map { it.id },
            afterObjectOrder = after.convertedObjects.map { it.id },
            beforeObjectPayload = beforeObjects.filterKeys { it in changedObjectIds },
            afterObjectPayload = afterObjects.filterKeys { it in changedObjectIds }
        )
    }

    companion object {
        private fun idOf(value: Any): String = when (value) {
            is InkStroke -> value.id
            is ConvertedInkObject -> value.id
            else -> error("Unsupported history item ${value::class}")
        }
        private fun estimateIds(ids: List<String>): Long = ids.sumOf { 16L + it.length * 2L }
        private fun estimateStroke(stroke: InkStroke): Long = 96L + stroke.id.length * 2L + stroke.points.size * 32L
        private fun estimateObject(value: ConvertedInkObject): Long =
            160L + value.id.length * 2L + value.content.length * 2L + value.providerId.length * 2L + value.sourceStrokes.sumOf(::estimateStroke)
        private fun estimatePage(page: InkPage): Long =
            128L + page.id.length * 2L + page.strokes.sumOf(::estimateStroke) + page.convertedObjects.sumOf(::estimateObject)
        private fun estimatePages(pages: List<InkPage>): Long = pages.sumOf(::estimatePage)
    }
}
