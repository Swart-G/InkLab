package dev.swart.inklab.core.history

import dev.swart.inklab.core.model.ConvertedInkKind
import dev.swart.inklab.core.model.ConvertedInkObject
import dev.swart.inklab.core.model.DocumentFormat
import dev.swart.inklab.core.model.InkBoard
import dev.swart.inklab.core.model.InkPage
import dev.swart.inklab.core.model.InkPoint
import dev.swart.inklab.core.model.InkStroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentHistoryTest {
    private fun stroke(id: String, x: Float = 10f) = InkStroke(
        id = id,
        points = listOf(InkPoint(x, 10f, 1L), InkPoint(x + 20f, 12f, 2L))
    )

    private fun converted(id: String = "object", content: String = "x+1") = ConvertedInkObject(
        id = id,
        kind = ConvertedInkKind.MATH,
        content = content,
        x = 10f,
        y = 20f,
        width = 120f,
        height = 48f,
        textSize = 32f,
        sourceStrokes = listOf(stroke("source")),
        providerId = "test"
    )

    private fun board(page: InkPage = InkPage(id = "page")) = InkBoard(
        id = "board",
        format = DocumentFormat.NOTEBOOK,
        pages = listOf(page)
    )

    @Test
    fun strokeDeltaUndoRedoRestoresExactContent() {
        val history = DocumentHistory()
        val before = board()
        val after = before.copy(pages = listOf(before.pages.single().copy(strokes = listOf(stroke("s1")))))
        history.begin(before, 0)
        history.commit(after, 0)
        assertTrue(history.canUndo)
        val undone = history.undo(after)!!
        assertEquals(before.pages, undone.board.pages)
        assertTrue(history.canRedo)
        val redone = history.redo(undone.board)!!
        assertEquals(after.pages, redone.board.pages)
    }

    @Test
    fun changedStrokeWithSameIdIsReversible() {
        val history = DocumentHistory()
        val original = stroke("s1")
        val changed = original.copy(width = 17f, points = original.points.map { it.copy(x = it.x + 50f) })
        val before = board(InkPage(id = "page", strokes = listOf(original)))
        val after = before.copy(pages = listOf(before.pages.single().copy(strokes = listOf(changed))))
        history.begin(before, 0)
        history.commit(after, 0)
        assertEquals(original, history.undo(after)!!.board.pages.single().strokes.single())
    }

    @Test
    fun convertedObjectDeltaPreservesEditableContentAndSourceStrokes() {
        val history = DocumentHistory()
        val original = converted()
        val edited = original.copy(content = "x^2+1", x = 42f, y = 55f)
        val before = board(InkPage(id = "page", convertedObjects = listOf(original)))
        val after = before.copy(pages = listOf(before.pages.single().copy(convertedObjects = listOf(edited))))

        history.begin(before, 0)
        history.commit(after, 0)
        val undone = history.undo(after)!!
        assertEquals(original, undone.board.pages.single().convertedObjects.single())
        val redone = history.redo(undone.board)!!
        assertEquals(edited, redone.board.pages.single().convertedObjects.single())
        assertEquals(original.sourceStrokes, redone.board.pages.single().convertedObjects.single().sourceStrokes)
    }

    @Test
    fun structuralPageOperationRoundTrips() {
        val history = DocumentHistory()
        val before = board()
        val second = InkPage(id = "second")
        val after = before.copy(pages = before.pages + second, lastPageIndex = 1)
        history.begin(before, 0)
        history.commit(after, 1)
        val undone = history.undo(after)!!
        assertEquals(listOf("page"), undone.board.pages.map { it.id })
        assertEquals(0, undone.pageIndex)
        val redone = history.redo(undone.board)!!
        assertEquals(listOf("page", "second"), redone.board.pages.map { it.id })
        assertEquals(1, redone.pageIndex)
    }

    @Test
    fun pageReorderIsOneReversibleStructuralOperation() {
        val history = DocumentHistory()
        val first = InkPage(id = "first")
        val second = InkPage(id = "second")
        val third = InkPage(id = "third")
        val before = InkBoard(id = "board", format = DocumentFormat.NOTEBOOK, pages = listOf(first, second, third), lastPageIndex = 0)
        val after = before.copy(pages = listOf(third, first, second), lastPageIndex = 0)

        history.begin(before, 0)
        history.commit(after, 0)
        assertEquals(1, history.undoSize)
        val undone = history.undo(after)!!
        assertEquals(listOf("first", "second", "third"), undone.board.pages.map { it.id })
        val redone = history.redo(undone.board)!!
        assertEquals(listOf("third", "first", "second"), redone.board.pages.map { it.id })
    }

    @Test
    fun historyUsesConfiguredEntryBudget() {
        val history = DocumentHistory(maxEntries = 100, maxEstimatedBytes = Long.MAX_VALUE)
        var current = board()
        repeat(120) { index ->
            val next = current.copy(
                pages = listOf(current.pages.single().copy(strokes = current.pages.single().strokes + stroke("s$index", index.toFloat())))
            )
            history.begin(current, 0)
            history.commit(next, 0)
            current = next
        }
        assertEquals(100, history.undoSize)
        repeat(100) { current = history.undo(current)!!.board }
        assertFalse(history.canUndo)
        assertEquals(20, current.pages.single().strokes.size)
    }

    @Test
    fun oversizedNewestOperationRemainsUndoable() {
        val history = DocumentHistory(maxEntries = 100, maxEstimatedBytes = 1L)
        val before = board()
        val after = before.copy(pages = listOf(before.pages.single().copy(strokes = listOf(stroke("large")))))
        history.begin(before, 0)
        history.commit(after, 0)
        assertTrue(history.estimatedBytes > 1L)
        assertTrue(history.canUndo)
        assertEquals(before.pages, history.undo(after)!!.board.pages)
    }

    @Test
    fun memoryBudgetEvictsOlderOperationsBeforeNewest() {
        val probe = DocumentHistory(maxEntries = 100, maxEstimatedBytes = Long.MAX_VALUE)
        var current = board()
        val first = current.copy(pages = listOf(current.pages.single().copy(strokes = listOf(stroke("s1")))))
        probe.begin(current, 0)
        probe.commit(first, 0)
        val oneOperationBytes = probe.estimatedBytes

        val history = DocumentHistory(maxEntries = 100, maxEstimatedBytes = oneOperationBytes + 1L)
        history.begin(current, 0)
        history.commit(first, 0)
        current = first
        val second = current.copy(pages = listOf(current.pages.single().copy(strokes = current.pages.single().strokes + stroke("s2"))))
        history.begin(current, 0)
        history.commit(second, 0)
        assertEquals(1, history.undoSize)
        assertEquals(first.pages, history.undo(second)!!.board.pages)
    }
}
