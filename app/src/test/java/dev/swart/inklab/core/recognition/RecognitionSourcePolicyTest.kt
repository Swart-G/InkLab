package dev.swart.inklab.core.recognition

import dev.swart.inklab.core.model.InkPoint
import dev.swart.inklab.core.model.InkStroke
import org.junit.Assert.assertEquals
import org.junit.Test

class RecognitionSourcePolicyTest {
    private val source = InkStroke(
        id = "source",
        points = listOf(InkPoint(10f, 20f, 1L), InkPoint(40f, 20f, 2L))
    )

    @Test
    fun unchangedSourceIsAccepted() {
        assertEquals(
            RecognitionSourceState.UNCHANGED,
            recognitionSourceState("doc", "page", "doc", "page", setOf(source.id), listOf(source), listOf(source))
        )
    }

    @Test
    fun movedOrEditedSourceIsConflict() {
        val moved = source.copy(points = source.points.map { it.copy(x = it.x + 15f) })
        assertEquals(
            RecognitionSourceState.SOURCE_CHANGED,
            recognitionSourceState("doc", "page", "doc", "page", setOf(source.id), listOf(source), listOf(moved))
        )
    }

    @Test
    fun deletedSourceIsConflict() {
        assertEquals(
            RecognitionSourceState.SOURCE_CHANGED,
            recognitionSourceState("doc", "page", "doc", "page", setOf(source.id), listOf(source), emptyList())
        )
    }

    @Test
    fun leavingRequestPageIsLocationChange() {
        assertEquals(
            RecognitionSourceState.LOCATION_CHANGED,
            recognitionSourceState("doc", "page", "doc", "other", setOf(source.id), listOf(source), listOf(source))
        )
    }
}
