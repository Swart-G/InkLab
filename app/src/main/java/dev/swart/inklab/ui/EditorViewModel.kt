package dev.swart.inklab.ui

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.swart.inklab.AppContainer
import dev.swart.inklab.core.ink.pointInPolygon
import dev.swart.inklab.core.ink.selectionBounds
import dev.swart.inklab.core.ink.splitStrokeByCircle
import dev.swart.inklab.core.ink.strokeIntersectsCircle
import dev.swart.inklab.core.model.BoardSettings
import dev.swart.inklab.core.model.InkBoard
import dev.swart.inklab.core.model.InkPoint
import dev.swart.inklab.core.model.InkStroke
import dev.swart.inklab.core.recognition.ProviderState
import dev.swart.inklab.core.recognition.RecognitionMode
import dev.swart.inklab.core.recognition.RecognitionResult
import dev.swart.inklab.core.storage.BoardRepository
import dev.swart.inklab.core.storage.EraserMode
import dev.swart.inklab.core.storage.FingerAction
import dev.swart.inklab.core.storage.InputPreferences
import dev.swart.inklab.core.storage.InputPreferencesRepository
import dev.swart.inklab.core.storage.StylusButtonAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

enum class EditorTool { PEN, ERASER, LASSO }
enum class CanvasInputSource { STYLUS, STYLUS_ERASER, TOUCH, MOUSE }
enum class AppScreen { EDITOR, BOARDS, SETTINGS, BOARD_SETTINGS, LAB }

data class UiRecognition(
    val mode: RecognitionMode,
    val loading: Boolean = false,
    val result: RecognitionResult? = null,
    val error: String? = null
)

class EditorViewModel(application: Application) : AndroidViewModel(application) {
    private val boardRepository = BoardRepository(application)
    private val inputRepository = InputPreferencesRepository(application)

    val boards = mutableStateListOf<InkBoard>()
    val strokes = mutableStateListOf<InkStroke>()
    val modelProgress = mutableStateMapOf<String, Float>()
    val modelErrors = mutableStateMapOf<String, String>()

    var screen by mutableStateOf(AppScreen.EDITOR)
    var currentBoardId by mutableStateOf("")
        private set
    var tool by mutableStateOf(EditorTool.PEN)
    val currentPoints = mutableStateListOf<InkPoint>()
    val lassoPoints = mutableStateListOf<Offset>()
    var selectedIds by mutableStateOf<Set<String>>(emptySet())
    var textProviderId by mutableStateOf("mlkit-ru")
    var mathProviderId by mutableStateOf("pix2text-math")
    var recognition by mutableStateOf<UiRecognition?>(null)
    var penWidth by mutableFloatStateOf(5f)
    var penColor by mutableStateOf(Color(0xFF25272C))
    var viewportScale by mutableFloatStateOf(1f)
        private set
    var viewportOffset by mutableStateOf(Offset.Zero)
        private set
    var inputPreferences by mutableStateOf(inputRepository.load())
        private set
    var stylusInContact by mutableStateOf(false)
        private set
    var eraserCursor by mutableStateOf<Offset?>(null)

    private val undo = ArrayDeque<List<InkStroke>>()
    private val redo = ArrayDeque<List<InkStroke>>()
    private var eraserGestureChanged = false
    private var movingSelection = false
    private var lastSelectionPoint = Offset.Zero
    private var saveJob: Job? = null

    val currentBoard: InkBoard?
        get() = boards.firstOrNull { it.id == currentBoardId }

    val currentSelectionBounds: Rect?
        get() = selectionBounds(strokes, selectedIds)

    init {
        val loaded = boardRepository.load()
        boards += if (loaded.isEmpty()) listOf(InkBoard(title = "Лекция 01", subject = "OCR playground")) else loaded
        openBoard(boards.first().id, persistPrevious = false)
    }

    fun navigate(destination: AppScreen) {
        if (destination != AppScreen.EDITOR) persistCurrentBoard()
        screen = destination
    }

    fun createBoard(): InkBoard {
        persistCurrentBoard()
        val board = InkBoard(title = "Новая доска")
        boards.add(0, board)
        scheduleSave()
        openBoard(board.id, persistPrevious = false)
        screen = AppScreen.EDITOR
        return board
    }

    fun openBoard(id: String, persistPrevious: Boolean = true) {
        if (id == currentBoardId) {
            screen = AppScreen.EDITOR
            return
        }
        if (persistPrevious) persistCurrentBoard()
        val board = boards.firstOrNull { it.id == id } ?: return
        currentBoardId = board.id
        strokes.clear()
        strokes += board.strokes
        selectedIds = emptySet()
        lassoPoints.clear()
        undo.clear()
        redo.clear()
        resetViewport()
        screen = AppScreen.EDITOR
    }

