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

class BoardRepository(context: Context) {
    private val savedDocuments = mutableMapOf<String, InkBoard>()
    private val directory = File(context.filesDir, "documents")
    private val migrationBackup = File(context.filesDir, "migration-v1")
    var loadError: String? = null
        private set

    fun encode(boards: List<InkBoard>): String = JSONArray().apply { boards.forEach { put(it.toJson()) } }.toString()
    fun decode(value: String): List<InkBoard> = JSONArray(value).let { root ->
        List(root.length()) { root.getJSONObject(it).toBoard() }
    }

    private val file = File(context.filesDir, "boards.json")
    private val backupFile = File(context.filesDir, "boards.json.bak")
    private val foldersFile = File(context.filesDir, "folders.json")
    private val foldersBackupFile = File(context.filesDir, "folders.json.bak")

    fun allowRecovery() {
        if (loadError != null) {
            val recovery = File(directory.parentFile, "recovery-${System.currentTimeMillis()}").apply { mkdirs() }
            if (directory.exists()) directory.copyRecursively(File(recovery, "documents"))
            listOf(file, backupFile, foldersFile, foldersBackupFile).filter { it.exists() }.forEach { it.copyTo(File(recovery,it.name)) }
            loadError = null
        }
    }

    fun load(): List<InkBoard> {
        if (directory.isDirectory && (File(directory, "index.json").exists() || File(directory, "index.json.bak").exists())) {
            return loadArray(File(directory, "index.json"), File(directory, "index.json.bak")) { index ->
                List(index.length()) { i ->
                    val name = index.getString(i)
                    require(name.matches(Regex("[a-zA-Z0-9-]+")))
                    val target = File(directory, "$name.json")
                    runCatching { JSONObject(target.readText()).toBoard() }.getOrElse {
                        JSONObject(File(directory, "$name.json.bak").readText()).toBoard()
                    }
                }
            }
        }
        val old = loadArray(file, backupFile) { root -> List(root.length()) { root.getJSONObject(it).toBoard() } }
        if (file.exists() && loadError == null) {
            migrationBackup.mkdirs()
            listOf(file, backupFile, foldersFile, foldersBackupFile).filter { it.exists() }.forEach {
                val destination = File(migrationBackup, it.name)
                if (!destination.exists()) it.copyTo(destination)
            }
        }
        return old
    }

    fun loadFolders(): List<InkFolder> = loadArray(foldersFile, foldersBackupFile) { root ->
        List(root.length()) { index ->
            val item = root.getJSONObject(index)
            InkFolder(
                id = item.optString("id", UUID.randomUUID().toString()),
                title = item.optString("title", "Новая папка"),
                parentId = item.optString("parentId", "").takeIf { it.isNotBlank() },
                createdAt = item.optLong("createdAt", System.currentTimeMillis()),
                updatedAt = item.optLong("updatedAt", System.currentTimeMillis())
            )
        }
    }

    private fun <T> loadArray(primary: File, backup: File, parser: (JSONArray) -> List<T>): List<T> {
        for (candidate in listOf(primary, backup)) {
            if (!candidate.isFile) continue
            val parsed = runCatching { parser(JSONArray(candidate.readText())) }.getOrNull()
            if (parsed != null) return parsed
        }
        if (primary.exists() || backup.exists()) loadError = "Не удалось прочитать ${primary.name}. Исходные файлы сохранены. Восстановите резервную копию."
        return emptyList()
    }

    @Synchronized
    fun save(boards: List<InkBoard>) {
        check(loadError == null) { loadError.orEmpty() }
        directory.mkdirs()
        boards.forEach { board ->
            if (savedDocuments[board.id] === board) return@forEach
            require(board.id.matches(Regex("[a-zA-Z0-9-]+")))
            val target = File(directory, "${board.id}.json")
            val content = board.toJson().toString()
            if (!target.exists() || target.readText() != content) writeAtomic(target, content)
            savedDocuments[board.id] = board
        }
        writeAtomic(File(directory, "index.json"), JSONArray(boards.map { it.id }).toString())
        val retained = boards.map {it.id}.toSet()
        directory.listFiles()?.filter { it.extension == "json" && it.name != "index.json" && it.nameWithoutExtension !in retained }?.forEach {
            it.delete(); File(directory,"${it.name}.bak").delete(); savedDocuments.remove(it.nameWithoutExtension)
        }
    }

    @Synchronized
    fun saveFolders(folders: List<InkFolder>) {
        val root = JSONArray()
        folders.forEach { folder ->
            root.put(JSONObject().apply {
                put("id", folder.id)
                put("title", folder.title)
                put("parentId", folder.parentId ?: JSONObject.NULL)
                put("createdAt", folder.createdAt)
                put("updatedAt", folder.updatedAt)
            })
        }
        writeAtomic(foldersFile, root.toString())
    }

    private fun writeAtomic(target: File, content: String) {
        target.parentFile?.mkdirs()
        val atomic = android.util.AtomicFile(target)
        val output = atomic.startWrite()
        try {
            output.write(content.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (error: Throwable) {
            atomic.failWrite(output)
            throw error
        }
        target.copyTo(File(target.parentFile, "${target.name}.bak"), overwrite = true)
    }

    private fun InkBoard.toJson() = JSONObject().apply {
        put("schemaVersion", 2)
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
            put("width", page.width.toDouble()); put("height", page.height.toDouble())
            put("originX", page.originX.toDouble()); put("originY", page.originY.toDouble())
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
                id = page.optString("id", UUID.randomUUID().toString()), strokes = strokes, convertedObjects = objects,
                width = page.optDouble("width", width.toDouble()).toFloat().coerceAtLeast(1f),
                height = page.optDouble("height", (width / ratio).toDouble()).toFloat().coerceAtLeast(1f),
                originX = page.optDouble("originX", (left - (right-left)*0.025f).toDouble()).toFloat(),
                originY = page.optDouble("originY", (top - (bottom-top)*0.025f).toDouble()).toFloat()
            )
        }
        val pagesJson = optJSONArray("pages")
        val pages = if (pagesJson != null && pagesJson.length() > 0) {
            List(pagesJson.length()) { parsePage(pagesJson.getJSONObject(it)) }
        } else listOf(parsePage(this))
        (pages + (optJSONArray("trashedPages")?.let { arr -> List(arr.length()) { parsePage(arr.getJSONObject(it)) } } ?: emptyList())).forEach { page ->
            require(page.width.isFinite() && page.height.isFinite() && page.width in 1f..1000000f && page.height in 1f..1000000f)
            require(page.originX.isFinite() && page.originY.isFinite())
            (page.strokes + page.convertedObjects.flatMap { it.sourceStrokes }).forEach { stroke ->
                require(stroke.width.isFinite() && stroke.width > 0f)
                require(stroke.points.all { it.x.isFinite() && it.y.isFinite() && it.pressure.isFinite() })
            }
            page.convertedObjects.forEach { require(listOf(it.x,it.y,it.width,it.height,it.textSize).all(Float::isFinite) && it.width > 0 && it.height > 0 && it.textSize > 0) }
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
            trashedPages = optJSONArray("trashedPages")?.let { arr -> List(arr.length()) { parsePage(arr.getJSONObject(it)) } } ?: emptyList(),
            savedScale = optDouble("savedScale", 0.0).toFloat(),
            savedOffsetX = optDouble("savedOffsetX", 0.0).toFloat(),
            savedOffsetY = optDouble("savedOffsetY", 0.0).toFloat(),
            folderId = optString("folderId", "").takeIf { it.isNotBlank() && it != "null" }
        )
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
