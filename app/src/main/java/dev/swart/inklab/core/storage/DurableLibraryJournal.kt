package dev.swart.inklab.core.storage

import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

internal data class DurableJournalEntry(
    val sequence: Long,
    val payload: String
)

internal data class JournalReadResult(
    val entries: List<DurableJournalEntry>,
    val ignoredTrailingRecord: Boolean
)

/** Append-only, fsync-backed local mutation journal. */
internal class DurableLibraryJournal(private val file: File) {
    private val atomicFile get() = AtomicFile(file)
    private val backupFile get() = File(file.parentFile, "${file.name}.bak")

    val hasData: Boolean get() =
        (file.isFile && file.length() > 0L) || (backupFile.isFile && backupFile.length() > 0L)
    val sizeBytes: Long get() = when {
        file.isFile -> file.length()
        backupFile.isFile -> backupFile.length()
        else -> 0L
    }

    @Synchronized
    fun append(sequence: Long, payload: String): DurableJournalEntry {
        require(sequence > 0L)
        val canonicalPayload = JSONObject(payload).toString()
        val existing = read().entries
        val last = existing.lastOrNull()?.sequence ?: 0L
        require(sequence > last) { "Journal sequence must increase: $sequence <= $last" }

        // openRead() above restores an AtomicFile backup before append if compact was interrupted.
        file.parentFile?.mkdirs()
        val payloadBytes = canonicalPayload.toByteArray(Charsets.UTF_8)
        val line = encodeLine(sequence, canonicalPayload, payloadBytes)
        FileOutputStream(file, true).use { output ->
            output.write(line.toByteArray(Charsets.UTF_8))
            output.flush()
            output.fd.sync()
        }
        return DurableJournalEntry(sequence, canonicalPayload)
    }

    @Synchronized
    fun read(afterSequence: Long = 0L): JournalReadResult {
        if (!file.isFile && !backupFile.isFile) return JournalReadResult(emptyList(), false)
        val lines = runCatching {
            atomicFile.openRead().bufferedReader(Charsets.UTF_8).use { it.readLines() }
        }.getOrElse {
            if (!file.isFile) return JournalReadResult(emptyList(), false)
            throw it
        }
        val entries = mutableListOf<DurableJournalEntry>()
        var trailing = false
        var previous = 0L
        lines.forEachIndexed { index, line ->
            if (line.isBlank()) return@forEachIndexed
            val parsed = runCatching { parseLine(line) }.getOrElse { error ->
                val laterNonBlank = lines.drop(index + 1).any { it.isNotBlank() }
                if (laterNonBlank) throw IllegalStateException("Повреждение journal не в хвосте", error)
                trailing = true
                return@forEachIndexed
            }
            require(parsed.sequence > previous) { "Нарушен порядок journal sequence" }
            previous = parsed.sequence
            if (parsed.sequence > afterSequence) entries += parsed
        }
        return JournalReadResult(entries, trailing)
    }

    @Synchronized
    fun latestSequence(): Long = read().entries.lastOrNull()?.sequence ?: 0L

    @Synchronized
    fun entryCountAfter(sequence: Long): Int = read(sequence).entries.size

    @Synchronized
    fun payloadBytesAfter(sequence: Long): Long = read(sequence).entries.sumOf {
        it.payload.toByteArray(Charsets.UTF_8).size.toLong()
    }

    /**
     * Compact only through the previous retained checkpoint. Records after it are intentionally
     * preserved so the newest logical state can still be reconstructed if the newest checkpoint
     * later fails verification and the store falls back one generation.
     */
    @Synchronized
    fun compactThrough(sequence: Long) {
        if ((!file.exists() && !backupFile.exists()) || sequence <= 0L) return
        val retained = read().entries.filter { it.sequence > sequence }
        val output = atomicFile.startWrite()
        try {
            retained.forEach { entry ->
                val bytes = entry.payload.toByteArray(Charsets.UTF_8)
                output.write(encodeLine(entry.sequence, entry.payload, bytes).toByteArray(Charsets.UTF_8))
            }
            atomicFile.finishWrite(output)
        } catch (error: Throwable) {
            atomicFile.failWrite(output)
            throw error
        }
    }

    private fun encodeLine(sequence: Long, canonicalPayload: String, payloadBytes: ByteArray): String =
        JSONObject().apply {
            put("sequence", sequence)
            put("sha256", sha256(payloadBytes))
            put("payload", JSONObject(canonicalPayload))
        }.toString() + "\n"

    private fun parseLine(line: String): DurableJournalEntry {
        val root = JSONObject(line)
        val sequence = root.getLong("sequence")
        require(sequence > 0L)
        val payload = root.getJSONObject("payload").toString()
        val expected = root.getString("sha256")
        require(expected.matches(Regex("[0-9a-f]{64}")))
        require(sha256(payload.toByteArray(Charsets.UTF_8)) == expected) { "Journal checksum mismatch" }
        return DurableJournalEntry(sequence, payload)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
