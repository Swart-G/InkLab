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
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.swart.inklab.AppContainer
import dev.swart.inklab.core.ink.autoRecognizeShape
import dev.swart.inklab.core.ink.pointInPolygon
import dev.swart.inklab.core.ink.selectionBounds
import dev.swart.inklab.core.ink.splitStrokeByCircle
import dev.swart.inklab.core.ink.strokeBounds
import dev.swart.inklab.core.ink.strokeHitTest
import dev.swart.inklab.core.ink.strokeIntersectsCircle
import dev.swart.inklab.core.model.BoardSettings
import dev.swart.inklab.core.model.ConvertedInkKind
import dev.swart.inklab.core.model.ConvertedInkObject
import dev.swart.inklab.core.model.DocumentFormat
import dev.swart.inklab.core.model.InkBoard
import dev.swart.inklab.core.model.InkFolder
import dev.swart.inklab.core.model.InkPage
import dev.swart.inklab.core.model.InkPoint
import dev.swart.inklab.core.model.InkStroke
import dev.swart.inklab.core.model.PageOrientation
import dev.swart.inklab.core.recognition.RecognitionMode
import dev.swart.inklab.core.recognition.RecognitionResult
import dev.swart.inklab.core.storage.BoardRepository
import dev.swart.inklab.core.storage.EraserMode
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
enum class AppScreen { EDITOR, BOARDS, SETTINGS, BOARD_SETTINGS }

data class UiRecognition(
    val mode: RecognitionMode,
    val loading: Boolean = false,
    val result: RecognitionResult? = null,
    val error: String? = null,
    val sourceIds: Set<String> = emptySet(),
    val documentId: String = "",
    val pageId: String = "",
    val originalStrokes: List<InkStroke> = emptyList()
)

private data class DocumentSnapshot(
    val board: InkBoard,
    val pageIndex: Int
)

class EditorViewModel(application: Application) : AndroidViewModel(application) {
    private val boardRepository = BoardRepository(application)
    private val inputRepository = InputPreferencesRepository(application)
    private val initialInputPreferences = inputRepository.load()

    val boards = mutableStateListOf<InkBoard>()
    val folders = mutableStateListOf<InkFolder>()
    val strokes = mutableStateListOf<InkStroke>()
    val convertedObjects = mutableStateListOf<ConvertedInkObject>()
    val modelProgress = mutableStateMapOf<String, Float>()
    var saving by mutableStateOf(false)
    private val modelRequests = mutableMapOf<String, Int>()
    var storageError by mutableStateOf<String?>(null)
    var fullScreen by mutableStateOf(false)
    var pageManager by mutableStateOf(false)
    var pagePanel by mutableStateOf(false)
    var libraryTools by mutableStateOf(false)
    var audioPanel by mutableStateOf(false)
    var languagePanel by mutableStateOf(false)
    var viewportWidth = 1f
    var viewportHeight = 1f
    var lastStylusTime = -10000L
    var stylusHover = false
    private val saves = kotlinx.coroutines.channels.Channel<Pair<List<InkBoard>, List<InkFolder>>>(kotlinx.coroutines.channels.Channel.CONFLATED)
    private var inputSnapshot: DocumentSnapshot? = null
    private var inputUndoCount = 0
    val modelErrors = mutableStateMapOf<String, String>()

    var screen by mutableStateOf(AppScreen.EDITOR)
    var currentBoardId by mutableStateOf("")
        private set
    var currentPageIndex by mutableStateOf(0)
        private set
    var tool by mutableStateOf(EditorTool.PEN)
    val currentPoints = mutableStateListOf<InkPoint>()
    val lassoPoints = mutableStateListOf<Offset>()
    var selectedIds by mutableStateOf<Set<String>>(emptySet())
    var selectedConvertedId by mutableStateOf<String?>(null)
        private set
    var editingConvertedId by mutableStateOf<String?>(null)
        private set
    var textProviderId by mutableStateOf("mlkit-ru")
    var mathProviderId by mutableStateOf("pix2text-math")
    var recognition by mutableStateOf<UiRecognition?>(null)
    var penWidth by mutableFloatStateOf(5f)
    var penColor by mutableStateOf(Color(initialInputPreferences.penColor))
    var viewportScale by mutableFloatStateOf(1f)
        private set
    var viewportOffset by mutableStateOf(Offset.Zero)
        private set
    var inputPreferences by mutableStateOf(initialInputPreferences)
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

