package dev.swart.inklab.core.importing

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import dev.swart.inklab.core.model.DocumentFormat
import dev.swart.inklab.core.model.InkBoard
import dev.swart.inklab.core.model.InkPage
import dev.swart.inklab.core.model.PageBackground
import dev.swart.inklab.core.model.PageBackgroundKind
import dev.swart.inklab.core.model.PageBackgroundTransform
import dev.swart.inklab.core.model.PageOrientation
import dev.swart.inklab.core.model.PageSourceBox
import dev.swart.inklab.core.storage.AssetKind
import dev.swart.inklab.core.storage.AssetStore
import dev.swart.inklab.core.storage.StoredAsset
import java.io.File
import kotlin.math.max
import kotlin.math.min

enum class ImportDocumentKind { PDF, IMAGE }

/**
 * SAF/import boundary for immutable source material.
 *
 * The original file is published through [AssetStore] first. Only after the published object can be
 * probed successfully is an [InkBoard] returned to the caller for the library commit. A failure can
 * therefore leave at most an unreferenced immutable asset; it never returns a document with a broken
 * asset reference.
 */
class DocumentImportService(
    private val context: Context,
    private val assetStore: AssetStore = AssetStore(context)
) {
    fun import(uri: Uri, kind: ImportDocumentKind, folderId: String? = null): InkBoard {
        val assetKind = when (kind) {
            ImportDocumentKind.PDF -> AssetKind.PDF
            ImportDocumentKind.IMAGE -> AssetKind.IMAGE
        }
        val asset = assetStore.import(uri, assetKind)
        return createDocumentFromAsset(
            asset = asset,
            title = resolveDisplayName(uri, kind),
            folderId = folderId
        )
    }

    /** Exposed for deterministic importer regression tests and future restore/package assembly. */
    fun createDocumentFromAsset(
        asset: StoredAsset,
        title: String,
        folderId: String? = null
    ): InkBoard = when (asset.kind) {
        AssetKind.PDF -> createPdfDocument(asset, title, folderId)
        AssetKind.IMAGE -> createImageDocument(asset, title, folderId)
    }

    private fun createPdfDocument(asset: StoredAsset, title: String, folderId: String?): InkBoard {
        require(asset.mimeType == "application/pdf") { "Asset не является PDF" }
        val pages = mutableListOf<InkPage>()
        ParcelFileDescriptor.open(asset.file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                require(renderer.pageCount in 1..MAX_PDF_PAGES) {
                    if (renderer.pageCount <= 0) "PDF не содержит страниц"
                    else "PDF содержит слишком много страниц: ${renderer.pageCount}"
                }
                repeat(renderer.pageCount) { index ->
                    val page = renderer.openPage(index)
                    try {
                        val sourceWidth = page.width.toFloat()
                        val sourceHeight = page.height.toFloat()
                        validateSourceGeometry(sourceWidth, sourceHeight, "PDF page ${index + 1}")
                        val scale = TARGET_SHORT_EDGE / min(sourceWidth, sourceHeight)
                        pages += InkPage(
                            width = sourceWidth * scale,
                            height = sourceHeight * scale,
                            background = PageBackground(
                                kind = PageBackgroundKind.PDF,
                                assetId = asset.id,
                                sourcePageIndex = index,
                                sourceBox = PageSourceBox(0f, 0f, sourceWidth, sourceHeight),
                                // PdfRenderer exposes the normalized display dimensions. Preserve
                                // that coordinate space here; a richer backend can populate the
                                // original CropBox/rotation later without changing page-local ink.
                                sourceRotationDegrees = 0,
                                transform = PageBackgroundTransform(a = scale, d = scale)
                            )
                        )
                    } finally {
                        page.close()
                    }
                }
            }
        }
        val orientation = orientationOf(pages.first())
        return InkBoard(
            title = normalizedTitle(title, "Импортированный PDF"),
            format = DocumentFormat.NOTEBOOK,
            orientation = orientation,
            pages = pages,
            folderId = folderId
        )
    }

    private fun createImageDocument(asset: StoredAsset, title: String, folderId: String?): InkBoard {
        require(asset.mimeType.startsWith("image/")) { "Asset не является изображением" }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(asset.file.absolutePath, options)
        val rawWidth = options.outWidth
        val rawHeight = options.outHeight
        require(rawWidth > 0 && rawHeight > 0) { "Не удалось определить размер изображения" }
        require(rawWidth <= MAX_IMAGE_EDGE && rawHeight <= MAX_IMAGE_EDGE) {
            "Изображение имеет слишком большой размер: ${rawWidth}×${rawHeight}"
        }
        require(rawWidth.toLong() * rawHeight.toLong() <= MAX_IMAGE_PIXELS) {
            "Изображение содержит слишком много пикселей"
        }

        val orientation = readExifOrientation(asset)
        val affine = exifTransform(rawWidth.toFloat(), rawHeight.toFloat(), orientation)
        validateSourceGeometry(affine.outputWidth, affine.outputHeight, "image")
        val scale = TARGET_SHORT_EDGE / min(affine.outputWidth, affine.outputHeight)
        val page = InkPage(
            width = affine.outputWidth * scale,
            height = affine.outputHeight * scale,
            background = PageBackground(
                kind = PageBackgroundKind.IMAGE,
                assetId = asset.id,
                sourceBox = PageSourceBox(0f, 0f, rawWidth.toFloat(), rawHeight.toFloat()),
                transform = PageBackgroundTransform(
                    a = affine.a * scale,
                    b = affine.b * scale,
                    c = affine.c * scale,
                    d = affine.d * scale,
                    tx = affine.tx * scale,
                    ty = affine.ty * scale
                )
            )
        )
        return InkBoard(
            title = normalizedTitle(title, "Импортированное изображение"),
            format = DocumentFormat.NOTEBOOK,
            orientation = orientationOf(page),
            pages = listOf(page),
            folderId = folderId
        )
    }

    private fun resolveDisplayName(uri: Uri, kind: ImportDocumentKind): String {
        val queried = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) null
                else cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    .takeIf { it >= 0 }
                    ?.let(cursor::getString)
            }
        }.getOrNull()
        val fallback = uri.lastPathSegment?.substringAfterLast('/')
        return normalizedTitle(
            queried ?: fallback.orEmpty(),
            if (kind == ImportDocumentKind.PDF) "Импортированный PDF" else "Импортированное изображение"
        )
    }

    private fun normalizedTitle(value: String, fallback: String): String {
        val clean = value.trim().substringBeforeLast('.', missingDelimiterValue = value.trim()).trim()
        return clean.ifBlank { fallback }.take(MAX_TITLE_LENGTH)
    }

    private fun readExifOrientation(asset: StoredAsset): Int {
        if (asset.mimeType != "image/jpeg") return ExifInterface.ORIENTATION_NORMAL
        return runCatching {
            ExifInterface(asset.file.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    }

    private data class ImageAffine(
        val outputWidth: Float,
        val outputHeight: Float,
        val a: Float,
        val b: Float,
        val c: Float,
        val d: Float,
        val tx: Float,
        val ty: Float
    )

    /** Raw image pixels -> EXIF-normalized top-left image coordinates. */
    private fun exifTransform(width: Float, height: Float, orientation: Int): ImageAffine = when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL ->
            ImageAffine(width, height, -1f, 0f, 0f, 1f, width, 0f)
        ExifInterface.ORIENTATION_ROTATE_180 ->
            ImageAffine(width, height, -1f, 0f, 0f, -1f, width, height)
        ExifInterface.ORIENTATION_FLIP_VERTICAL ->
            ImageAffine(width, height, 1f, 0f, 0f, -1f, 0f, height)
        ExifInterface.ORIENTATION_TRANSPOSE ->
            ImageAffine(height, width, 0f, 1f, 1f, 0f, 0f, 0f)
        ExifInterface.ORIENTATION_ROTATE_90 ->
            ImageAffine(height, width, 0f, 1f, -1f, 0f, height, 0f)
        ExifInterface.ORIENTATION_TRANSVERSE ->
            ImageAffine(height, width, 0f, -1f, -1f, 0f, height, width)
        ExifInterface.ORIENTATION_ROTATE_270 ->
            ImageAffine(height, width, 0f, -1f, 1f, 0f, 0f, width)
        else -> ImageAffine(width, height, 1f, 0f, 0f, 1f, 0f, 0f)
    }

    private fun validateSourceGeometry(width: Float, height: Float, label: String) {
        require(width.isFinite() && height.isFinite() && width > 0f && height > 0f) {
            "$label имеет некорректный размер"
        }
        val ratio = max(width, height) / min(width, height)
        require(ratio <= MAX_PAGE_ASPECT) { "$label имеет неподдерживаемое соотношение сторон" }
    }

    private fun orientationOf(page: InkPage) =
        if (page.height >= page.width) PageOrientation.PORTRAIT else PageOrientation.LANDSCAPE

    companion object {
        const val TARGET_SHORT_EDGE = 1000f
        const val MAX_PDF_PAGES = 2_000
        const val MAX_IMAGE_EDGE = 50_000
        const val MAX_IMAGE_PIXELS = 250_000_000L
        private const val MAX_PAGE_ASPECT = 20f
        private const val MAX_TITLE_LENGTH = 160
    }
}
