package dev.swart.inklab.core.storage

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import dev.swart.inklab.core.model.BoardSettings
import dev.swart.inklab.core.model.ConvertedInkKind
import dev.swart.inklab.core.model.ConvertedInkObject
import dev.swart.inklab.core.model.DocumentFormat
import dev.swart.inklab.core.model.InkBoard
import dev.swart.inklab.core.model.InkFolder
import dev.swart.inklab.core.model.InkPage
import dev.swart.inklab.core.model.InkPoint
import dev.swart.inklab.core.model.InkStroke
import dev.swart.inklab.core.model.PaperPattern
import dev.swart.inklab.core.model.PageOrientation
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class LocalSaveReceipt(
    val sequence: Long,
    val committedAt: Long
)

class BoardRepository(context: Context) {
    private val filesDir = context.filesDir
    private val transactionalStore = TransactionalLibraryStore(File(filesDir, "library-store-v1"))
    private val directory = File(filesDir, "documents")
    private val migrationBackup = File(filesDir, "migration-local-store-v1")
    private val file = File(filesDir, "boards.json")
    private val backupFile = File(filesDir, "boards.json.bak")
    private val foldersFile = File(filesDir, "folders.json")
    private val foldersBackupFile = File(filesDir, "folders.json.bak")

    private var cachedBoards: List<InkBoard> = emptyList()
    private var cachedFolders: List<InkFolder> = emptyList()
    private var transactionalLoaded = false
    private var legacyFoldersLoaded = false

    var loadError: String? = null
        private set
    var lastCommittedSequence: Long = 0L
        private set

    fun encode(boards: List<InkBoard>): String = JSONArray().apply { boards.forEach { put(it.toJson()) } }.toString()

    fun decode(value: String): List<InkBoard> = JSONArray(value).let { root ->
        List(root.length()) { root.getJSONObject(it).toBoard() }
    }.also(::validateBoards)

    fun allowRecovery() {
        if (loadError == null) return
        val recovery = File(filesDir, "recovery-${System.currentTimeMillis()}").apply { mkdirs() }
        if (directory.exists()) directory.copyRecursively(File(recovery, "documents"))
        transactionalStore.copyForRecovery(File(recovery, "library-store-v1"))
        listOf(file, backupFile, foldersFile, foldersBackupFile).filter { it.exists() }.forEach {
            it.copyTo(File(recovery, it.name), overwrite = false)
        }
        loadError = null
    }

    @Synchronized
    fun load(): List<InkBoard> {
        if (transactionalStore.hasPublishedData()) return loadTransactional().first

        val separate = loadSeparateDocuments()
        val boards = separate ?: loadLegacyBoardsArray()
        cachedBoards = boards
        if (!legacyFoldersLoaded) {
            cachedFolders = loadLegacyFoldersInternal()
            legacyFoldersLoaded = true
        }
        if (boards.isNotEmpty() && loadError == null) {
            backupLegacyStorage()
            runCatching { saveLibrary(boards, cachedFolders) }
                .onFailure { loadError = "Не удалось завершить миграцию локального хранилища: ${it.message}" }
        }
        return boards
    }

    @Synchronized
    fun loadFolders(): List<InkFolder> {
        if (transactionalStore.hasPublishedData()) return loadTransactional().second
        if (!legacyFoldersLoaded) {
            cachedFolders = loadLegacyFoldersInternal()
            legacyFoldersLoaded = true
        }
        return cachedFolders
    }

    /** Publishes documents and folder metadata as one durable generation. */
    @Synchronized
    fun saveLibrary(boards: List<InkBoard>, folders: List<InkFolder>): LocalSaveReceipt {
        check(loadError == null) { loadError.orEmpty() }
        validateBoards(boards)
        validateFolders(folders)
        val documents = LinkedHashMap<String, String>(boards.size)
        boards.forEach { board -> documents[board.id] = board.toJson().toString() }
        val commit = transactionalStore.commit(documents, foldersToJson(folders).toString())
        cachedBoards = boards.toList()
        cachedFolders = folders.toList()
        transactionalLoaded = true
        lastCommittedSequence = commit.sequence
        return LocalSaveReceipt(commit.sequence, commit.committedAt)
    }

