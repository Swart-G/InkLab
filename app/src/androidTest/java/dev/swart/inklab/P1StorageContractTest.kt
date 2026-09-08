package dev.swart.inklab

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.swart.inklab.core.model.DocumentFormat
import dev.swart.inklab.core.model.InkBoard
import dev.swart.inklab.core.model.InkPage
import dev.swart.inklab.core.model.InkPoint
import dev.swart.inklab.core.model.InkStroke
import dev.swart.inklab.core.storage.BoardRepository
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class P1StorageContractTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var app: P1StorageTestApplication

    @Before fun setup() { app = P1StorageTestApplication(instrumentation.targetContext) }
    @After fun cleanup() { app.filesDir.deleteRecursively() }

    @Test
    fun viewportIsTransientAndDoesNotAdvanceDurableSequence() {
        val repo = BoardRepository(app)
        val board = InkBoard(id = "document", format = DocumentFormat.NOTEBOOK)
        val first = repo.saveLibrary(listOf(board), emptyList())

        val viewportOnly = board.copy(savedScale = 2.4f, savedOffsetX = 318f, savedOffsetY = -912f)
        val second = repo.saveLibrary(listOf(viewportOnly), emptyList())

        assertEquals(first.sequence, second.sequence)
        assertFalse(second.checkpointed)
        val journal = File(app.filesDir, "library-store-v1/journal.ndjson")
        assertTrue(!journal.exists() || journal.length() == 0L)

        val restored = BoardRepository(app).load().single()
        assertEquals(0f, restored.savedScale)
        assertEquals(0f, restored.savedOffsetX)
        assertEquals(0f, restored.savedOffsetY)
    }

    @Test
    fun durableEncodingNeverContainsViewportFields() {
        val board = InkBoard(
            id = "document",
            format = DocumentFormat.NOTEBOOK,
            savedScale = 3f,
            savedOffsetX = 123f,
            savedOffsetY = -456f
        )
        val encoded = BoardRepository(app).encode(listOf(board))
        assertFalse(encoded.contains("savedScale"))
        assertFalse(encoded.contains("savedOffsetX"))
        assertFalse(encoded.contains("savedOffsetY"))
    }

    @Test
    fun commonPageEditUsesSmallPageLevelMutationAndReplaysExactly() {
        val repo = BoardRepository(app)
        val pages = List(100) { densePage(it) }
        val original = InkBoard(id = "n2-document", format = DocumentFormat.NOTEBOOK, pages = pages)
        val fullDocumentBytes = repo.encode(listOf(original)).toByteArray().size
        repo.saveLibrary(listOf(original), emptyList())

        val changedPages = pages.toMutableList()
        changedPages[57] = changedPages[57].copy(
            strokes = changedPages[57].strokes + stroke("new-stroke", 9000f, 64)
        )
        val changed = original.copy(pages = changedPages, updatedAt = original.updatedAt + 1)
        repo.saveLibrary(listOf(changed), emptyList())

        val journal = File(app.filesDir, "library-store-v1/journal.ndjson")
        val record = JSONObject(journal.readLines().last { it.isNotBlank() })
        val payload = record.getJSONObject("payload")
        assertEquals(2, payload.getInt("version"))
        assertEquals(0, payload.getJSONArray("upsert").length())
        assertEquals(1, payload.getJSONArray("patch").length())
        assertEquals(1, payload.getJSONArray("patch").getJSONObject(0).getJSONArray("pages").length())

        val mutationBytes = payload.toString().toByteArray().size
        assertTrue("page mutation=$mutationBytes full=$fullDocumentBytes", mutationBytes < fullDocumentBytes / 8)
        assertEquals(changed, BoardRepository(app).load().single())
    }

    @Test
    fun structuralPageChangeUsesFullUpsertAndStillReplays() {
        val repo = BoardRepository(app)
        val first = InkPage(id = "page-one")
        val original = InkBoard(id = "document", format = DocumentFormat.NOTEBOOK, pages = listOf(first))
        repo.saveLibrary(listOf(original), emptyList())

        val changed = original.copy(pages = listOf(first, InkPage(id = "page-two")), lastPageIndex = 1)
        repo.saveLibrary(listOf(changed), emptyList())

        val record = JSONObject(File(app.filesDir, "library-store-v1/journal.ndjson").readLines().last { it.isNotBlank() })
        val payload = record.getJSONObject("payload")
        assertEquals(1, payload.getJSONArray("upsert").length())
        assertEquals(0, payload.getJSONArray("patch").length())
        assertEquals(changed, BoardRepository(app).load().single())
    }

    @Test
    fun metadataOnlyEditDoesNotSerializePagePayload() {
        val repo = BoardRepository(app)
        val original = InkBoard(id = "document", title = "Before", format = DocumentFormat.NOTEBOOK, pages = List(40) { densePage(it) })
        repo.saveLibrary(listOf(original), emptyList())
        val changed = original.copy(title = "After", updatedAt = original.updatedAt + 1)
        repo.saveLibrary(listOf(changed), emptyList())

        val record = JSONObject(File(app.filesDir, "library-store-v1/journal.ndjson").readLines().last { it.isNotBlank() })
        val patch = record.getJSONObject("payload").getJSONArray("patch").getJSONObject(0)
        assertEquals(0, patch.getJSONArray("pages").length())
        assertEquals("After", BoardRepository(app).load().single().title)
    }

    @Test
    fun journalV1FullUpsertRemainsReplayCompatible() {
        val repo = BoardRepository(app)
        val original = InkBoard(id = "document", title = "Before", format = DocumentFormat.NOTEBOOK)
        val first = repo.saveLibrary(listOf(original), emptyList())
        val changed = original.copy(title = "From v1", updatedAt = original.updatedAt + 1)
        val documentJson = JSONArray(repo.encode(listOf(changed))).getJSONObject(0)
        val payload = JSONObject().apply {
            put("version", 1)
            put("upsert", JSONArray().put(documentJson))
            put("delete", JSONArray())
        }.toString()
        appendJournalRecord(first.sequence + 1L, payload)

        val restored = BoardRepository(app)
        assertEquals("From v1", restored.load().single().title)
        assertEquals(first.sequence + 1L, restored.lastCommittedSequence)
    }

    private fun densePage(index: Int): InkPage = InkPage(
        id = "page-$index",
        strokes = List(8) { stroke("stroke-$index-$it", index * 100f + it, 32) }
    )

    private fun stroke(id: String, seed: Float, count: Int): InkStroke = InkStroke(
        id = id,
        points = List(count) { point ->
            InkPoint(
                x = seed + point * 1.25f,
                y = seed * 0.2f + point * 0.75f,
                timestamp = point.toLong(),
                pressure = 0.5f + (point % 10) / 20f
            )
        }
    )

    private fun appendJournalRecord(sequence: Long, payload: String) {
        val canonical = JSONObject(payload).toString()
        val bytes = canonical.toByteArray()
        val line = JSONObject().apply {
            put("sequence", sequence)
            put("sha256", sha256(bytes))
            put("payload", JSONObject(canonical))
        }.toString() + "\n"
        val journal = File(app.filesDir, "library-store-v1/journal.ndjson")
        journal.parentFile?.mkdirs()
        journal.appendText(line)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
}

private class P1StorageTestApplication(private val source: Context) : Application() {
    private val key = UUID.randomUUID().toString()
    private val directory = File(source.cacheDir, "p1-storage-fixture-$key").apply { mkdirs() }
    init { attachBaseContext(source) }
    override fun getFilesDir(): File = directory
    override fun getApplicationContext(): Context = this
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        source.getSharedPreferences("$key-$name", mode)
}