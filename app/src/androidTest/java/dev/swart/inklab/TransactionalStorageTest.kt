package dev.swart.inklab

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.swart.inklab.core.model.DocumentFormat
import dev.swart.inklab.core.model.InkBoard
import dev.swart.inklab.core.model.InkFolder
import dev.swart.inklab.core.storage.BoardRepository
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class TransactionalStorageTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var app: StorageTestApplication

    @Before
    fun setup() {
        app = StorageTestApplication(instrumentation.targetContext)
    }

    @After
    fun cleanup() {
        app.filesDir.deleteRecursively()
    }

    @Test
    fun generationRoundTripPreservesDocumentOrderAndFolders() {
        val first = InkBoard(id = "z-document", title = "First", format = DocumentFormat.NOTEBOOK)
        val second = InkBoard(id = "a-document", title = "Second", format = DocumentFormat.NOTEBOOK)
        val folder = InkFolder(id = "folder", title = "Folder")

        val receipt = BoardRepository(app).saveLibrary(listOf(first, second), listOf(folder))
        assertTrue(receipt.sequence > 0)

        val restored = BoardRepository(app)
        assertEquals(listOf(first, second), restored.load())
        assertEquals(listOf(folder), restored.loadFolders())
        assertEquals(receipt.sequence, restored.lastCommittedSequence)
    }

    @Test
    fun damagedNewestPayloadFallsBackToPreviousGeneration() {
        val repo = BoardRepository(app)
        val original = InkBoard(id = "document", title = "Original", format = DocumentFormat.NOTEBOOK)
        repo.saveLibrary(listOf(original), emptyList())
        repo.saveLibrary(listOf(original.copy(title = "Newest")), emptyList())

        val root = File(app.filesDir, "library-store-v1")
        val current = File(root, "CURRENT").readText().trim().toLong()
        val manifest = JSONObject(File(root, "generations/g-$current.json").readText())
        val ref = manifest.getJSONArray("documents").getJSONObject(0)
        File(root, "documents/${ref.getString("id")}.${ref.getString("slot")}.json").writeText("broken")

        val restored = BoardRepository(app)
        assertEquals("Original", restored.load().single().title)
        assertEquals(current - 1, restored.lastCommittedSequence)
        assertEquals((current - 1).toString(), File(root, "CURRENT").readText().trim())
    }

    @Test
    fun futureDocumentSchemaIsRejectedBeforeWrite() {
        val source = """[{"schemaVersion":999,"id":"future","pages":[{}]}]"""
        assertTrue(runCatching { BoardRepository(app).decode(source) }.isFailure)
    }
}

private class StorageTestApplication(private val source: Context) : Application() {
    private val key = UUID.randomUUID().toString()
    private val directory = File(source.cacheDir, "storage-fixture-$key").apply { mkdirs() }

    init {
        attachBaseContext(source)
    }

    override fun getFilesDir(): File = directory
    override fun getApplicationContext(): Context = this
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        source.getSharedPreferences("$key-$name", mode)
}
