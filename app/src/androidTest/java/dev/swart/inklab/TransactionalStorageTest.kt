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

    @Before fun setup() { app = StorageTestApplication(instrumentation.targetContext) }
    @After fun cleanup() { app.filesDir.deleteRecursively() }

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
    fun nextAppendRepairsPreviouslyTruncatedTail() {
        val original = InkBoard(id = "document", title = "Original", format = DocumentFormat.NOTEBOOK)
        val seed = BoardRepository(app)
        seed.saveLibrary(listOf(original), emptyList())
        val confirmed = seed.saveLibrary(listOf(original.copy(title = "Confirmed")), emptyList())
        val journal = File(app.filesDir, "library-store-v1/journal.ndjson")
        journal.appendText("{\"sequence\":${confirmed.sequence + 1},\"sha256\":\"partial")

        val resumed = BoardRepository(app)
        assertEquals("Confirmed", resumed.load().single().title)
        val final = resumed.saveLibrary(listOf(original.copy(title = "After repair")), emptyList())
        assertTrue(final.sequence > confirmed.sequence)

        val restored = BoardRepository(app)
        assertEquals("After repair", restored.load().single().title)
        assertEquals(final.sequence, restored.lastCommittedSequence)
        val lines = journal.readLines().filter { it.isNotBlank() }
        assertTrue(lines.all { runCatching { JSONObject(it) }.isSuccess })
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
        corruptFirstPayload(root, manifest)
        val restored = BoardRepository(app)
        assertEquals("Newest", restored.load().single().title)
        assertEquals(changed.sequence, restored.lastCommittedSequence)
        assertTrue(File(root, "CURRENT").readText().trim().toLong() < currentGeneration)
    }

    @Test
    fun checkpointAfterFallbackKeepsTheActuallyValidPreviousGeneration() {
        val original = InkBoard(id = "document", title = "Original", format = DocumentFormat.NOTEBOOK)
        val repo = BoardRepository(app)
        repo.saveLibrary(listOf(original), emptyList())
        repo.saveLibrary(listOf(original.copy(title = "Two")), emptyList())
        repo.checkpoint()

        val root = File(app.filesDir, "library-store-v1")
        val badGeneration = File(root, "CURRENT").readText().trim().toLong()
        corruptFirstPayload(root, JSONObject(File(root, "generations/g-$badGeneration.json").readText()))

        val fallback = BoardRepository(app)
        assertEquals("Two", fallback.load().single().title)
        val previousValidGeneration = File(root, "CURRENT").readText().trim().toLong()
        assertTrue(previousValidGeneration < badGeneration)

        val newest = original.copy(title = "Three")
        fallback.saveLibrary(listOf(newest), emptyList())
        fallback.checkpoint()
        val newestGeneration = File(root, "CURRENT").readText().trim().toLong()
        assertTrue(File(root, "generations/g-$previousValidGeneration.json").exists())
        assertTrue(!File(root, "generations/g-$badGeneration.json").exists())

        corruptFirstPayload(root, JSONObject(File(root, "generations/g-$newestGeneration.json").readText()))
        val secondFallback = BoardRepository(app)
        assertEquals("Three", secondFallback.load().single().title)
    }

    @Test
    fun corruptionInsideJournalBlocksWriteInsteadOfSkippingConfirmedMutation() {
        val repo = BoardRepository(app)
        val original = InkBoard(id = "document", title = "Original", format = DocumentFormat.NOTEBOOK)
        repo.saveLibrary(listOf(original), emptyList())
        repo.saveLibrary(listOf(original.copy(title = "One")), emptyList())
        repo.saveLibrary(listOf(original.copy(title = "Two")), emptyList())
        corruptFirstJournalRecord()
        val restored = BoardRepository(app)
        assertTrue(restored.load().isEmpty())
        assertNotNull(restored.loadError)
        assertTrue(runCatching { restored.saveLibrary(listOf(original), emptyList()) }.isFailure)
    }

    @Test
    fun explicitRecoveryCopiesCorruptStoreAndCanStartCleanGeneration() {
        val original = InkBoard(id = "document", title = "Original", format = DocumentFormat.NOTEBOOK)
        val seed = BoardRepository(app)
        seed.saveLibrary(listOf(original), emptyList())
        seed.saveLibrary(listOf(original.copy(title = "One")), emptyList())
        seed.saveLibrary(listOf(original.copy(title = "Two")), emptyList())
        corruptFirstJournalRecord()

        val broken = BoardRepository(app)
        assertTrue(broken.load().isEmpty())
        assertNotNull(broken.loadError)
        broken.allowRecovery()
        val recovered = original.copy(title = "Recovered")
        val receipt = broken.saveLibrary(listOf(recovered), emptyList())
        assertTrue(receipt.checkpointed)
        assertEquals("Recovered", BoardRepository(app).load().single().title)
        val recoveryRoots = app.filesDir.listFiles()?.filter { it.isDirectory && it.name.startsWith("recovery-") }.orEmpty()
        assertTrue(recoveryRoots.any { File(it, "library-store-v1/journal.ndjson").exists() })
    }

    @Test
    fun futureDocumentSchemaIsRejectedBeforeWrite() {
        val source = """[{"schemaVersion":999,"id":"future","pages":[{}]}]"""
        assertTrue(runCatching { BoardRepository(app).decode(source) }.isFailure)
    }

    private fun corruptFirstJournalRecord() {
        val journal = File(app.filesDir, "library-store-v1/journal.ndjson")
        val lines = journal.readLines().toMutableList()
        lines[0] = lines[0].replaceFirst("\"sha256\":\"", "\"sha256\":\"0")
        journal.writeText(lines.joinToString("\n", postfix = "\n"))
    }

    private fun corruptFirstPayload(root: File, manifest: JSONObject) {
        val ref = manifest.getJSONArray("documents").getJSONObject(0)
        File(root, "documents/${ref.getString("id")}.${ref.getString("slot")}.json").writeText("broken")
    }
}

private class StorageTestApplication(private val source: Context) : Application() {
    private val key = UUID.randomUUID().toString()
    private val directory = File(source.cacheDir, "storage-fixture-$key").apply { mkdirs() }
    init { attachBaseContext(source) }
    override fun getFilesDir(): File = directory
    override fun getApplicationContext(): Context = this
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        source.getSharedPreferences("$key-$name", mode)
}
