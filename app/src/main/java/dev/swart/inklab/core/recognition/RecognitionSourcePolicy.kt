package dev.swart.inklab.core.recognition

import dev.swart.inklab.core.model.InkStroke

enum class RecognitionSourceState {
    UNCHANGED,
    SOURCE_CHANGED,
    LOCATION_CHANGED
}

fun recognitionSourceState(
    requestDocumentId: String,
    requestPageId: String,
    currentDocumentId: String,
    currentPageId: String?,
    sourceIds: Set<String>,
    originalStrokes: List<InkStroke>,
    currentStrokes: List<InkStroke>
): RecognitionSourceState {
    if (requestDocumentId != currentDocumentId || requestPageId != currentPageId) {
        return RecognitionSourceState.LOCATION_CHANGED
    }

    val originalById = originalStrokes.associateBy { it.id }
    val currentById = currentStrokes.asSequence()
        .filter { it.id in sourceIds }
        .associateBy { it.id }

    return if (
        sourceIds.size == originalById.size &&
        sourceIds.size == currentById.size &&
        originalById == currentById
    ) {
        RecognitionSourceState.UNCHANGED
    } else {
        RecognitionSourceState.SOURCE_CHANGED
    }
}
