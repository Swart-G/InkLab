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
import org.junit.Assert.assertNotNull
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
        assertTrue(receipt.checkpointed)

        val restored = BoardRepository(app)
        assertEquals(listOf(first, second), restored.load())
        assertEquals(listOf(folder), restored.loadFolders())
        assertEquals(receipt.sequence, restored.lastCommittedSequence)
    }

    @Test
    fun journalMutationSurvivesRepositoryRestartBeforeCheckpoint() {
        val repo = BoardRepository(app)
        val original = InkBoard(id = "document", title = "Original", format = DocumentFormat.NOTEBOOK)
        val initial = repo.saveLibrary(listOf(original), emptyList())
        val changed = repo.saveLibrary(listOf(original.copy(title = "Journalled")), emptyList())
        assertTrue(initial.checkpointed)
        assertTrue(!changed.checkpointed)
        assertTrue(changed.sequence > initial.sequence)

        val restored = BoardRepository(app)
        assertEquals("Journalled", restored.load().single().title)
        assertEquals(changed.sequence, restored.lastCommittedSequence)
    }

    @Test
    fun truncatedJournalTailIsIgnoredButConfirmedEntryReplays() {
        val repo = BoardRepository(app)
        val original = InkBoard(id = "document", title = "Original", format = DocumentFormat.NOTEBOOK)
        repo.saveLibrary(listOf(original), emptyList())
        val confirmed = repo.saveLibrary(listOf(original.copy(title = "Confirmed")), emptyList())

        val journal = File(app.filesDir, "library-store-v1/journal.ndjson")
        journal.appendText("{\"sequence\":${confirmed.sequence + 1},\"sha256\":\"partial")

        val restored = BoardRepository(app)
        assertEquals("Confirmed", restored.load().single().title)
        assertEquals(confirmed.sequence, restored.lastCommittedSequence)
    }

    @Test
    fun corruptedNewestCheckpointRebuildsFromPreviousCheckpointAndJournal() {
        val repo = BoardRepository(app)
        val original = InkBoard(id = "document", title = "Original", format = DocumentFormat.NOTEBOOK)
        repo.saveLibrary(listOf(original), emptyList())
        val changed = repo.saveLibrary(listOf(original.copy(title = "Newest")), emptyList())
        repo.checkpoint()

        val root = File(app.filesDir, "library-store-v1")
        val currentGeneration = File(root, "CURRENT").readText().trim().toLong()
        val manifest = JSONObject(File(root, "generations/g-$currentGeneration.json").readText())
        assertEquals(changed.sequence, manifest.getLong("journalSequence"))
        val ref = manifest.getJSONArray("documents").getJSONObject(0)
        File(root, "documents/${ref.getString("id")}.${ref.getString("slot")}.json").writeText("broken")

        val restored = BoardRepository(app)
        assertEquals("Newest", restored.load().single().title)
        assertEquals(changed.sequence, restored.lastCommittedSequence)
        assertTrue(File(root, "CURRENT").readText().trim().toLong() < currentGeneration)
    }

    @Test
    fun corruptionInsideJournalBlocksWriteInsteadOfSkippingConfirmedMutation() {
        val repo = BoardRepository(app)
        val original = InkBoard(id = "document", title = "Original", format = DocumentFormat.NOTEBOOK)
        repo.saveLibrary(listOf(original), emptyList())
        repo.saveLibrary(listOf(original.copy(title = "One")), emptyList())
        repo.saveLibrary(listOf(original.copy(title = "Two")), emptyList())

        val journal = File(app.filesDir, "library-store-v1/journal.ndjson")
        val lines = journal.readLines().toMutableList()
        lines[0] = lines[0].replaceFirst("\"sha256\":\"", "\"sha256\":\"0")
        journal.writeText(lines.joinToString("\n", postfix = "\n"))

        val restored = BoardRepository(app)
        assertTrue(restored.load().isEmpty())
        assertNotNull(restored.loadError)
        assertTrue(runCatching { restored.saveLibrary(listOf(original), emptyList()) }.isFailure)
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