    /** Compatibility entry point. Prefer saveLibrary() so folder metadata shares the same generation. */
    @Synchronized
    fun save(boards: List<InkBoard>): LocalSaveReceipt = saveLibrary(boards, cachedFolders)

    /** Compatibility entry point. Prefer saveLibrary() so documents share the same generation. */
    @Synchronized
    fun saveFolders(folders: List<InkFolder>): LocalSaveReceipt = saveLibrary(cachedBoards, folders)

    private fun loadTransactional(): Pair<List<InkBoard>, List<InkFolder>> {
        if (transactionalLoaded) return cachedBoards to cachedFolders
        val snapshot = runCatching { transactionalStore.load() }
            .getOrElse {
                loadError = "Локальное хранилище повреждено. Исходные generation сохранены: ${it.message}"
                return emptyList<InkBoard>() to emptyList()
            } ?: return emptyList<InkBoard>() to emptyList()
        val boards = runCatching {
            snapshot.documents.values.map { JSONObject(it).toBoard() }.also(::validateBoards)
        }.getOrElse {
            loadError = "Документ имеет повреждённую или более новую схему. Запись заблокирована: ${it.message}"
            return emptyList<InkBoard>() to emptyList()
        }
        val folders = runCatching { parseFolders(JSONArray(snapshot.foldersJson)) }
            .getOrElse {
                loadError = "Не удалось прочитать метаданные папок: ${it.message}"
                return boards to emptyList()
            }
        cachedBoards = boards
        cachedFolders = folders
        lastCommittedSequence = snapshot.sequence
        transactionalLoaded = true
        return boards to folders
    }

    private fun loadSeparateDocuments(): List<InkBoard>? {
        val primary = File(directory, "index.json")
        val backup = File(directory, "index.json.bak")
        if (!primary.exists() && !backup.exists()) return null
        var indexFailure: Throwable? = null
        for (candidate in listOf(primary, backup)) {
            if (!candidate.isFile) continue
            val ids = runCatching {
                val index = JSONArray(candidate.readText())
                List(index.length()) { i -> index.getString(i).also { require(safeId(it)) } }
            }.onFailure { indexFailure = it }.getOrNull() ?: continue
            val result = ArrayList<InkBoard>(ids.size)
            val damaged = mutableListOf<String>()
            ids.forEach { id ->
                val target = File(directory, "$id.json")
                val board = listOf(target, File(directory, "$id.json.bak"))
                    .firstNotNullOfOrNull { source ->
                        if (!source.isFile) null else runCatching { JSONObject(source.readText()).toBoard() }.getOrNull()
                    }
                if (board == null) damaged += id else result += board
            }
            if (damaged.isNotEmpty()) {
                loadError = "Не удалось прочитать документы: ${damaged.joinToString()}. Остальные документы доступны; запись заблокирована до восстановления."
            }
            return result
        }
        loadError = "Не удалось прочитать index.json. Исходные файлы сохранены: ${indexFailure?.message.orEmpty()}"
        return emptyList()
    }

    private fun loadLegacyBoardsArray(): List<InkBoard> {
        if (!file.exists() && !backupFile.exists()) return emptyList()
        var failure: Throwable? = null
        for (candidate in listOf(file, backupFile)) {
            if (!candidate.isFile) continue
            val parsed = runCatching {
                val root = JSONArray(candidate.readText())
                List(root.length()) { root.getJSONObject(it).toBoard() }.also(::validateBoards)
            }.onFailure { failure = it }.getOrNull()
            if (parsed != null) return parsed
        }
        loadError = "Не удалось прочитать boards.json. Исходные файлы сохранены: ${failure?.message.orEmpty()}"
        return emptyList()
    }

    private fun loadLegacyFoldersInternal(): List<InkFolder> {
        if (!foldersFile.exists() && !foldersBackupFile.exists()) return emptyList()
        var failure: Throwable? = null
        for (candidate in listOf(foldersFile, foldersBackupFile)) {
            if (!candidate.isFile) continue
            val parsed = runCatching { parseFolders(JSONArray(candidate.readText())) }
                .onFailure { failure = it }
                .getOrNull()
            if (parsed != null) return parsed
        }
        loadError = loadError ?: "Не удалось прочитать folders.json. Исходные файлы сохранены: ${failure?.message.orEmpty()}"
        return emptyList()
    }

