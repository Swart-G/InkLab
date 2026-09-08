package dev.swart.inklab

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.swart.inklab.core.importing.DocumentImportService
import dev.swart.inklab.core.model.DocumentFormat
import dev.swart.inklab.core.model.PageBackgroundKind
import dev.swart.inklab.core.model.PageOrientation
import dev.swart.inklab.core.storage.AssetKind
import dev.swart.inklab.core.storage.AssetStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DocumentImportTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var context: ImportTestContext

    @Before
    fun setup() {
        context = ImportTestContext(target)
    }

    @After
    fun cleanup() {
        context.filesDir.deleteRecursively()
    }

    @Test
    fun pdfAssetCreatesOnePagePerSourcePageWithOneStableAssetReference() {
        val source = File(context.filesDir, "lecture.pdf")
        val document = PdfDocument()
        try {
            val portrait = document.startPage(PdfDocument.PageInfo.Builder(600, 800, 1).create())
            portrait.canvas.drawText("Page one", 30f, 40f, android.graphics.Paint())
            document.finishPage(portrait)
            val landscape = document.startPage(PdfDocument.PageInfo.Builder(1600, 900, 2).create())
            landscape.canvas.drawText("Page two", 30f, 40f, android.graphics.Paint())
            document.finishPage(landscape)
            source.outputStream().use(document::writeTo)
        } finally {
            document.close()
        }

        val asset = AssetStore(context).import(source.inputStream(), AssetKind.PDF)
        val board = DocumentImportService(context).createDocumentFromAsset(asset, "Lecture.pdf", "folder-1")

        assertEquals("Lecture", board.title)
        assertEquals(DocumentFormat.NOTEBOOK, board.format)
        assertEquals(PageOrientation.PORTRAIT, board.orientation)
        assertEquals("folder-1", board.folderId)
        assertEquals(2, board.pages.size)
        board.pages.forEachIndexed { index, page ->
            assertEquals(PageBackgroundKind.PDF, page.background?.kind)
            assertEquals(asset.id, page.background?.assetId)
            assertEquals(index, page.background?.sourcePageIndex)
        }
        assertEquals(1000f, board.pages[0].width, 0.01f)
        assertEquals(1333.3334f, board.pages[0].height, 0.1f)
        assertEquals(1777.7778f, board.pages[1].width, 0.1f)
        assertEquals(1000f, board.pages[1].height, 0.01f)
        assertEquals(1000f / 600f, board.pages[0].background!!.transform.a, 0.0001f)
        assertEquals(board.pages[0].background!!.transform.a, board.pages[0].background!!.transform.d, 0.0001f)
    }

    @Test
    fun jpegExifRotate90CreatesLandscapePageAndAffineNormalization() {
        val rawJpeg = ByteArrayOutputStream().use { output ->
            Bitmap.createBitmap(400, 800, Bitmap.Config.ARGB_8888).also { bitmap ->
                bitmap.eraseColor(android.graphics.Color.WHITE)
                assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output))
                bitmap.recycle()
            }
            output.toByteArray()
        }
        val orientedJpeg = injectExifOrientation(rawJpeg, 6)
        val asset = AssetStore(context).import(orientedJpeg.inputStream(), AssetKind.IMAGE)

        val board = DocumentImportService(context).createDocumentFromAsset(asset, "portrait-camera.jpg")
        val page = board.pages.single()
        val transform = page.background!!.transform

        assertEquals("portrait-camera", board.title)
        assertEquals(PageOrientation.LANDSCAPE, board.orientation)
        assertEquals(PageBackgroundKind.IMAGE, page.background?.kind)
        assertEquals(asset.id, page.background?.assetId)
        assertEquals(2000f, page.width, 0.1f)
        assertEquals(1000f, page.height, 0.1f)
        assertEquals(0f, transform.a, 0.0001f)
        assertEquals(2.5f, transform.b, 0.0001f)
        assertEquals(-2.5f, transform.c, 0.0001f)
        assertEquals(0f, transform.d, 0.0001f)
        assertEquals(2000f, transform.tx, 0.1f)
        assertEquals(0f, transform.ty, 0.0001f)
    }

    @Test
    fun syntacticallyDetectedButUnreadablePdfIsRejectedBeforeDocumentCreation() {
        val bytes = "%PDF-1.7\nthis is not a valid PDF body\n".toByteArray()
        val asset = AssetStore(context).import(bytes.inputStream(), AssetKind.PDF)

        val failure = runCatching {
            DocumentImportService(context).createDocumentFromAsset(asset, "broken.pdf")
        }.exceptionOrNull()

        assertNotNull(failure)
        assertTrue(AssetStore(context).load(asset.id).file.isFile)
    }

    @Test
    fun libraryCreateMenuExposesPdfAndImageSafActions() {
        compose.onNodeWithContentDescription("Создать").assertIsDisplayed().performClick()
        compose.onNodeWithText("PDF").assertIsDisplayed()
        compose.onNodeWithText("Изображение").assertIsDisplayed()
    }

    private fun injectExifOrientation(jpeg: ByteArray, orientation: Int): ByteArray {
        require(jpeg.size > 2 && jpeg[0] == 0xff.toByte() && jpeg[1] == 0xd8.toByte())
        require(orientation in 1..8)
        val payload = byteArrayOf(
            0x45, 0x78, 0x69, 0x66, 0x00, 0x00,
            0x49, 0x49, 0x2a, 0x00, 0x08, 0x00, 0x00, 0x00,
            0x01, 0x00,
            0x12, 0x01, 0x03, 0x00, 0x01, 0x00, 0x00, 0x00,
            orientation.toByte(), 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00
        )
        val output = ByteArrayOutputStream(jpeg.size + payload.size + 4)
        output.write(jpeg, 0, 2)
        output.write(byteArrayOf(0xff.toByte(), 0xe1.toByte(), 0x00, 0x22))
        output.write(payload)
        output.write(jpeg, 2, jpeg.size - 2)
        return output.toByteArray()
    }
}

private class ImportTestContext(source: Context) : ContextWrapper(source) {
    private val directory = File(source.cacheDir, "document-import-${UUID.randomUUID()}").apply { mkdirs() }
    override fun getFilesDir(): File = directory
    override fun getApplicationContext(): Context = this
}
