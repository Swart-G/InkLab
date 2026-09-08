package dev.swart.inklab.core.storage

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

internal data class LibraryStoreSnapshot(
    val sequence: Long,
    val committedAt: Long,
    val documents: Map<String, String>,
    val foldersJson: String
)

internal data class LibraryStoreCommit(
    val sequence: Long,
    val committedAt: Long
)

/**
 * Crash-safe local library store.
 *
 * A published generation is a small manifest referenced by CURRENT. Document payloads use
 * alternating A/B slots. A new payload is always written to the slot that is NOT referenced by
 * the currently published manifest. Therefore a process death before CURRENT is switched leaves
 * the previous generation intact; after CURRENT is switched the new generation is complete.
 *
 * Only the current and immediately previous generation are required for recovery. Payload slots
 * are intentionally not deleted here: deletion/GC is a separate lifecycle concern and must never
 * be part of the durability boundary.
 */
internal class TransactionalLibraryStore(private val root: File) {
    private val documentsDirectory = File(root, "documents")
    private val generationsDirectory = File(root, "generations")
    private val currentFile = File(root, "CURRENT")
    private var activeManifest: Manifest? = null

    private data class DocumentRef(
        val id: String,
        val slot: String,
        val sha256: String
    )

    private data class Manifest(
        val sequence: Long,
        val committedAt: Long,
        val documents: List<DocumentRef>,
        val foldersJson: String
    )

    fun hasPublishedData(): Boolean =
        currentFile.isFile || generationsDirectory.listFiles()?.any { generationNumber(it) != null } == true

    @Synchronized
    fun load(): LibraryStoreSnapshot? {
        if (!hasPublishedData()) return null
        val preferred = currentFile.takeIf { it.isFile }
            ?.let { runCatching { it.readText().trim().toLong() }.getOrNull() }
        val candidates = buildList {
            if (preferred != null) add(preferred)
            generationsDirectory.listFiles()
                ?.mapNotNull(::generationNumber)
                ?.sortedDescending()
                ?.forEach { if (it !in this) add(it) }
        }
        var lastError: Throwable? = null
        for (sequence in candidates) {
            val manifest = runCatching { readManifest(sequence, verifyPayloads = true) }
                .onFailure { lastError = it }
                .getOrNull() ?: continue
            activeManifest = manifest
            if (preferred != sequence) writeAtomic(currentFile, sequence.toString())
            val documents = linkedMapOf<String, String>()
            manifest.documents.forEach { ref ->
                documents[ref.id] = payloadFile(ref).readText(Charsets.UTF_8)
            }
            return LibraryStoreSnapshot(
                sequence = manifest.sequence,
                committedAt = manifest.committedAt,
                documents = documents,
                foldersJson = manifest.foldersJson
            )
        }
        throw IllegalStateException("Не найдено целой локальной generation", lastError)
    }

    @Synchronized
    fun commit(documents: Map<String, String>, foldersJson: String): LibraryStoreCommit {
        root.mkdirs()
        documentsDirectory.mkdirs()
        generationsDirectory.mkdirs()
        require(documents.keys.all(::safeId)) { "Недопустимый documentId" }
        JSONArray(foldersJson)

        if (activeManifest == null && hasPublishedData()) load()
        val previous = activeManifest
        val previousRefs = previous?.documents?.associateBy { it.id }.orEmpty()
        val refs = ArrayList<DocumentRef>(documents.size)

        // LinkedHashMap from BoardRepository preserves the user-visible library order.
        documents.forEach { (id, content) ->
            JSONObject(content)
            val hash = sha256(content.toByteArray(Charsets.UTF_8))
            val old = previousRefs[id]
            if (old != null && old.sha256 == hash && payloadFile(old).isFile) {
                refs += old
            } else {
                val slot = if (old?.slot == SLOT_A) SLOT_B else SLOT_A
                val ref = DocumentRef(id, slot, hash)
                val target = payloadFile(ref)
                writeAtomic(target, content)
                check(sha256(target.readBytes()) == hash) { "Не удалось проверить запись $id" }
                refs += ref
            }
        }

        val maxKnownSequence = generationsDirectory.listFiles()
            ?.mapNotNull(::generationNumber)
            ?.maxOrNull() ?: 0L
        val sequence = maxOf(previous?.sequence ?: 0L, maxKnownSequence) + 1L
        val committedAt = System.currentTimeMillis()
        val manifest = Manifest(sequence, committedAt, refs, JSONArray(foldersJson).toString())
        writeAtomic(generationFile(sequence), manifest.toJson().toString())
        val verified = readManifest(sequence, verifyPayloads = true)
        writeAtomic(currentFile, sequence.toString())
        activeManifest = verified
        pruneGenerationManifests(sequence)
        return LibraryStoreCommit(sequence, committedAt)
    }