    val editingConvertedObject: ConvertedInkObject?
        get() = editingConvertedId?.let { id -> convertedObjects.firstOrNull { it.id == id } }

    val selectionBounds: Rect?
        get() = selectedConvertedObject?.bounds() ?: currentSelectionBounds

    init {
        folders += boardRepository.loadFolders()
        val loaded = boardRepository.load()
        storageError = boardRepository.loadError
        boards += if (loaded.isEmpty() && storageError == null) listOf(InkBoard(title = "Новая тетрадь", format = DocumentFormat.NOTEBOOK)) else loaded
        boards.firstOrNull { it.deletedAt == null }?.let { openBoard(it.id, persistPrevious = false) }
            ?: run { screen = AppScreen.BOARDS }
        viewModelScope.launch {
            for ((documents, directories) in saves) {
                val result = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    runCatching { boardRepository.save(documents); boardRepository.saveFolders(directories) }
                }
                storageError = result.exceptionOrNull()?.let { "Не удалось сохранить: ${it.message}" }
                saving = false
            }
        }
        if (loaded.isEmpty() && storageError == null) scheduleSave()
    }

    fun navigate(destination: AppScreen) {
        if (destination != AppScreen.EDITOR) persistCurrentBoard()
        screen = destination
    }

    fun createDocument(
        title: String,
        format: DocumentFormat,
        orientation: PageOrientation,
        settings: BoardSettings = BoardSettings(),
        folderId: String? = null
    ): InkBoard {
        persistCurrentBoard()
        val board = InkBoard(
            title = title.ifBlank { if (format == DocumentFormat.NOTEBOOK) "Новая тетрадь" else "Новая доска" },
            format = format,
            orientation = orientation,
            settings = settings,
            pages = listOf(newPage(orientation)),
            folderId = folderId
        )
        boards.add(0, board)
        scheduleSave()
        openBoard(board.id, persistPrevious = false)
        screen = AppScreen.EDITOR
        return board
    }

    fun createBoard(): InkBoard = createDocument("Новая доска", DocumentFormat.BOARD, PageOrientation.LANDSCAPE)

    fun createFolder(title: String, parentId: String? = null): InkFolder {
        val folder = InkFolder(title = title.ifBlank { "Новая папка" }, parentId = parentId)
        folders.add(0, folder)
        scheduleSave()
        return folder
    }

    fun renameFolder(id: String, title: String) {
        val index = folders.indexOfFirst { it.id == id }
        if (index < 0 || title.isBlank()) return
        folders[index] = folders[index].copy(title = title.trim(), updatedAt = System.currentTimeMillis())
        scheduleSave()
    }

    fun deleteFolder(id: String) {
        val folder = folders.firstOrNull { it.id == id } ?: return
        val parent = folder.parentId
        for (index in boards.indices) {
            if (boards[index].folderId == id) boards[index] = boards[index].copy(folderId = parent, updatedAt = System.currentTimeMillis())
        }
        for (index in folders.indices) {
            if (folders[index].parentId == id) folders[index] = folders[index].copy(parentId = parent, updatedAt = System.currentTimeMillis())
        }
        folders.removeAll { it.id == id }
        scheduleSave()
    }

    fun openBoard(id: String, persistPrevious: Boolean = true) {
        if (id == currentBoardId) {
            screen = AppScreen.EDITOR
            return
        }
        if (persistPrevious) persistCurrentBoard()
        val board = boards.firstOrNull { it.id == id && it.deletedAt == null } ?: return
        currentBoardId = board.id
        textProviderId = "mlkit-${board.languageTag}"
        currentPageIndex = board.lastPageIndex.coerceIn(0, board.pages.lastIndex)
        val page = board.pages[currentPageIndex]
        strokes.clear()
        strokes += page.strokes
        convertedObjects.clear()
        convertedObjects += page.convertedObjects
        clearSelection()
        undo.clear()
        redo.clear()
        if (board.savedScale > 0) {
            viewportScale = board.savedScale
            viewportOffset = Offset(board.savedOffsetX, board.savedOffsetY)
            constrainViewport()
        } else resetViewport()
        screen = AppScreen.EDITOR
    }

    fun deleteBoard(id: String) {
        val wasCurrent = id == currentBoardId
        val index = boards.indexOfFirst { it.id == id }
        if (index >= 0) boards[index] = boards[index].copy(deletedAt = System.currentTimeMillis())
        if (wasCurrent) {
            val next = boards.firstOrNull { it.deletedAt == null }
            if (next != null) openBoard(next.id, persistPrevious = false) else {
                currentBoardId = ""
                currentPageIndex = 0
                strokes.clear()
                convertedObjects.clear()
                clearSelection()
                screen = AppScreen.BOARDS
            }
        }
        scheduleSave()
    }

    fun renameBoard(id: String, title: String) {
        val index = boards.indexOfFirst { it.id == id }
        if (index < 0 || title.isBlank()) return
        boards[index] = boards[index].copy(title = title.trim(), updatedAt = System.currentTimeMillis())
        scheduleSave()
    }

    fun updateBoard(title: String, subject: String, settings: BoardSettings) {
        val index = boards.indexOfFirst { it.id == currentBoardId }
        if (index < 0) return
        boards[index] = boards[index].copy(
            title = title.ifBlank { "Без названия" },
            subject = subject,
            settings = settings,
            updatedAt = System.currentTimeMillis()
        )
        persistCurrentBoard()
        scheduleSave()
    }

    fun updatePaperSettings(settings: BoardSettings) {
        val index = boards.indexOfFirst { it.id == currentBoardId }
        if (index < 0) return
        boards[index] = boards[index].copy(settings = settings, updatedAt = System.currentTimeMillis())
        scheduleSave()
    }

    private fun newPage(orientation: PageOrientation = currentBoard?.orientation ?: PageOrientation.PORTRAIT) =
        if (orientation == PageOrientation.PORTRAIT) InkPage() else InkPage(width = 1414f, height = 1000f)

    fun addPage() = editPages { pages -> pages.add(currentPageIndex + 1, newPage()); currentPageIndex + 1 }
    fun duplicatePage() = editPages { pages ->
        val page = pages[currentPageIndex]
        pages.add(currentPageIndex + 1, page.copy(id = UUID.randomUUID().toString()))
        currentPageIndex + 1
    }
    fun movePage(delta: Int) = movePageTo((currentPageIndex + delta).coerceIn(0, (currentBoard?.pages?.lastIndex ?: 0)))
    fun movePageTo(target: Int) = editPages { pages ->
        val destination = target.coerceIn(0, pages.lastIndex)
        pages.add(destination, pages.removeAt(currentPageIndex)); destination
    }
    private fun editPages(edit: (MutableList<InkPage>) -> Int) {
        val board = currentBoard ?: return
        if (board.format != DocumentFormat.NOTEBOOK) return
        pushUndo()
        val pages = currentDocument().pages.toMutableList()
        val next = edit(pages)
        replaceBoard(currentDocument().copy(pages = pages, lastPageIndex = next))
        loadPage(next)
        scrollToPage(next)
    }
    fun openPage(index: Int) {
        if (index !in (currentBoard?.pages?.indices ?: return)) return
        activatePage(index)
        scrollToPage(index)
    }
    fun activatePage(index: Int) {
        if (index == currentPageIndex) return
        persistCurrentBoard()
        loadPage(index)
    }
    fun deleteCurrentPage() {
        val page = currentBoard?.pages?.getOrNull(currentPageIndex) ?: return
        editPages { pages ->
            replaceBoard(currentDocument().copy(trashedPages = currentDocument().trashedPages + page))
            pages.removeAt(currentPageIndex)
            if (pages.isEmpty()) pages.add(newPage())
            currentPageIndex.coerceAtMost(pages.lastIndex)
        }
    }
    fun restorePage(pageId: String) {
        val board = currentBoard ?: return
        val page = board.trashedPages.firstOrNull { it.id == pageId } ?: return
        pushUndo()
        replaceBoard(currentDocument().copy(pages = currentDocument().pages + page, trashedPages = board.trashedPages.filterNot { it.id == pageId }))
        scheduleSave()
    }
    fun replaceBoard(board: InkBoard) {
        val index = boards.indexOfFirst { it.id == board.id }
        if (index >= 0) boards[index] = board
    }
    fun setLanguage(tag: String) {
        currentBoard?.let { replaceBoard(it.copy(languageTag = tag)) }
        textProviderId = "mlkit-$tag"
        scheduleSave()
    }
    fun toggleFavorite(id: String) {
        boards.firstOrNull { it.id == id }?.let { replaceBoard(it.copy(favorite = !it.favorite)) }; scheduleSave()
    }
    fun moveDocument(id: String, folderId: String?) {
        boards.firstOrNull { it.id == id }?.let { replaceBoard(it.copy(folderId = folderId)) }; scheduleSave()
    }
    fun restoreDocument(id: String) {
        boards.firstOrNull { it.id == id }?.let { replaceBoard(it.copy(deletedAt = null)) }; scheduleSave()
    }
    fun permanentlyDelete(id: String) { boards.removeAll { it.id == id && it.deletedAt != null }; scheduleSave() }
    fun permanentlyDeletePage(id: String) { currentBoard?.let {replaceBoard(it.copy(trashedPages=it.trashedPages.filterNot {page->page.id==id}))};scheduleSave() }
    fun emptyTrash() {
        boards.removeAll {it.deletedAt!=null}
        for(index in boards.indices) boards[index]=boards[index].copy(trashedPages=emptyList())
        scheduleSave()
    }
    fun importDocuments(documents: List<InkBoard>) {
        boardRepository.allowRecovery()
        storageError = null
        boards.addAll(documents); scheduleSave()
    }
    fun restorePreferences(json: org.json.JSONObject?) {
        if (json == null) return
        val prefs = inputPreferences
        updateInputPreferences(prefs.copy(
            darkTheme = json.optBoolean("darkTheme", prefs.darkTheme), systemTheme = json.optBoolean("systemTheme", prefs.systemTheme),
            nightPaper = json.optBoolean("nightPaper", prefs.nightPaper), twoFingerUndo = json.optBoolean("twoFingerUndo", prefs.twoFingerUndo),
            wifiOnlyModels = json.optBoolean("wifiOnlyModels", prefs.wifiOnlyModels), palmRejection = json.optBoolean("palmRejection", prefs.palmRejection),
            pressureEnabled = json.optBoolean("pressureEnabled", prefs.pressureEnabled), autoShapes = json.optBoolean("autoShapes", prefs.autoShapes),
            penColor = json.optInt("penColor", prefs.penColor), eraserRadius = json.optDouble("eraserRadius", prefs.eraserRadius.toDouble()).toFloat().coerceIn(8f,54f),
            stylusButtonAction = runCatching { StylusButtonAction.valueOf(json.getString("stylusButtonAction")) }.getOrDefault(prefs.stylusButtonAction),
            eraserMode = runCatching { EraserMode.valueOf(json.getString("eraserMode")) }.getOrDefault(prefs.eraserMode),
            quickPenColors = json.optString("quickPenColors").split(',').mapNotNull {it.toLongOrNull(16)?.toInt()}.takeIf {it.size==4} ?: prefs.quickPenColors
        ))
        penColor = Color(inputPreferences.penColor)
    }
    fun allDocuments(): List<InkBoard> { persistCurrentBoard(); return boards.toList() }

    fun updateInputPreferences(value: InputPreferences) {
        inputPreferences = value
        inputRepository.save(value)
    }

    fun choosePenColor(color: Color) {
        penColor = color
        updateInputPreferences(inputPreferences.copy(penColor = color.toArgb()))
    }

    fun chooseQuickPenColor(index: Int) {
        val value = inputPreferences.quickPenColors.getOrNull(index) ?: return
        choosePenColor(Color(value))
        tool = EditorTool.PEN
    }

    fun updateQuickPenColor(index: Int, color: Color) {
        if (index !in inputPreferences.quickPenColors.indices) return
        val colors = inputPreferences.quickPenColors.toMutableList()
        colors[index] = color.toArgb()
        updateInputPreferences(inputPreferences.copy(quickPenColors = colors, penColor = color.toArgb()))
        penColor = color
        tool = EditorTool.PEN
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
        CanvasInputSource.TOUCH -> null
        CanvasInputSource.MOUSE -> tool
    }

    fun setStylusContact(active: Boolean) {
        stylusInContact = active
        lastStylusTime = android.os.SystemClock.uptimeMillis()
    }
    fun beginInput() { inputSnapshot = snapshot(); inputUndoCount = undo.size }
    fun cancelInput() {
        inputSnapshot?.let { restore(it) }
        while (undo.size > inputUndoCount) undo.removeLast()
        currentPoints.clear(); lassoPoints.clear(); eraserCursor = null
        movingSelection = false; movingConverted = false; eraserGestureChanged = false
        inputSnapshot = null
        setStylusContact(false)
    }
    fun endInput() { inputSnapshot = null }
    val notebook get() = currentBoard?.format == DocumentFormat.NOTEBOOK
    fun pageTop(index: Int): Float = currentBoard?.pages?.take(index)?.sumOf { (it.height + 28f).toDouble() }?.toFloat() ?: 0f
    fun pageLeft(index: Int): Float {
        val pages = currentBoard?.pages ?: return 0f
        return ((pages.maxOfOrNull { it.width } ?: 1000f) - (pages.getOrNull(index)?.width ?: 1000f)) / 2f
    }
    fun pageAt(screen: Offset): Int? {
        if (!notebook) return currentPageIndex
        val world = (screen - viewportOffset) / viewportScale
        return currentBoard?.pages?.indices?.firstOrNull { i ->
            val p = currentBoard!!.pages[i]
            world.x in pageLeft(i)..(pageLeft(i)+p.width) && world.y in pageTop(i)..(pageTop(i)+p.height)
        }
    }
    fun screenToCanvas(point: Offset): Offset {
        val world = (point - viewportOffset) / viewportScale
        val page = currentBoard?.pages?.getOrNull(currentPageIndex)
        return if (notebook && page != null) world - Offset(pageLeft(currentPageIndex), pageTop(currentPageIndex)) + Offset(page.originX, page.originY) else world
    }
    fun canvasToScreen(point: Offset): Offset {
        val page = currentBoard?.pages?.getOrNull(currentPageIndex)
        val world = if (notebook && page != null) point + Offset(pageLeft(currentPageIndex)-page.originX, pageTop(currentPageIndex)-page.originY) else point
        return world * viewportScale + viewportOffset
    }
    fun panBy(delta: Offset) {
        viewportOffset += delta; constrainViewport()
        if (notebook) pageAt(Offset(viewportWidth/2,viewportHeight/2))?.let { activatePage(it) }
    }
    fun flush() { persistCurrentBoard() }
    fun zoomBy(factor: Float, centroid: Offset) {
        val anchor = (centroid - viewportOffset) / viewportScale
        viewportScale = (viewportScale * factor).coerceIn(if (notebook) fitScale() * 0.5f else 0.2f, 6f)
        viewportOffset = centroid - anchor * viewportScale
        constrainViewport()
    }
    private fun fitScale() = ((viewportWidth - 32f).coerceAtLeast(1f) / (currentBoard?.pages?.maxOfOrNull { it.width } ?: 1000f)).coerceAtMost(6f)
    fun resizeViewport(width: Float, height: Float) {
        val first = viewportWidth <= 1f
        val center = (Offset(viewportWidth/2, viewportHeight/2) - viewportOffset) / viewportScale
        viewportWidth = width; viewportHeight = height
        if (first) {
            val board = currentBoard
            if (board != null && board.savedScale > 0) {
                viewportScale = board.savedScale; viewportOffset = Offset(board.savedOffsetX, board.savedOffsetY)
            } else resetViewport()
        } else viewportOffset = Offset(width/2, height/2) - center * viewportScale
        constrainViewport()
    }
    fun resetViewport() {
        viewportScale = if (notebook) fitScale() else 1f
        viewportOffset = Offset.Zero
        if (notebook) scrollToPage(currentPageIndex)
    }
    fun fitPage() {
        val page = currentBoard?.pages?.getOrNull(currentPageIndex) ?: return
        viewportScale = minOf(fitScale(), (viewportHeight-32f).coerceAtLeast(1f)/page.height)
        scrollToPage(currentPageIndex)
    }
    private fun scrollToPage(index: Int) {
        if (!notebook) return
        viewportOffset = Offset(viewportOffset.x, 16f - pageTop(index)*viewportScale)
        constrainViewport()
    }
    private fun constrainViewport() {
        if (!notebook) return
        val pages = currentBoard?.pages ?: return
        val width = (pages.maxOfOrNull { it.width } ?: 1000f)*viewportScale
        val height = (pages.sumOf { (it.height+28f).toDouble() }.toFloat()-28f)*viewportScale
        viewportOffset = Offset(
            if (width < viewportWidth-32f) (viewportWidth-width)/2f else viewportOffset.x.coerceIn(viewportWidth-width-16f, 16f),
            viewportOffset.y.coerceIn(minOf(16f, viewportHeight-height-16f),16f)
        )
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

    fun finishStroke(snapToShape: Boolean = false) {
        if (currentPoints.isNotEmpty()) {
            pushUndo()
            val stroke = InkStroke(points = currentPoints.toList(), width = penWidth, color = penColor)
            val finished = if (inputPreferences.autoShapes && snapToShape) autoRecognizeShape(stroke) else stroke
            val page = currentBoard?.pages?.getOrNull(currentPageIndex)
            strokes += if (notebook && page != null) dev.swart.inklab.core.ink.clipStrokeToPage(finished, Rect(page.originX,page.originY,page.originX+page.width,page.originY+page.height)) else listOf(finished)
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

    fun selectObjectAt(point: Offset) {
        val converted = convertedObjects.asReversed().firstOrNull {
            it.bounds().inflate(14f / viewportScale).contains(point)
        }
        if (converted != null) {
            selectConvertedObject(converted.id)
            return
        }

        val hit = strokes.asReversed().firstOrNull { strokeHitTest(it, point, 12f / viewportScale) }
        if (hit == null) {
            clearSelection()
            return
        }

        val selected = linkedSetOf(hit.id)
        var area = strokeBounds(hit)?.inflate(16f / viewportScale) ?: return
        var changed: Boolean
        do {
            changed = false
            strokes.forEach { stroke ->
                if (stroke.id !in selected) {
                    val bounds = strokeBounds(stroke)?.inflate(10f / viewportScale)
                    if (bounds != null && area.overlaps(bounds)) {
                        selected += stroke.id
                        area = Rect(
                            minOf(area.left, bounds.left), minOf(area.top, bounds.top),
                            maxOf(area.right, bounds.right), maxOf(area.bottom, bounds.bottom)
                        )
                        changed = true
                    }
                }
            }
        } while (changed && selected.size < 80)
        selectedIds = selected
        selectedConvertedId = null
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

    fun beginEditConverted() {
        editingConvertedId = selectedConvertedId
    }

    fun cancelEditConverted() { editingConvertedId = null }

    fun updateConvertedContent(content: String) {
        val id = editingConvertedId ?: return
        val index = convertedObjects.indexOfFirst { it.id == id }
        if (index < 0 || content.isBlank()) return
        pushUndo()
        val item = convertedObjects[index]
        convertedObjects[index] = item.copy(content = content, height = if (item.kind == ConvertedInkKind.TEXT) textHeight(content,item.textSize,item.width) else item.height)
        editingConvertedId = null
        selectedConvertedId = id
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
        if (recognition?.loading == true) return
        val requestBoard = currentBoardId
        val requestPage = currentBoard?.pages?.getOrNull(currentPageIndex)?.id ?: return
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
            }.onSuccess {
                if (currentBoardId != requestBoard || currentBoard?.pages?.getOrNull(currentPageIndex)?.id != requestPage || strokes.filter { it.id in sourceIds } != input) {
                    recognition = UiRecognition(mode, error = "Страница или рукопись изменилась. Повторите распознавание на исходной странице.")
                    return@onSuccess
                }
                recognition = UiRecognition(mode, result = it, sourceIds = sourceIds, documentId = requestBoard, pageId = requestPage, originalStrokes = input)
                if (mode == RecognitionMode.MATH) applyRecognition()
            }.onFailure {
                recognition = UiRecognition(mode, error = it.message ?: "Ошибка распознавания", sourceIds = sourceIds)
            }
        }
    }

    private fun textHeight(content: String, size: Float, width: Float): Float {
        val paint = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = size; typeface = android.graphics.Typeface.create("cursive",android.graphics.Typeface.NORMAL) }
        return android.text.StaticLayout.Builder.obtain(content,0,content.length,paint,width.toInt().coerceAtLeast(1)).setIncludePad(false).setLineSpacing(0f,1.16f).build().height.toFloat().coerceAtLeast(size)
    }
    fun chooseRecognitionCandidate(value: String) {
        recognition = recognition?.let { it.copy(result = it.result?.copy(primary = value)) }
        applyRecognition()
    }
    fun applyRecognition() {
        val state = recognition ?: return
        val result = state.result ?: return
        if (state.documentId != currentBoardId || state.pageId != currentBoard?.pages?.getOrNull(currentPageIndex)?.id || strokes.filter {it.id in state.sourceIds} != state.originalStrokes) {
            recognition = UiRecognition(state.mode, error = "Исходная рукопись изменилась. Повторите распознавание.")
            return
        }
        val source = strokes.filter { it.id in state.sourceIds }
        if (source.isEmpty() || result.primary.isBlank()) return
        val bounds = sourceBounds(source) ?: return
        pushUndo()
        strokes.removeAll { it.id in state.sourceIds }

        val isText = state.mode == RecognitionMode.TEXT
        val textSize = if (isText) (bounds.height * 0.76f).coerceIn(18f, 68f) else (bounds.height * 0.72f).coerceIn(20f, 76f)
        val width = if (isText) bounds.width.coerceAtLeast(textSize * 3f) else bounds.width.coerceAtLeast(textSize * 1.5f)
        val height = if (isText) textHeight(result.primary, textSize, width) else bounds.height.coerceAtLeast(textSize * 1.1f)
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

    fun prepareProvider(context: Context, id: String, wifiOnly: Boolean? = null) {
        val provider = AppContainer.recognitionRegistry.get(id) ?: return
        if (modelProgress.containsKey(id) && wifiOnly == null) return
        val request = (modelRequests[id] ?: 0) + 1
        modelRequests[id] = request
        if (provider is dev.swart.inklab.recognition.mlkit.MlKitDigitalInkProvider) provider.wifiOnly = wifiOnly ?: inputPreferences.wifiOnlyModels
        modelErrors.remove(id)
        modelProgress[id] = 0f
        viewModelScope.launch {
            val result = provider.prepare(context) { progress -> viewModelScope.launch { if(modelRequests[id]==request) modelProgress[id] = progress } }
            if (modelRequests[id] == request) {
                result.onFailure { modelErrors[id] = it.message ?: "Не удалось загрузить модель" }.onSuccess {modelErrors.remove(id)}
                modelProgress.remove(id)
            }
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

    private fun currentDocument(): InkBoard {
        val board = currentBoard ?: return InkBoard()
        val pages = board.pages.toMutableList()
        if (currentPageIndex in pages.indices) pages[currentPageIndex] = pages[currentPageIndex].copy(strokes = strokes.toList(), convertedObjects = convertedObjects.toList())
        return board.copy(pages = pages, lastPageIndex = currentPageIndex)
    }
    private fun snapshot() = DocumentSnapshot(currentDocument(), currentPageIndex)

    private fun restore(snapshot: DocumentSnapshot) {
        val board = currentBoard ?: return
        if (board.id != snapshot.board.id) return
        replaceBoard(board.copy(pages = snapshot.board.pages, trashedPages = snapshot.board.trashedPages))
        loadPage(snapshot.pageIndex.coerceIn(0, snapshot.board.pages.lastIndex))
        clearSelection()
        scheduleSave()
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
        val board = boards[index]
        val pages = board.pages.toMutableList()
        if (currentPageIndex !in pages.indices) return
        pages[currentPageIndex] = pages[currentPageIndex].copy(
            strokes = strokes.toList(), convertedObjects = convertedObjects.toList()
        )
        boards[index] = board.copy(
            pages = pages,
            savedScale = viewportScale, savedOffsetX = viewportOffset.x, savedOffsetY = viewportOffset.y,
            lastPageIndex = currentPageIndex,
            updatedAt = System.currentTimeMillis()
        )
        scheduleSave()
    }

    private fun loadPage(index: Int) {
        val board = currentBoard ?: return
        val page = board.pages.getOrNull(index) ?: return
        val changedPage = index != currentPageIndex
        currentPageIndex = index
        val app = getApplication<Application>()
        val active = dev.swart.inklab.audio.AudioHub.activeId
        if (changedPage && active != null && dev.swart.inklab.audio.AudioStore(app).list().any { it.id == active && it.boardId == board.id }) {
            dev.swart.inklab.audio.recordingCommand(app, "mark", pageId = page.id, title = "Лист ${index + 1}")
        }
        strokes.clear()
        strokes += page.strokes
        convertedObjects.clear()
        convertedObjects += page.convertedObjects
        clearSelection()
        scheduleSave()
    }

    private fun scheduleSave() {
        if (boardRepository.loadError != null) return
        saving = true
        saves.trySend(boards.toList() to folders.toList())
    }

    private fun Rect.inflate(value: Float) = Rect(left - value, top - value, right + value, bottom + value)
}