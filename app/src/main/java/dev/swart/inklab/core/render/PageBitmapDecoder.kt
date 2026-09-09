package dev.swart.inklab.core.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import dev.swart.inklab.core.model.InkPage
import dev.swart.inklab.core.model.PageBackgroundKind
import dev.swart.inklab.core.model.PageBackgroundTransform
import dev.swart.inklab.core.storage.AssetKind
import dev.swart.inklab.core.storage.AssetStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

/** Cache key deliberately contains document generation so stale async decodes cannot be reused. */
data class PageRenderKey(
    val assetId: String,
    val sourcePageIndex: Int,
    val widthBucket: Int,
    val heightBucket: Int
)

data class DecodedPageBitmap(val bitmap: Bitmap, val bitmapToPage: Matrix)

class PageBitmapDecoder(private val assetStore: AssetStore) {
    suspend fun decode(page: InkPage, targetWidthPx: Int, targetHeightPx: Int): DecodedPageBitmap? = withContext(Dispatchers.IO) {
        val background = page.background ?: return@withContext null
        val assetId = background.assetId ?: return@withContext null
        require(targetWidthPx > 0 && targetHeightPx > 0)
        val asset = assetStore.load(assetId)
        when (background.kind) {
            PageBackgroundKind.PDF -> {
                require(asset.kind == AssetKind.PDF)
                decodePdf(page, asset.file, targetWidthPx, targetHeightPx)
            }
            PageBackgroundKind.IMAGE -> {
                require(asset.kind == AssetKind.IMAGE)
                decodeImage(page, asset.file, targetWidthPx, targetHeightPx)
            }
            PageBackgroundKind.PAPER -> null
        }
    }

    private fun decodePdf(page: InkPage, file: java.io.File, targetWidth: Int, targetHeight: Int): DecodedPageBitmap {
        val background = requireNotNull(page.background)
        val index = background.sourcePageIndex ?: 0
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                renderer.openPage(index).use { source ->
                    require(index in 0 until renderer.pageCount) { "PDF page index вне диапазона" }
                    val scale = minOf(
                        targetWidth.toFloat() / source.width.coerceAtLeast(1),
                        targetHeight.toFloat() / source.height.coerceAtLeast(1),
                        MAX_RENDER_SCALE
                    ).coerceAtLeast(MIN_RENDER_SCALE)
                    val width = (source.width * scale).roundToInt().coerceIn(1, MAX_BITMAP_EDGE)
                    val height = (source.height * scale).roundToInt().coerceIn(1, MAX_BITMAP_EDGE)
                    require(width.toLong() * height <= MAX_BITMAP_PIXELS) { "PDF preview превышает RAM budget" }
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    source.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    return DecodedPageBitmap(bitmap, bitmapToPage(background.transform, 1f / scale))
                }
            }
        }
    }

    private fun decodeImage(page: InkPage, file: java.io.File, targetWidth: Int, targetHeight: Int): DecodedPageBitmap {
        val background = requireNotNull(page.background)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Не удалось прочитать изображение" }

        var sample = 1
        while (
            bounds.outWidth / (sample * 2) >= targetWidth &&
            bounds.outHeight / (sample * 2) >= targetHeight
        ) sample *= 2
        while ((bounds.outWidth / sample).toLong() * (bounds.outHeight / sample) > MAX_BITMAP_PIXELS) sample *= 2

        val bitmap = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: error("Не удалось декодировать изображение")
        require(bitmap.width <= MAX_BITMAP_EDGE && bitmap.height <= MAX_BITMAP_EDGE)
        return DecodedPageBitmap(bitmap, bitmapToPage(background.transform, sample.toFloat()))
    }

    private fun bitmapToPage(transform: PageBackgroundTransform, bitmapPixelToSource: Float): Matrix = Matrix().apply {
        val s = bitmapPixelToSource
        setValues(floatArrayOf(
            transform.a * s, transform.c * s, transform.tx,
            transform.b * s, transform.d * s, transform.ty,
            0f, 0f, 1f
        ))
    }

    companion object {
        const val DEFAULT_CACHE_BYTES = 48L * 1024L * 1024L
        private const val MAX_BITMAP_EDGE = 8192
        private const val MAX_BITMAP_PIXELS = 24_000_000L
        private const val MAX_RENDER_SCALE = 3f
        private const val MIN_RENDER_SCALE = 0.05f

        fun bucket(value: Int): Int = max(128, ((value + 127) / 128) * 128)
    }
}

/** Shared cache with explicit bitmap recycling on eviction. */
class PageBitmapCache(maxBytes: Long = PageBitmapDecoder.DEFAULT_CACHE_BYTES) {
    private val cache = RenderCache<PageRenderKey, DecodedPageBitmap>(
        maxBytes = maxBytes,
        sizeOf = { it.bitmap.byteCount.toLong() },
        onEvict = { if (!it.bitmap.isRecycled) it.bitmap.recycle() }
    )

    fun get(key: PageRenderKey, generation: Long) = cache.get(key, generation)
    fun put(key: PageRenderKey, generation: Long, value: DecodedPageBitmap) = cache.put(key, generation, value)
    fun clear() = cache.clear()
    fun bytes() = cache.bytes()
}
