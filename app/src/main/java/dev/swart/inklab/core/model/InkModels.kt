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

enum class PageBackgroundKind { PAPER, PDF, IMAGE }

/** Source-space rectangle for PDF CropBox/MediaBox or a normalized image crop. */
data class PageSourceBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/**
 * Affine source -> page-local transform.
 * x' = a*x + c*y + tx, y' = b*x + d*y + ty.
 */
data class PageBackgroundTransform(
    val a: Float = 1f,
    val b: Float = 0f,
    val c: Float = 0f,
    val d: Float = 1f,
    val tx: Float = 0f,
    val ty: Float = 0f
)

/**
 * Page-local background metadata. `null` on InkPage means "inherit document paper settings".
 * Asset bytes are intentionally stored outside the document JSON; `assetId` is a stable reference
 * that the asset-store/import slices can attach later without changing page coordinates again.
 */
data class PageBackground(
    val kind: PageBackgroundKind,
    val paper: BoardSettings? = null,
    val assetId: String? = null,
    val sourcePageIndex: Int? = null,
    val sourceBox: PageSourceBox? = null,
    val sourceRotationDegrees: Int = 0,
    val transform: PageBackgroundTransform = PageBackgroundTransform()
) {
    companion object {
        fun paper(settings: BoardSettings) = PageBackground(
            kind = PageBackgroundKind.PAPER,
            paper = settings
        )
    }
}

data class InkPage(
    val id: String = UUID.randomUUID().toString(),
    val strokes: List<InkStroke> = emptyList(),
    val convertedObjects: List<ConvertedInkObject> = emptyList(),
    val width: Float = 1000f,
    val height: Float = 1414f,
    val originX: Float = 0f,
    val originY: Float = 0f,
    val background: PageBackground? = null
) {
    fun resolvedPaperSettings(documentDefaults: BoardSettings): BoardSettings =
        background?.takeIf { it.kind == PageBackgroundKind.PAPER }?.paper ?: documentDefaults
}

data class InkFolder(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "Новая папка",
    val parentId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
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
    val lastPageIndex: Int = 0,
    val folderId: String? = null,
    val languageTag: String = "ru-RU",
    val favorite: Boolean = false,
    val deletedAt: Long? = null,
    val trashedPages: List<InkPage> = emptyList(),
    // Session-only viewport compatibility fields. They remain in the constructor while old UI code
    // is migrated, but storage no longer writes them and document equality deliberately ignores them.
    val savedScale: Float = 0f,
    val savedOffsetX: Float = 0f,
    val savedOffsetY: Float = 0f
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is InkBoard) return false
        return id == other.id &&
            title == other.title &&
            subject == other.subject &&
            createdAt == other.createdAt &&
            updatedAt == other.updatedAt &&
            format == other.format &&
            orientation == other.orientation &&
            settings == other.settings &&
            pages == other.pages &&
            lastPageIndex == other.lastPageIndex &&
            folderId == other.folderId &&
            languageTag == other.languageTag &&
            favorite == other.favorite &&
            deletedAt == other.deletedAt &&
            trashedPages == other.trashedPages
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + title.hashCode()
        result = 31 * result + subject.hashCode()
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + updatedAt.hashCode()
        result = 31 * result + format.hashCode()
        result = 31 * result + orientation.hashCode()
        result = 31 * result + settings.hashCode()
        result = 31 * result + pages.hashCode()
        result = 31 * result + lastPageIndex
        result = 31 * result + (folderId?.hashCode() ?: 0)
        result = 31 * result + languageTag.hashCode()
        result = 31 * result + favorite.hashCode()
        result = 31 * result + (deletedAt?.hashCode() ?: 0)
        result = 31 * result + trashedPages.hashCode()
        return result
    }
}
