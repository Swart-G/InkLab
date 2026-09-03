package dev.swart.inklab.core.storage

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import dev.swart.inklab.core.model.BoardSettings
import dev.swart.inklab.core.model.InkBoard
import dev.swart.inklab.core.model.InkPoint
import dev.swart.inklab.core.model.InkStroke
import dev.swart.inklab.core.model.PaperPattern
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class BoardRepository(context: Context) {
    private val file = File(context.filesDir, "boards.json")

    fun load(): List<InkBoard> = runCatching {
        if (!file.exists()) return emptyList()
        val root = JSONArray(file.readText())
        List(root.length()) { index -> root.getJSONObject(index).toBoard() }
    }.getOrDefault(emptyList())

    @Synchronized
    fun save(boards: List<InkBoard>) {
        val root = JSONArray()
        boards.forEach { root.put(it.toJson()) }
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(root.toString())
        check(temporary.renameTo(file) || run {
            temporary.copyTo(file, overwrite = true)
            temporary.delete()
        })
    }

    private fun InkBoard.toJson() = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("subject", subject)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
        put("settings", JSONObject().apply {
            put("pattern", settings.pattern.name)
            put("spacing", settings.spacing.toDouble())
            put("paperColor", settings.paperColor)
            put("showMargin", settings.showMargin)
        })
        put("strokes", JSONArray().apply {
            strokes.forEach { stroke ->
                put(JSONObject().apply {
                    put("id", stroke.id)
                    put("width", stroke.width.toDouble())
                    put("color", stroke.color.toArgb())
                    put("points", JSONArray().apply {
                        stroke.points.forEach { point ->
                            put(JSONArray().apply {
                                put(point.x.toDouble())
                                put(point.y.toDouble())
                                put(point.timestamp)
                                put(point.pressure.toDouble())
                                put(point.tilt.toDouble())
                            })
                        }
                    })
                })
            }
        })
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
        val strokesJson = optJSONArray("strokes") ?: JSONArray()
        val strokes = List(strokesJson.length()) { strokeIndex ->
            val stroke = strokesJson.getJSONObject(strokeIndex)
            val pointsJson = stroke.optJSONArray("points") ?: JSONArray()
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
            InkStroke(
                id = stroke.optString("id", java.util.UUID.randomUUID().toString()),
                points = points,
                width = stroke.optDouble("width", 5.0).toFloat(),
                color = Color(stroke.optInt("color", 0xFF25272C.toInt()))
            )
        }
        return InkBoard(
            id = optString("id", java.util.UUID.randomUUID().toString()),
            title = optString("title", "Без названия"),
            subject = optString("subject", ""),
            createdAt = optLong("createdAt", System.currentTimeMillis()),
            updatedAt = optLong("updatedAt", System.currentTimeMillis()),
            settings = settings,
            strokes = strokes
        )
    }
}

enum class FingerAction { PAN, DRAW, ERASE, IGNORE }
enum class StylusButtonAction { ERASE, LASSO, IGNORE }
enum class EraserMode { PIXEL, STROKE }

data class InputPreferences(
    val fingerAction: FingerAction = FingerAction.PAN,
    val stylusButtonAction: StylusButtonAction = StylusButtonAction.ERASE,
    val eraserMode: EraserMode = EraserMode.PIXEL,
    val eraserRadius: Float = 24f,
    val palmRejection: Boolean = true,
    val pressureEnabled: Boolean = true
)

class InputPreferencesRepository(context: Context) {
    private val preferences = context.getSharedPreferences("input_preferences", Context.MODE_PRIVATE)

    fun load() = InputPreferences(
        fingerAction = preferences.enum("fingerAction", FingerAction.PAN),
        stylusButtonAction = preferences.enum("stylusButtonAction", StylusButtonAction.ERASE),
        eraserMode = preferences.enum("eraserMode", EraserMode.PIXEL),
        eraserRadius = preferences.getFloat("eraserRadius", 24f),
        palmRejection = preferences.getBoolean("palmRejection", true),
        pressureEnabled = preferences.getBoolean("pressureEnabled", true)
    )

    fun save(value: InputPreferences) {
        preferences.edit()
            .putString("fingerAction", value.fingerAction.name)
            .putString("stylusButtonAction", value.stylusButtonAction.name)
            .putString("eraserMode", value.eraserMode.name)
            .putFloat("eraserRadius", value.eraserRadius)
            .putBoolean("palmRejection", value.palmRejection)
            .putBoolean("pressureEnabled", value.pressureEnabled)
            .apply()
    }

    private inline fun <reified T : Enum<T>> android.content.SharedPreferences.enum(key: String, fallback: T): T =
        runCatching { enumValueOf<T>(getString(key, fallback.name) ?: fallback.name) }.getOrDefault(fallback)
}