    private fun backupLegacyStorage() {
        if (migrationBackup.exists()) return
        migrationBackup.mkdirs()
        if (directory.exists()) directory.copyRecursively(File(migrationBackup, "documents"), overwrite = false)
        listOf(file, backupFile, foldersFile, foldersBackupFile).filter { it.exists() }.forEach {
            it.copyTo(File(migrationBackup, it.name), overwrite = false)
        }
    }

    private fun foldersToJson(folders: List<InkFolder>) = JSONArray().apply {
        folders.forEach { folder ->
            put(JSONObject().apply {
                put("id", folder.id)
                put("title", folder.title)
                put("parentId", folder.parentId ?: JSONObject.NULL)
                put("createdAt", folder.createdAt)
                put("updatedAt", folder.updatedAt)
            })
        }
    }

    private fun parseFolders(root: JSONArray): List<InkFolder> = List(root.length()) { index ->
        val item = root.getJSONObject(index)
        InkFolder(
            id = item.optString("id", UUID.randomUUID().toString()),
            title = item.optString("title", "Новая папка"),
            parentId = item.optString("parentId", "").takeIf { it.isNotBlank() && it != "null" },
            createdAt = item.optLong("createdAt", System.currentTimeMillis()),
            updatedAt = item.optLong("updatedAt", System.currentTimeMillis())
        )
    }.also(::validateFolders)

    private fun validateBoards(boards: List<InkBoard>) {
        require(boards.map { it.id }.distinct().size == boards.size) { "Повторяющийся documentId" }
        boards.forEach { board ->
            require(safeId(board.id)) { "Недопустимый documentId" }
            require(board.pages.isNotEmpty()) { "Документ не содержит страниц" }
            val pageIds = (board.pages + board.trashedPages).map { it.id }
            require(pageIds.distinct().size == pageIds.size) { "Повторяющийся pageId" }
        }
    }

    private fun validateFolders(folders: List<InkFolder>) {
        require(folders.map { it.id }.distinct().size == folders.size) { "Повторяющийся folderId" }
        folders.forEach { require(safeId(it.id)) { "Недопустимый folderId" } }
        val ids = folders.mapTo(mutableSetOf()) { it.id }
        folders.forEach { folder -> require(folder.parentId == null || folder.parentId in ids) { "Папка ссылается на отсутствующего родителя" } }
        folders.forEach { start ->
            var current: InkFolder? = start
            val seen = mutableSetOf<String>()
            while (current != null) {
                require(seen.add(current.id)) { "Цикл папок" }
                val parent = current.parentId
                current = folders.firstOrNull { it.id == parent }
            }
        }
    }

    private fun InkBoard.toJson() = JSONObject().apply {
        put("schemaVersion", BOARD_SCHEMA_VERSION)
        put("languageTag", languageTag)
        put("favorite", favorite)
        put("deletedAt", deletedAt ?: JSONObject.NULL)
        put("savedScale", savedScale.toDouble())
        put("savedOffsetX", savedOffsetX.toDouble())
        put("savedOffsetY", savedOffsetY.toDouble())
        put("id", id)
        put("title", title)
        put("subject", subject)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
        put("format", format.name)
        put("orientation", orientation.name)
        put("lastPageIndex", lastPageIndex)
        put("folderId", folderId ?: JSONObject.NULL)
        put("settings", JSONObject().apply {
            put("pattern", settings.pattern.name)
            put("spacing", settings.spacing.toDouble())
            put("paperColor", settings.paperColor)
            put("showMargin", settings.showMargin)
        })
        put("pages", pagesJson(pages))
        put("trashedPages", pagesJson(trashedPages))
    }

    private fun pagesJson(pages: List<InkPage>) = JSONArray().apply {
        pages.forEach { page -> put(JSONObject().apply {
            put("id", page.id)
            put("width", page.width.toDouble())
            put("height", page.height.toDouble())
            put("originX", page.originX.toDouble())
            put("originY", page.originY.toDouble())
            put("strokes", JSONArray().apply { page.strokes.forEach { put(it.toJson()) } })
            put("convertedObjects", page.convertedObjects.toJson())
        }) }
    }

