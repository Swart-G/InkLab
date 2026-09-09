package dev.swart.inklab.core.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.ui.graphics.toArgb
import dev.swart.inklab.core.model.BoardSettings
import dev.swart.inklab.core.model.ConvertedInkKind
import dev.swart.inklab.core.model.InkBoard
import dev.swart.inklab.core.model.InkPage
import dev.swart.inklab.core.model.PageBackgroundKind
import dev.swart.inklab.core.model.PaperPattern
import dev.swart.inklab.core.render.PageBitmapDecoder
import dev.swart.inklab.core.storage.AssetStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import ru.noties.jlatexmath.JLatexMathDrawable
import kotlin.coroutines.coroutineContext
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

/** PDF/PNG export that renders source PDF/image backgrounds before InkLab objects. */
class DocumentExporter(private val context: Context) {
    private val decoder = PageBitmapDecoder(AssetStore(context))

    suspend fun pdf(board: InkBoard, pageRange: String, uri: Uri) = withContext(Dispatchers.IO) {
        val indices = PageRange.parse(pageRange, board.pages.size)
        val document = PdfDocument()
        try {
            indices.forEachIndexed { outputIndex, sourceIndex ->
                coroutineContext.ensureActive()
                val page = board.pages[sourceIndex]
                val size = pdfSize(page)
                val pdfPage = document.startPage(PdfDocument.PageInfo.Builder(size.first, size.second, outputIndex + 1).create())
                try {
                    val scale = min(size.first / page.width, size.second / page.height)
                    pdfPage.canvas.save()
                    pdfPage.canvas.scale(scale, scale)
                    render(pdfPage.canvas, page, board.settings, size.first, size.second)
                    pdfPage.canvas.restore()
                } finally {
                    document.finishPage(pdfPage)
                }
            }
            context.contentResolver.openOutputStream(uri, "wt")?.use(document::writeTo)
                ?: error("Не удалось открыть PDF для записи")
        } finally {
            document.close()
        }
    }

    suspend fun png(board: InkBoard, pageIndex: Int, uri: Uri, maxEdgePx: Int = 4096) = withContext(Dispatchers.IO) {
        require(pageIndex in board.pages.indices)
        require(maxEdgePx in 512..8192)
        val page = board.pages[pageIndex]
        val scale = min(1f, maxEdgePx / maxOf(page.width, page.height))
        val width = (page.width * scale).roundToInt().coerceAtLeast(1)
        val height = (page.height * scale).roundToInt().coerceAtLeast(1)
        require(width.toLong() * height <= 32_000_000L) { "PNG превышает memory budget" }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.scale(scale, scale)
            render(canvas, page, board.settings, width, height)
            context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "Не удалось записать PNG" }
            } ?: error("Не удалось открыть PNG для записи")
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun render(canvas: Canvas, page: InkPage, defaults: BoardSettings, targetWidth: Int, targetHeight: Int) {
        val settings = page.resolvedPaperSettings(defaults)
        canvas.drawColor(settings.paperColor.toInt())
        canvas.save()
        canvas.translate(-page.originX, -page.originY)
        val left = page.originX
        val top = page.originY
        val right = left + page.width
        val bottom = top + page.height

        val background = page.background
        if (background?.kind == PageBackgroundKind.PDF || background?.kind == PageBackgroundKind.IMAGE) {
            val decoded = decoder.decode(page, targetWidth.coerceAtLeast(1), targetHeight.coerceAtLeast(1))
            if (decoded != null) {
                try {
                    canvas.save()
                    canvas.clipRect(left, top, right, bottom)
                    canvas.drawBitmap(decoded.bitmap, decoded.bitmapToPage, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                    canvas.restore()
                } finally {
                    decoded.bitmap.recycle()
                }
            }
        } else {
            drawPaper(canvas, page, settings)
        }

        drawInk(canvas, page)
        canvas.restore()
    }

    private fun drawPaper(canvas: Canvas, page: InkPage, settings: BoardSettings) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val left = page.originX; val top = page.originY; val right = left + page.width; val bottom = top + page.height
        paint.color = if (android.graphics.Color.luminance(settings.paperColor.toInt()) < 0.4f) 0xFF565860.toInt() else 0xFFC9C5BC.toInt()
        paint.strokeWidth = 0.8f
        val spacing = settings.spacing.coerceAtLeast(12f)
        if (settings.pattern != PaperPattern.BLANK) {
            var y = floor(top / spacing) * spacing
            while (y < bottom) {
                if (settings.pattern == PaperPattern.RULED || settings.pattern == PaperPattern.GRID) canvas.drawLine(left, y, right, y, paint)
                if (settings.pattern == PaperPattern.DOTS) {
                    var x = floor(left / spacing) * spacing
                    while (x < right) { canvas.drawCircle(x, y, 1.2f, paint); x += spacing }
                }
                y += spacing
            }
            if (settings.pattern == PaperPattern.GRID) {
                var x = floor(left / spacing) * spacing
                while (x < right) { canvas.drawLine(x, top, x, bottom, paint); x += spacing }
            }
        }
        if (settings.showMargin) { paint.color = 0xFFDE9B9F.toInt(); canvas.drawLine(left + 74f, top, left + 74f, bottom, paint) }
    }

    private fun drawInk(canvas: Canvas, page: InkPage) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
        page.strokes.forEach { stroke ->
            paint.color = stroke.color.toArgb()
            if (stroke.points.size == 1) canvas.drawCircle(stroke.points[0].x, stroke.points[0].y, stroke.width / 2, paint)
            stroke.points.zipWithNext().forEach { (a, b) ->
                paint.strokeWidth = stroke.width * (0.55f + ((a.pressure + b.pressure) / 2).coerceIn(0.15f, 1f) * 0.75f)
                canvas.drawLine(a.x, a.y, b.x, b.y, paint)
            }
        }
        page.convertedObjects.forEach { item ->
            canvas.save(); canvas.translate(item.x, item.y)
            val math = if (item.kind == ConvertedInkKind.MATH) runCatching {
                JLatexMathDrawable.builder(item.content).textSize(item.textSize).color(item.color.toArgb()).build()
            }.getOrNull() else null
            if (math != null) {
                val fit = minOf(1f, item.width / math.intrinsicWidth.coerceAtLeast(1), item.height / math.intrinsicHeight.coerceAtLeast(1))
                canvas.scale(fit, fit)
                math.setBounds(0, 0, math.intrinsicWidth, math.intrinsicHeight)
                math.draw(canvas)
            } else {
                val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = item.color.toArgb(); textSize = item.textSize
                    typeface = Typeface.create("cursive", Typeface.NORMAL)
                }
                StaticLayout.Builder.obtain(item.content, 0, item.content.length, text, item.width.toInt().coerceAtLeast(1))
                    .setIncludePad(false).setLineSpacing(0f, 1.16f).build().draw(canvas)
            }
            canvas.restore()
        }
    }

    private fun pdfSize(page: InkPage): Pair<Int, Int> {
        val short = 595
        val scale = short / min(page.width, page.height)
        val width = (page.width * scale).roundToInt().coerceIn(72, 14_400)
        val height = (page.height * scale).roundToInt().coerceIn(72, 14_400)
        return width to height
    }
}
