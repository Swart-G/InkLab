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
    private val file = File(context.filesDir, "boards.json")
    private val backupFile = File(context.filesDir, "boards.json.bak")
    private val foldersFile = File(context.filesDir, "folders.json")
    private val foldersBackupFile = File(context.filesDir, "folders.json.bak")

    fun load(): List<InkBoard> = loadArray(file, backupFile) { root ->
        List(root.length()) { index -> root.getJSONObject(index).toBoard() }
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
        return emptyList()
    }

    @Synchronized
    fun save(boards: List<InkBoard>) {
        val root = JSONArray()
        boards.forEach { root.put(it.toJson()) }
        writeAtomic(file, root.toString())
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
        val temporary = File(target.parentFile, "${target.name}.tmp")
        temporary.writeText(content)
        if (!temporary.renameTo(target)) {
            temporary.copyTo(target, overwrite = true)
            check(temporary.delete() || !temporary.exists()) { "Could not remove temporary ${temporary.name}" }
        }
        val backup = File(target.parentFile, "${target.name}.bak")
        runCatching { target.copyTo(backup, overwrite = true) }
    }

    private fun InkBoard.toJson() = JSONObject().apply {
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
        put("pages", JSONArray().apply {
            pages.forEach { page ->
                put(JSONObject().apply {
                    put("id", page.id)
                    put("strokes", JSONArray().apply { page.strokes.forEach { put(it.toJson()) } })
                    put("convertedObjects", page.convertedObjects.toJson())
                })
            }
        })
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

        val pagesJson = optJSONArray("pages")
        val pages = if (pagesJson != null && pagesJson.length() > 0) {
            List(pagesJson.length()) { pageIndex ->
                val page = pagesJson.getJSONObject(pageIndex)
                val strokesJson = page.optJSONArray("strokes") ?: JSONArray()
                InkPage(
                    id = page.optString("id", UUID.randomUUID().toString()),
                    strokes = List(strokesJson.length()) { strokesJson.getJSONObject(it).toStroke() },
                    convertedObjects = parseConverted(page.optJSONArray("convertedObjects") ?: JSONArray())
                )
            }
        } else {
            val strokesJson = optJSONArray("strokes") ?: JSONArray()
            listOf(
                InkPage(
                    strokes = List(strokesJson.length()) { strokesJson.getJSONObject(it).toStroke() },
                    convertedObjects = parseConverted(optJSONArray("convertedObjects") ?: JSONArray())
                )
            )
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
            folderId = optString("folderId", "").takeIf { it.isNotBlank() }
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
    val darkTheme: Boolean = false
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
        darkTheme = preferences.getBoolean("darkTheme", false)
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
            .putBoolean("darkTheme", value.darkTheme)
            .apply()
    }

    private inline fun <reified T : Enum<T>> android.content.SharedPreferences.enum(key: String, fallback: T): T =
        runCatching { enumValueOf<T>(getString(key, fallback.name) ?: fallback.name) }.getOrDefault(fallback)
}