    private fun List<ConvertedInkObject>.toJson() = JSONArray().apply {
        this@toJson.forEach { item ->
            put(JSONObject().apply {
                put("id", item.id)
                put("kind", item.kind.name)
                put("content", item.content)
                put("x", item.x.toDouble())
                put("y", item.y.toDouble())
                put("width", item.width.toDouble())
                put("height", item.height.toDouble())
                put("textSize", item.textSize.toDouble())
                put("color", item.color.toArgb())
                put("providerId", item.providerId)
                put("sourceStrokes", JSONArray().apply { item.sourceStrokes.forEach { put(it.toJson()) } })
            })
        }
    }

    private fun InkStroke.toJson() = JSONObject().apply {
        put("id", id)
        put("width", width.toDouble())
        put("color", color.toArgb())
        put("points", JSONArray().apply {
            points.forEach { point ->
                put(JSONArray().apply {
                    put(point.x.toDouble())
                    put(point.y.toDouble())
                    put(point.timestamp)
                    put(point.pressure.toDouble())
                    put(point.tilt.toDouble())
                })
            }
        })
    }

    private fun JSONObject.toStroke(): InkStroke {
        val pointsJson = optJSONArray("points") ?: JSONArray()
        val points = List(pointsJson.length()) { pointIndex ->
            val point = pointsJson.getJSONArray(pointIndex)
            InkPoint(
                x = point.getDouble(0).toFloat(),
                y = point.getDouble(1).toFloat(),
                timestamp = point.optLong(2, 0L),
                pressure = point.optDouble(3, 1.0).toFloat(),
                tilt = point.optDouble(4, 0.0).toFloat()
            )
        }
        return InkStroke(
            id = optString("id", UUID.randomUUID().toString()),
            points = points,
            width = optDouble("width", 5.0).toFloat(),
            color = Color(optInt("color", 0xFF25272C.toInt()))
        )
    }