    fun deleteBoard(id: String) {
        if (boards.size <= 1) return
        val wasCurrent = id == currentBoardId
        boards.removeAll { it.id == id }
        if (wasCurrent) openBoard(boards.first().id, persistPrevious = false)
        scheduleSave()
    }

    fun updateBoard(title: String, subject: String, settings: BoardSettings) {
        val index = boards.indexOfFirst { it.id == currentBoardId }
        if (index < 0) return
        boards[index] = boards[index].copy(
            title = title.ifBlank { "Без названия" },
            subject = subject,
            settings = settings,
            updatedAt = System.currentTimeMillis(),
            strokes = strokes.toList()
        )
        scheduleSave()
    }

    fun updateInputPreferences(value: InputPreferences) {
        inputPreferences = value
        inputRepository.save(value)
    }

    fun effectiveTool(source: CanvasInputSource, sideButton: Boolean): EditorTool? = when (source) {
        CanvasInputSource.STYLUS_ERASER -> EditorTool.ERASER
        CanvasInputSource.STYLUS -> if (sideButton) {
            when (inputPreferences.stylusButtonAction) {
                StylusButtonAction.ERASE -> EditorTool.ERASER
                StylusButtonAction.LASSO -> EditorTool.LASSO
                StylusButtonAction.IGNORE -> tool
            }
        } else tool
        CanvasInputSource.TOUCH -> when (inputPreferences.fingerAction) {
            FingerAction.DRAW -> EditorTool.PEN
            FingerAction.ERASE -> EditorTool.ERASER
            FingerAction.PAN, FingerAction.IGNORE -> null
        }
        CanvasInputSource.MOUSE -> tool
    }

    fun setStylusContact(active: Boolean) { stylusInContact = active }

    fun screenToCanvas(point: Offset): Offset = (point - viewportOffset) / viewportScale

    fun panBy(delta: Offset) {
        viewportOffset += delta
    }

    fun zoomBy(factor: Float, centroid: Offset) {
        val nextScale = (viewportScale * factor).coerceIn(0.45f, 4f)
        val anchor = screenToCanvas(centroid)
        viewportScale = nextScale
        viewportOffset = centroid - anchor * nextScale
    }

    fun resetViewport() {
        viewportScale = 1f
        viewportOffset = Offset.Zero
    }

    fun startStroke(point: InkPoint) {
        currentPoints.clear()
        currentPoints += point
    }

    fun addPoint(point: InkPoint) {
        val previous = currentPoints.lastOrNull()
        if (previous == null) {
            currentPoints += point
            return
        }
        val delta = point.offset() - previous.offset()
        val distance = delta.getDistance()
        if (distance < 0.7f) return
        val parts = (distance / 2.5f).toInt().coerceIn(1, 16)
        for (part in 1..parts) {
            val fraction = part.toFloat() / parts
            currentPoints += point.copy(
                x = previous.x + delta.x * fraction,
                y = previous.y + delta.y * fraction,
                timestamp = previous.timestamp + ((point.timestamp - previous.timestamp) * fraction).toLong(),
                pressure = previous.pressure + (point.pressure - previous.pressure) * fraction
            )
        }
    }

    fun finishStroke() {
        if (currentPoints.size > 1) {
            pushUndo()
            strokes += InkStroke(points = currentPoints.toList(), width = penWidth, color = penColor)
            selectedIds = emptySet()
            persistCurrentBoard()
        }
        currentPoints.clear()
    }

    fun cancelStroke() { currentPoints.clear() }

    fun beginErase() { eraserGestureChanged = false }

    fun eraseAt(point: Offset) {
        val radius = inputPreferences.eraserRadius / viewportScale
        val before = strokes.toList()
        val after = when (inputPreferences.eraserMode) {
            EraserMode.STROKE -> before.filterNot { strokeIntersectsCircle(it, point, radius) }
            EraserMode.PIXEL -> before.flatMap { splitStrokeByCircle(it, point, radius) }
        }
        if (after != before) {
            if (!eraserGestureChanged) {
                undo.addLast(before)
                trimHistory(undo)
                redo.clear()
                eraserGestureChanged = true
            }
            strokes.clear()
            strokes += after
            selectedIds = selectedIds.intersect(after.mapTo(mutableSetOf()) { it.id })
        }
    }

    fun finishErase() {
        if (eraserGestureChanged) persistCurrentBoard()
        eraserGestureChanged = false
        eraserCursor = null
    }

    fun startLasso(point: Offset) {
        val bounds = currentSelectionBounds?.inflate(18f / viewportScale)
        if (bounds?.contains(point) == true) {
            pushUndo()
            movingSelection = true
            lastSelectionPoint = point
            lassoPoints.clear()
        } else {
            movingSelection = false
            lassoPoints.clear()
            lassoPoints += point
            selectedIds = emptySet()
        }
    }

