package dev.swart.inklab.ui

import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.swart.inklab.AppContainer
import dev.swart.inklab.core.model.*
import dev.swart.inklab.core.recognition.*
import kotlinx.coroutines.launch

enum class EditorTool { PEN, ERASER, LASSO }

data class UiRecognition(
    val mode: RecognitionMode,
    val loading: Boolean = false,
    val result: RecognitionResult? = null,
    val error: String? = null
)

class EditorViewModel : ViewModel() {
    val strokes = mutableStateListOf<InkStroke>()
    var tool by mutableStateOf(EditorTool.PEN)
    var currentPoints by mutableStateOf<List<InkPoint>>(emptyList())
    var lassoPoints by mutableStateOf<List<Offset>>(emptyList())
    var selectedIds by mutableStateOf<Set<String>>(emptySet())
    var textProviderId by mutableStateOf("mlkit-ru")
    var mathProviderId by mutableStateOf("myscript-math")
    var recognition by mutableStateOf<UiRecognition?>(null)
    var showSettings by mutableStateOf(false)
    var showLab by mutableStateOf(false)
    var penWidth by mutableFloatStateOf(5f)

    private val undo = ArrayDeque<InkStroke>()

    fun startStroke(p: InkPoint) { currentPoints = listOf(p) }
    fun addPoint(p: InkPoint) { currentPoints = currentPoints + p }
    fun finishStroke() {
        if (currentPoints.size > 1) {
            strokes += InkStroke(points = currentPoints, width = penWidth)
            selectedIds = emptySet()
        }
        currentPoints = emptyList()
    }

    fun eraseAt(point: Offset, radius: Float = 24f) {
        val hit = strokes.indexOfLast { stroke -> stroke.points.any { (it.offset() - point).getDistance() <= radius } }
        if (hit >= 0) {
            undo.addLast(strokes[hit])
            strokes.removeAt(hit)
        }
    }

    fun undoErase() { if (undo.isNotEmpty()) strokes += undo.removeLast() }
    fun clear() { strokes.clear(); selectedIds = emptySet(); lassoPoints = emptyList() }

    fun startLasso(p: Offset) { lassoPoints = listOf(p); selectedIds = emptySet() }
    fun addLasso(p: Offset) { lassoPoints = lassoPoints + p }
    fun finishLasso() {
        if (lassoPoints.size < 3) return
        selectedIds = strokes.filter { stroke ->
            val inside = stroke.points.count { pointInPolygon(it.offset(), lassoPoints) }
            inside >= maxOf(1, stroke.points.size / 3)
        }.map { it.id }.toSet()
    }

    private fun pointInPolygon(p: Offset, poly: List<Offset>): Boolean {
        var result = false
        var j = poly.lastIndex
        for (i in poly.indices) {
            val pi = poly[i]; val pj = poly[j]
            if ((pi.y > p.y) != (pj.y > p.y) &&
                p.x < (pj.x - pi.x) * (p.y - pi.y) / ((pj.y - pi.y).takeIf { it != 0f } ?: 0.0001f) + pi.x
            ) result = !result
            j = i
        }
        return result
    }

    fun recognize(context: Context, mode: RecognitionMode) {
        val id = if (mode == RecognitionMode.TEXT) textProviderId else mathProviderId
        val provider = AppContainer.recognitionRegistry.get(id) ?: return
        val input = strokes.filter { it.id in selectedIds }
        if (input.isEmpty()) {
            recognition = UiRecognition(mode, error = "Сначала выделите рукопись лассо")
            return
        }
        viewModelScope.launch {
            recognition = UiRecognition(mode, loading = true)
            runCatching {
                if (provider.state(context) == ProviderState.MODEL_REQUIRED && provider.id == "mlkit-ru") {
                    provider.prepare(context).getOrThrow()
                }
                provider.recognize(context, input, mode)
            }.onSuccess { recognition = UiRecognition(mode, result = it) }
                .onFailure { recognition = UiRecognition(mode, error = it.message ?: "Ошибка распознавания") }
        }
    }
}