    private fun JSONObject.toBoard(): InkBoard {
        val schemaVersion = optInt("schemaVersion", 1)
        require(schemaVersion in 1..BOARD_SCHEMA_VERSION) {
            "Схема документа $schemaVersion новее поддерживаемой $BOARD_SCHEMA_VERSION"
        }
        val settingsJson = optJSONObject("settings") ?: JSONObject()
        val settings = BoardSettings(
            pattern = runCatching { PaperPattern.valueOf(settingsJson.optString("pattern", PaperPattern.RULED.name)) }
                .getOrDefault(PaperPattern.RULED),
            spacing = settingsJson.optDouble("spacing", 36.0).toFloat(),
            paperColor = settingsJson.optLong("paperColor", 0xFFFBF9F5),
            showMargin = settingsJson.optBoolean("showMargin", false)
        )
        fun parseConverted(convertedJson: JSONArray): List<ConvertedInkObject> = List(convertedJson.length()) { itemIndex ->
            val item = convertedJson.getJSONObject(itemIndex)
            val sourceJson = item.optJSONArray("sourceStrokes") ?: JSONArray()
            ConvertedInkObject(
                id = item.optString("id", UUID.randomUUID().toString()),
                kind = runCatching { ConvertedInkKind.valueOf(item.optString("kind", ConvertedInkKind.TEXT.name)) }
                    .getOrDefault(ConvertedInkKind.TEXT),
                content = item.optString("content", ""),
                x = item.optDouble("x", 0.0).toFloat(),
                y = item.optDouble("y", 0.0).toFloat(),
                width = item.optDouble("width", 160.0).toFloat(),
                height = item.optDouble("height", 48.0).toFloat(),
                textSize = item.optDouble("textSize", 32.0).toFloat(),
                color = Color(item.optInt("color", 0xFF25272C.toInt())),
                sourceStrokes = List(sourceJson.length()) { sourceIndex -> sourceJson.getJSONObject(sourceIndex).toStroke() },
                providerId = item.optString("providerId", "")
            )
        }

        fun parsePage(page: JSONObject): InkPage {
            val strokesJson = page.optJSONArray("strokes") ?: JSONArray()
            val strokes = List(strokesJson.length()) { strokesJson.getJSONObject(it).toStroke() }
            val objects = parseConverted(page.optJSONArray("convertedObjects") ?: JSONArray())
            val points = (strokes + objects.flatMap { it.sourceStrokes }).flatMap { it.points }
            val left = minOf(0f, points.minOfOrNull { it.x } ?: 0f, objects.minOfOrNull { it.x } ?: 0f)
            val top = minOf(0f, points.minOfOrNull { it.y } ?: 0f, objects.minOfOrNull { it.y } ?: 0f)
            val right = maxOf(950f, points.maxOfOrNull { it.x + 20f } ?: 0f, objects.maxOfOrNull { it.x + it.width } ?: 0f)
            val bottom = maxOf(0f, points.maxOfOrNull { it.y + 20f } ?: 0f, objects.maxOfOrNull { it.y + it.height } ?: 0f)
            val ratio = if (optString("orientation") == "LANDSCAPE") 1.414f else 1f / 1.414f
            val width = maxOf((right - left) * 1.05f, (bottom - top) * 1.05f * ratio)
            return InkPage(
                id = page.optString("id", UUID.randomUUID().toString()),
                strokes = strokes,
                convertedObjects = objects,
                width = page.optDouble("width", width.toDouble()).toFloat().coerceAtLeast(1f),
                height = page.optDouble("height", (width / ratio).toDouble()).toFloat().coerceAtLeast(1f),
                originX = page.optDouble("originX", (left - (right - left) * 0.025f).toDouble()).toFloat(),
                originY = page.optDouble("originY", (top - (bottom - top) * 0.025f).toDouble()).toFloat()
            )
        }

        val pagesJson = optJSONArray("pages")
        val pages = if (pagesJson != null && pagesJson.length() > 0) {
            List(pagesJson.length()) { parsePage(pagesJson.getJSONObject(it)) }
        } else listOf(parsePage(this))
        val trashed = optJSONArray("trashedPages")?.let { arr -> List(arr.length()) { parsePage(arr.getJSONObject(it)) } } ?: emptyList()
        (pages + trashed).forEach { page ->
            require(page.width.isFinite() && page.height.isFinite() && page.width in 1f..1_000_000f && page.height in 1f..1_000_000f)
            require(page.originX.isFinite() && page.originY.isFinite())
            (page.strokes + page.convertedObjects.flatMap { it.sourceStrokes }).forEach { stroke ->
                require(stroke.width.isFinite() && stroke.width > 0f)
                require(stroke.points.all { it.x.isFinite() && it.y.isFinite() && it.pressure.isFinite() && it.tilt.isFinite() })
            }
            page.convertedObjects.forEach {
                require(listOf(it.x, it.y, it.width, it.height, it.textSize).all(Float::isFinite) && it.width > 0 && it.height > 0 && it.textSize > 0)
            }
        }

        return InkBoard(
            id = optString("id", UUID.randomUUID().toString()),
            title = optString("title", "Без названия"),
            subject = optString("subject", ""),
            createdAt = optLong("createdAt", System.currentTimeMillis()),
            updatedAt = optLong("updatedAt", System.currentTimeMillis()),
            format = runCatching { DocumentFormat.valueOf(optString("format", DocumentFormat.BOARD.name)) }
                .getOrDefault(DocumentFormat.BOARD),
            orientation = runCatching { PageOrientation.valueOf(optString("orientation", PageOrientation.PORTRAIT.name)) }
                .getOrDefault(PageOrientation.PORTRAIT),
            settings = settings,
            pages = pages,
            lastPageIndex = optInt("lastPageIndex", 0).coerceIn(0, pages.lastIndex),
            languageTag = optString("languageTag", "ru-RU"),
            favorite = optBoolean("favorite", false),
            deletedAt = if (isNull("deletedAt")) null else optLong("deletedAt"),
            trashedPages = trashed,
            savedScale = optDouble("savedScale", 0.0).toFloat(),
            savedOffsetX = optDouble("savedOffsetX", 0.0).toFloat(),
            savedOffsetY = optDouble("savedOffsetY", 0.0).toFloat(),
            folderId = optString("folderId", "").takeIf { it.isNotBlank() && it != "null" }
        )
    }

