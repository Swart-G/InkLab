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
import dev.swart.inklab.core.model.ConvertedInkKind
import dev.swart.inklab.core.model.ConvertedInkObject
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
import kotlin.math.max

enum class EditorTool { PEN, ERASER, LASSO }
enum class CanvasInputSource { STYLUS, STYLUS_ERASER, TOUCH, MOUSE }
enum class AppScreen { EDITOR, BOARDS, SETTINGS, BOARD_SETTINGS, LAB }

data class UiRecognition(
    val mode: RecognitionMode,
    val loading: Boolean = false,
    val result: RecognitionResult? = null,
    val error: String? = null,
    val sourceIds: Set<String> = emptySet()
)

private data class DocumentSnapshot(
    val strokes: List<InkStroke>,
    val convertedObjects: List<ConvertedInkObject>
)

class EditorViewModel(application: Application) : AndroidViewModel(application) {
    private val boardRepository = BoardRepository(application)
    private val inputRepository = InputPreferencesRepository(application)

    val boards = mutableStateListOf<InkBoard>()
    val strokes = mutableStateListOf<InkStroke>()
    val convertedObjects = mutableStateListOf<ConvertedInkObject>()
    val modelProgress = mutableStateMapOf<String, Float>()
    val modelErrors = mutableStateMapOf<String, String>()

    var screen by mutableStateOf(AppScreen.EDITOR)
    var currentBoardId by mutableStateOf("")
        private set
    var tool by mutableStateOf(EditorTool.PEN)
    val currentPoints = mutableStateListOf<InkPoint>()
    val lassoPoints = mutableStateListOf<Offset>()
    var selectedIds by mutableStateOf<Set<String>>(emptySet())
    var selectedConvertedId by mutableStateOf<String?>(null)
        private set
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

    private val undo = ArrayDeque<DocumentSnapshot>()
    private val redo = ArrayDeque<DocumentSnapshot>()
    private var eraserGestureChanged = false
    private var movingSelection = false
    private var movingConverted = false
    private var lastSelectionPoint = Offset.Zero
    private var saveJob: Job? = null

    val currentBoard: InkBoard?
        get() = boards.firstOrNull { it.id == currentBoardId }

    val currentSelectionBounds: Rect?
        get() = selectionBounds(strokes, selectedIds)

    val selectedConvertedObject: ConvertedInkObject?
        get() = selectedConvertedId?.let { id -> convertedObjects.firstOrNull { it.id == id } }

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
        convertedObjects.clear()
        convertedObjects += board.convertedObjects
        clearSelection()
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
            strokes = strokes.toList(),
            convertedObjects = convertedObjects.toList()
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

    fun panBy(delta: Offset) { viewportOffset += delta }

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
        selectedConvertedId = null
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

    fun beginErase() { eraserGestureChanged = false; selectedConvertedId = null }