    fun copyForRecovery(destination: File) {
        if (root.exists()) root.copyRecursively(destination, overwrite = false)
    }

    private fun readManifest(sequence: Long, verifyPayloads: Boolean): Manifest {
        val source = generationFile(sequence)
        require(source.isFile) { "Generation $sequence отсутствует" }
        val json = JSONObject(source.readText(Charsets.UTF_8))
        require(json.getInt("storageVersion") == STORAGE_VERSION) { "Неподдерживаемая версия local store" }
        require(json.getLong("sequence") == sequence) { "Неверный номер generation" }
        val items = json.getJSONArray("documents")
        val refs = List(items.length()) { index ->
            val item = items.getJSONObject(index)
            DocumentRef(
                id = item.getString("id"),
                slot = item.getString("slot"),
                sha256 = item.getString("sha256")
            ).also {
                require(safeId(it.id))
                require(it.slot == SLOT_A || it.slot == SLOT_B)
                require(it.sha256.matches(Regex("[0-9a-f]{64}")))
            }
        }
        require(refs.map { it.id }.distinct().size == refs.size) { "Повторяющийся documentId в manifest" }
        if (verifyPayloads) refs.forEach { ref ->
            val payload = payloadFile(ref)
            require(payload.isFile) { "Отсутствует payload ${ref.id}" }
            require(sha256(payload.readBytes()) == ref.sha256) { "Checksum mismatch: ${ref.id}" }
        }
        val folders = json.optJSONArray("folders") ?: JSONArray()
        return Manifest(
            sequence = sequence,
            committedAt = json.optLong("committedAt", 0L),
            documents = refs,
            foldersJson = folders.toString()
        )
    }

    private fun Manifest.toJson() = JSONObject().apply {
        put("storageVersion", STORAGE_VERSION)
        put("sequence", sequence)
        put("committedAt", committedAt)
        put("documents", JSONArray().apply {
            documents.forEach { ref ->
                put(JSONObject().apply {
                    put("id", ref.id)
                    put("slot", ref.slot)
                    put("sha256", ref.sha256)
                })
            }
        })
        put("folders", JSONArray(foldersJson))
    }

    private fun payloadFile(ref: DocumentRef) = File(documentsDirectory, "${ref.id}.${ref.slot}.json")
    private fun generationFile(sequence: Long) = File(generationsDirectory, "g-$sequence.json")

    private fun generationNumber(file: File): Long? {
        if (!file.isFile) return null
        val match = GENERATION_REGEX.matchEntire(file.name) ?: return null
        return match.groupValues[1].toLongOrNull()
    }

    private fun pruneGenerationManifests(current: Long) {
        val keep = generationsDirectory.listFiles()
            ?.mapNotNull { file -> generationNumber(file)?.let { it to file } }
            ?.sortedByDescending { it.first }
            ?.take(2)
            ?.mapTo(mutableSetOf()) { it.first }
            .orEmpty()
        generationsDirectory.listFiles()?.forEach { file ->
            val number = generationNumber(file) ?: return@forEach
            if (number !in keep && number < current) {
                file.delete()
                File(file.parentFile, "${file.name}.bak").delete()
            }
        }
    }

    private fun writeAtomic(target: File, content: String) {
        target.parentFile?.mkdirs()
        val atomic = AtomicFile(target)
        val output = atomic.startWrite()
        try {
            output.write(content.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (error: Throwable) {
            atomic.failWrite(output)
            throw error
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun safeId(id: String) = id.matches(Regex("[a-zA-Z0-9-]+"))

    companion object {
        private const val STORAGE_VERSION = 1
        private const val SLOT_A = "a"
        private const val SLOT_B = "b"
        private val GENERATION_REGEX = Regex("g-([0-9]+)\\.json")
    }
}