    private fun safeId(value: String) = value.matches(Regex("[a-zA-Z0-9-]+"))

    companion object {
        private const val BOARD_SCHEMA_VERSION = 2
    }
}

enum class FingerAction { PAN, DRAW, ERASE, IGNORE }
enum class StylusButtonAction { ERASE, LASSO, IGNORE }
enum class EraserMode { PIXEL, STROKE }

private val defaultQuickPenColors = listOf(
    0xFF25272C.toInt(),
    0xFF246BCE.toInt(),
    0xFFE05A47.toInt(),
    0xFF0D8B65.toInt()
)

data class InputPreferences(
    val fingerAction: FingerAction = FingerAction.PAN,
    val stylusButtonAction: StylusButtonAction = StylusButtonAction.ERASE,
    val eraserMode: EraserMode = EraserMode.PIXEL,
    val eraserRadius: Float = 24f,
    val palmRejection: Boolean = true,
    val pressureEnabled: Boolean = true,
    val autoShapes: Boolean = true,
    val penColor: Int = 0xFF25272C.toInt(),
    val quickPenColors: List<Int> = defaultQuickPenColors,
    val darkTheme: Boolean = false,
    val systemTheme: Boolean = false,
    val nightPaper: Boolean = true,
    val twoFingerUndo: Boolean = true,
    val wifiOnlyModels: Boolean = true
)

class InputPreferencesRepository(context: Context) {
    private val preferences = context.getSharedPreferences("input_preferences", Context.MODE_PRIVATE)

    fun load() = InputPreferences(
        fingerAction = preferences.enum("fingerAction", FingerAction.PAN),
        stylusButtonAction = preferences.enum("stylusButtonAction", StylusButtonAction.ERASE),
        eraserMode = preferences.enum("eraserMode", EraserMode.PIXEL),
        eraserRadius = preferences.getFloat("eraserRadius", 24f),
        palmRejection = preferences.getBoolean("palmRejection", true),
        pressureEnabled = preferences.getBoolean("pressureEnabled", true),
        autoShapes = preferences.getBoolean("autoShapes", true),
        penColor = preferences.getInt("penColor", 0xFF25272C.toInt()),
        quickPenColors = preferences.getString("quickPenColors", null)
            ?.split(',')
            ?.mapNotNull { token -> token.toLongOrNull(16)?.toInt() }
            ?.takeIf { it.size == 4 }
            ?.mapIndexed { index, color -> if (index == 0) 0xFF25272C.toInt() else color }
            ?: defaultQuickPenColors,
        darkTheme = preferences.getBoolean("darkTheme", false),
        systemTheme = preferences.getBoolean("systemTheme", false),
        nightPaper = preferences.getBoolean("nightPaper", true),
        twoFingerUndo = preferences.getBoolean("twoFingerUndo", true),
        wifiOnlyModels = preferences.getBoolean("wifiOnlyModels", true)
    )

    fun save(value: InputPreferences) {
        preferences.edit()
            .putString("fingerAction", value.fingerAction.name)
            .putString("stylusButtonAction", value.stylusButtonAction.name)
            .putString("eraserMode", value.eraserMode.name)
            .putFloat("eraserRadius", value.eraserRadius)
            .putBoolean("palmRejection", value.palmRejection)
            .putBoolean("pressureEnabled", value.pressureEnabled)
            .putBoolean("autoShapes", value.autoShapes)
            .putInt("penColor", value.penColor)
            .putString("quickPenColors", value.quickPenColors.joinToString(",") { Integer.toUnsignedString(it, 16) })
            .putBoolean("systemTheme", value.systemTheme)
            .putBoolean("nightPaper", value.nightPaper)
            .putBoolean("twoFingerUndo", value.twoFingerUndo)
            .putBoolean("wifiOnlyModels", value.wifiOnlyModels)
            .putBoolean("darkTheme", value.darkTheme)
            .apply()
    }

    private inline fun <reified T : Enum<T>> android.content.SharedPreferences.enum(key: String, fallback: T): T =
        runCatching { enumValueOf<T>(getString(key, fallback.name) ?: fallback.name) }.getOrDefault(fallback)
}