    fun eraseAt(point: Offset) {
        val radius = inputPreferences.eraserRadius / viewportScale
        val before = strokes.toList()
        val after = when (inputPreferences.eraserMode) {
            EraserMode.STROKE -> before.filterNot { strokeIntersectsCircle(it, point, radius) }
            EraserMode.PIXEL -> before.flatMap { splitStrokeByCircle(it, point, radius) }
        }
        if (after != before) {
            if (!eraserGestureChanged) {
                undo.addLast(snapshot())
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
        val selectedObject = selectedConvertedObject
        if (selectedObject != null && selectedObject.bounds().inflate(18f / viewportScale).contains(point)) {
            pushUndo()
            movingConverted = true
            movingSelection = false
            lastSelectionPoint = point
            lassoPoints.clear()
            return
        }

        val hitObject = convertedObjects.asReversed().firstOrNull { it.bounds().inflate(10f / viewportScale).contains(point) }
        if (hitObject != null) {
            selectedConvertedId = hitObject.id
            selectedIds = emptySet()
            pushUndo()
            movingConverted = true
            movingSelection = false
            lastSelectionPoint = point
            lassoPoints.clear()
            return
        }

        selectedConvertedId = null
        movingConverted = false
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
        if (movingConverted) {
            val id = selectedConvertedId ?: return
            val delta = point - lastSelectionPoint
            if (delta != Offset.Zero) {
                val index = convertedObjects.indexOfFirst { it.id == id }
                if (index >= 0) {
                    val item = convertedObjects[index]
                    convertedObjects[index] = item.copy(x = item.x + delta.x, y = item.y + delta.y)
                }
                lastSelectionPoint = point
            }
        } else if (movingSelection) {
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
        if (movingConverted) {
            movingConverted = false
            persistCurrentBoard()
            return
        }
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
        selectedConvertedId = null
        lassoPoints.clear()
    }

    fun clearSelection() {
        selectedIds = emptySet()
        selectedConvertedId = null
        lassoPoints.clear()
    }

    fun selectConvertedObject(id: String) {
        if (convertedObjects.none { it.id == id }) return
        selectedConvertedId = id
        selectedIds = emptySet()
        lassoPoints.clear()
    }

    fun deleteSelection() {
        if (selectedIds.isEmpty()) return
        pushUndo()
        strokes.removeAll { it.id in selectedIds }
        clearSelection()
        persistCurrentBoard()
    }

    fun deleteConvertedSelection() {
        val id = selectedConvertedId ?: return
        pushUndo()
        convertedObjects.removeAll { it.id == id }
        selectedConvertedId = null
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

    fun duplicateConvertedSelection() {
        val item = selectedConvertedObject ?: return
        pushUndo()
        val copy = item.copy(id = UUID.randomUUID().toString(), x = item.x + 22f, y = item.y + 22f)
        convertedObjects += copy
        selectedConvertedId = copy.id
        persistCurrentBoard()
    }

    fun restoreConvertedSelection() {
        val item = selectedConvertedObject ?: return
        pushUndo()
        val originalBounds = sourceBounds(item.sourceStrokes) ?: item.bounds()
        val delta = Offset(item.x - originalBounds.left, item.y - originalBounds.top)
        val restored = item.sourceStrokes.map { stroke ->
            stroke.copy(
                id = UUID.randomUUID().toString(),
                points = stroke.points.map { point -> point.copy(x = point.x + delta.x, y = point.y + delta.y) }
            )
        }
        convertedObjects.removeAll { it.id == item.id }
        strokes += restored
        selectedIds = restored.mapTo(mutableSetOf()) { it.id }
        selectedConvertedId = null
        persistCurrentBoard()
    }

    fun undo() {
        if (undo.isEmpty()) return
        redo.addLast(snapshot())
        restore(undo.removeLast())
    }

    fun redo() {
        if (redo.isEmpty()) return
        undo.addLast(snapshot())
        restore(redo.removeLast())
    }

    fun clear() {
        if (strokes.isEmpty() && convertedObjects.isEmpty()) return
        pushUndo()
        strokes.clear()
        convertedObjects.clear()
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
        val sourceIds = input.mapTo(mutableSetOf()) { it.id }
        viewModelScope.launch {
            recognition = UiRecognition(mode, loading = true, sourceIds = sourceIds)
            runCatching {
                provider.recognize(context, input, mode)
            }.onSuccess { recognition = UiRecognition(mode, result = it, sourceIds = sourceIds) }
                .onFailure { recognition = UiRecognition(mode, error = it.message ?: "Ошибка распознавания", sourceIds = sourceIds) }
        }
    }

    fun applyRecognition() {
        val state = recognition ?: return
        val result = state.result ?: return
        val source = strokes.filter { it.id in state.sourceIds }
        if (source.isEmpty() || result.primary.isBlank()) return
        val bounds = sourceBounds(source) ?: return
        pushUndo()
        strokes.removeAll { it.id in state.sourceIds }

        val isText = state.mode == RecognitionMode.TEXT
        val textSize = if (isText) (bounds.height * 0.82f).coerceIn(22f, 72f) else (bounds.height * 0.9f).coerceIn(24f, 82f)
        val width = if (isText) max(bounds.width, result.primary.length * textSize * 0.46f) else max(bounds.width, textSize * 3f)
        val height = if (isText) max(bounds.height, textSize * 1.35f) else max(bounds.height, textSize * 1.75f)
        val objectColor = source.firstOrNull()?.color ?: penColor
        val converted = ConvertedInkObject(
            kind = if (isText) ConvertedInkKind.TEXT else ConvertedInkKind.MATH,
            content = result.primary,
            x = bounds.left,
            y = bounds.top,
            width = width,
            height = height,
            textSize = textSize,
            color = objectColor,
            sourceStrokes = source,
            providerId = result.providerId
        )
        convertedObjects += converted
        selectedIds = emptySet()
        selectedConvertedId = converted.id
        recognition = null
        persistCurrentBoard()
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
        undo.addLast(snapshot())
        trimHistory(undo)
        redo.clear()
    }

    private fun snapshot() = DocumentSnapshot(strokes.toList(), convertedObjects.toList())

    private fun restore(snapshot: DocumentSnapshot) {
        strokes.clear()
        strokes += snapshot.strokes
        convertedObjects.clear()
        convertedObjects += snapshot.convertedObjects
        clearSelection()
        persistCurrentBoard()
    }

    private fun sourceBounds(source: List<InkStroke>): Rect? {
        val points = source.flatMap { it.points }
        if (points.isEmpty()) return null
        return Rect(
            points.minOf { it.x },
            points.minOf { it.y },
            points.maxOf { it.x },
            points.maxOf { it.y }
        )
    }

    private fun trimHistory(history: ArrayDeque<DocumentSnapshot>) {
        while (history.size > 50) history.removeFirst()
    }

    private fun persistCurrentBoard() {
        val index = boards.indexOfFirst { it.id == currentBoardId }
        if (index < 0) return
        boards[index] = boards[index].copy(
            strokes = strokes.toList(),
            convertedObjects = convertedObjects.toList(),
            updatedAt = System.currentTimeMillis()
        )
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
