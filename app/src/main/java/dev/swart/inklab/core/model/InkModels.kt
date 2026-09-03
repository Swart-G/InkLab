package dev.swart.inklab.core.model

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import java.util.UUID

data class InkPoint(
    val x: Float,
    val y: Float,
    val timestamp: Long,
    val pressure: Float = 1f,
    val tilt: Float = 0f
) {
    fun offset() = Offset(x, y)
}

data class InkStroke(
    val id: String = UUID.randomUUID().toString(),
    val points: List<InkPoint>,
    val width: Float = 5f,
    val color: Color = Color(0xFF25272C)
)

enum class ConvertedInkKind { TEXT, MATH }

enum class DocumentFormat { BOARD, NOTEBOOK }

enum class PageOrientation { PORTRAIT, LANDSCAPE }

data class ConvertedInkObject(
    val id: String = UUID.randomUUID().toString(),
    val kind: ConvertedInkKind,
    val content: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val textSize: Float,
    val color: Color = Color(0xFF25272C),
    val sourceStrokes: List<InkStroke>,
    val providerId: String = ""
) {
    fun bounds() = Rect(x, y, x + width, y + height)
}

enum class PaperPattern { RULED, GRID, DOTS, BLANK }

data class BoardSettings(
    val pattern: PaperPattern = PaperPattern.RULED,
    val spacing: Float = 36f,
    val paperColor: Long = 0xFFFBF9F5,
    val showMargin: Boolean = false
)

data class InkPage(
    val id: String = UUID.randomUUID().toString(),
    val strokes: List<InkStroke> = emptyList(),
    val convertedObjects: List<ConvertedInkObject> = emptyList()
)

data class InkBoard(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "Новая доска",
    val subject: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val format: DocumentFormat = DocumentFormat.BOARD,
    val orientation: PageOrientation = PageOrientation.PORTRAIT,
    val settings: BoardSettings = BoardSettings(),
    val pages: List<InkPage> = listOf(InkPage()),
    val lastPageIndex: Int = 0
)