    fun addLasso(point: Offset) {
        if (movingSelection) {
            val delta = point - lastSelectionPoint
            if (delta != Offset.Zero) {
                val moved = strokes.map { stroke ->
                    if (stroke.id in selectedIds) stroke.copy(points = stroke.points.map { it.copy(x = it.x + delta.x, y = it.y + delta.y) }) else stroke
                }
                strokes.clear()
                strokes += moved
                lastSelectionPoint = point
            }
        } else if (lassoPoints.lastOrNull()?.let { (it - point).getDistance() >= 1.5f } != false) {
            lassoPoints += point
        }
    }

    fun finishLasso() {
        if (movingSelection) {
            movingSelection = false
            persistCurrentBoard()
            return
        }
        if (lassoPoints.size < 3) {
            lassoPoints.clear()
            return
        }
        selectedIds = strokes.filter { stroke ->
            val pointsInside = stroke.points.count { pointInPolygon(it.offset(), lassoPoints) }
            pointsInside >= maxOf(1, stroke.points.size / 4) || stroke.points.any { point ->
                lassoPoints.zipWithNext().any { (a, b) ->
                    Rect(minOf(a.x, b.x), minOf(a.y, b.y), maxOf(a.x, b.x), maxOf(a.y, b.y))
                        .inflate(stroke.width).contains(point.offset())
                }
            }
        }.mapTo(mutableSetOf()) { it.id }
        lassoPoints.clear()
    }

    fun clearSelection() { selectedIds = emptySet(); lassoPoints.clear() }

    fun deleteSelection() {
        if (selectedIds.isEmpty()) return
        pushUndo()
        strokes.removeAll { it.id in selectedIds }
        clearSelection()
        persistCurrentBoard()
    }

    fun duplicateSelection() {
        if (selectedIds.isEmpty()) return
        pushUndo()
        val copies = strokes.filter { it.id in selectedIds }.map { stroke ->
            stroke.copy(
                id = UUID.randomUUID().toString(),
                points = stroke.points.map { it.copy(x = it.x + 22f, y = it.y + 22f) }
            )
        }
        strokes += copies
        selectedIds = copies.mapTo(mutableSetOf()) { it.id }
        persistCurrentBoard()
    }

    fun undo() {
        if (undo.isEmpty()) return
        redo.addLast(strokes.toList())
        restore(undo.removeLast())
    }

    fun redo() {
        if (redo.isEmpty()) return
        undo.addLast(strokes.toList())
        restore(redo.removeLast())
    }

    fun clear() {
        if (strokes.isEmpty()) return
        pushUndo()
        strokes.clear()
        clearSelection()
        persistCurrentBoard()
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
                if (provider.state(context) == ProviderState.MODEL_REQUIRED) provider.prepare(context).getOrThrow()
                provider.recognize(context, input, mode)
            }.onSuccess { recognition = UiRecognition(mode, result = it) }
                .onFailure { recognition = UiRecognition(mode, error = it.message ?: "Ошибка распознавания") }
        }
    }

    fun prepareProvider(context: Context, id: String) {
        val provider = AppContainer.recognitionRegistry.get(id) ?: return
        modelErrors.remove(id)
        modelProgress[id] = 0f
        viewModelScope.launch {
            provider.prepare(context) { progress -> viewModelScope.launch { modelProgress[id] = progress } }
                .onFailure { modelErrors[id] = it.message ?: "Не удалось загрузить модель" }
            modelProgress.remove(id)
        }
    }

    fun removeProvider(context: Context, id: String) {
        val provider = AppContainer.recognitionRegistry.get(id) ?: return
        modelProgress[id] = 0f
        viewModelScope.launch {
            provider.remove(context).onFailure { modelErrors[id] = it.message ?: "Не удалось удалить модель" }
            modelProgress.remove(id)
        }
    }

    private fun pushUndo() {
        undo.addLast(strokes.toList())
        trimHistory(undo)
        redo.clear()
    }

    private fun restore(snapshot: List<InkStroke>) {
        strokes.clear()
        strokes += snapshot
        clearSelection()
        persistCurrentBoard()
    }

    private fun trimHistory(history: ArrayDeque<List<InkStroke>>) {
        while (history.size > 50) history.removeFirst()
    }

    private fun persistCurrentBoard() {
        val index = boards.indexOfFirst { it.id == currentBoardId }
        if (index < 0) return
        boards[index] = boards[index].copy(strokes = strokes.toList(), updatedAt = System.currentTimeMillis())
        scheduleSave()
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        val snapshot = boards.toList()
        saveJob = viewModelScope.launch(Dispatchers.IO) {
            delay(220)
            boardRepository.save(snapshot)
        }
    }

    private fun Rect.inflate(value: Float) = Rect(left - value, top - value, right + value, bottom + value)
}
