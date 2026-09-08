package dev.swart.inklab.core.storage

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

enum class AssetKind { PDF, IMAGE }

data class StoredAsset(
    val id: String,
    val kind: AssetKind,
    val mimeType: String,
    val sizeBytes: Long,
    val sha256: String,
    val createdAt: Long,
    val file: File
)

/**
 * Immutable, content-addressed storage for original PDF/image assets.
 *
 * Publication order is deliberate: complete + fsync staging bytes -> immutable object -> AtomicFile
 * metadata. A crash can therefore leave an unreferenced object, but never a published metadata record
 * that points at partial/missing bytes. Documents must be committed only after [import] returns.
 */
class AssetStore(private val context: Context) {
    private val root = File(context.filesDir, "assets-v1")
    private val objects = File(root, "objects")
    private val metadata = File(root, "metadata")
    private val staging = File(root, "staging")

    fun import(uri: Uri, expectedKind: AssetKind, maxBytes: Long = MAX_ASSET_BYTES): StoredAsset {
        val resolver = context.contentResolver
        val reportedMime = resolver.getType(uri)
        val input = resolver.openInputStream(uri) ?: error("Не удалось открыть выбранный файл")
        return input.use { import(it, expectedKind, reportedMime, maxBytes) }
    }

    @Synchronized
    fun import(
        input: InputStream,
        expectedKind: AssetKind,
        reportedMimeType: String? = null,
        maxBytes: Long = MAX_ASSET_BYTES
    ): StoredAsset {
        require(maxBytes in 1..MAX_ASSET_BYTES) { "Недопустимый лимит размера asset" }
        root.mkdirs(); objects.mkdirs(); metadata.mkdirs(); staging.mkdirs()
        require(staging.usableSpace > MIN_FREE_SPACE_AFTER_IMPORT) { "Недостаточно свободного места для импорта" }

        val stage = File(staging, "${UUID.randomUUID()}.part")
        val digest = MessageDigest.getInstance("SHA-256")
        val header = ByteArray(HEADER_BYTES)
        var headerSize = 0
        var size = 0L
        try {
            FileOutputStream(stage).use { output ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    size += count
                    require(size <= maxBytes) { "Файл превышает лимит ${maxBytes / (1024 * 1024)} МБ" }
                    if (headerSize < header.size) {
                        val copy = minOf(count, header.size - headerSize)
                        buffer.copyInto(header, headerSize, 0, copy)
                        headerSize += copy
                    }
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                    if (size % FREE_SPACE_CHECK_INTERVAL < count) {
                        require(staging.usableSpace > MIN_FREE_SPACE_AFTER_IMPORT) { "Недостаточно свободного места для завершения импорта" }
                    }
                }
                require(size > 0) { "Пустой файл нельзя импортировать" }
                output.fd.sync()
            }

            val detected = detect(header, headerSize)
                ?: throw IllegalArgumentException("Неподдерживаемый или повреждённый формат файла")
            require(detected.kind == expectedKind) {
                "Выбранный файл имеет тип ${detected.kind}, ожидался $expectedKind"
            }
            // Content magic is authoritative. Provider MIME is only a hint and is never persisted.
            if (reportedMimeType != null) require(reportedMimeType.isNotBlank()) { "Пустой MIME type" }

            val hash = digest.digest().toHex()
            val id = "a-$hash"
            val target = objectFile(id)
            val meta = metadataFile(id)

            if (target.isFile) {
                require(target.length() == size && sha256(target) == hash) { "Существующий asset $id повреждён" }
                stage.delete()
            } else {
                check(stage.renameTo(target)) { "Не удалось атомарно опубликовать asset" }
                require(target.isFile && target.length() == size) { "Опубликованный asset имеет неверный размер" }
            }

            val existingCreatedAt = readMetadata(meta, verifyFile = false)?.createdAt
            val createdAt = existingCreatedAt ?: System.currentTimeMillis()
            val record = StoredAsset(id, detected.kind, detected.mimeType, size, hash, createdAt, target)
            writeMetadata(record)
            return load(id, verifyContent = false)
        } catch (error: Throwable) {
            stage.delete()
            throw error
        }
    }

    @Synchronized
    fun load(id: String, verifyContent: Boolean = false): StoredAsset {
        require(ASSET_ID.matches(id)) { "Недопустимый assetId" }
        return readMetadata(metadataFile(id), verifyFile = true, verifyContent = verifyContent)
            ?: throw IllegalStateException("Asset metadata отсутствует: $id")
    }

    /** Corrupt metadata is surfaced to the caller; it is never silently omitted from diagnostics. */
    @Synchronized
    fun list(): List<StoredAsset> {
        if (!metadata.isDirectory) return emptyList()
        return metadata.listFiles()
            .orEmpty()
            .mapNotNull { file ->
                when {
                    file.name.endsWith(".json") -> file
                    file.name.endsWith(".json.bak") -> File(metadata, file.name.removeSuffix(".bak"))
                    else -> null
                }
            }
            .distinctBy { it.name }
            .map { file -> readMetadata(file, verifyFile = true) ?: error("Не удалось прочитать ${file.name}") }
            .sortedBy { it.createdAt }
    }

    @Synchronized
    fun verify(id: String): StoredAsset = load(id, verifyContent = true)

    /** Removes only abandoned staging files. Published objects are retained until reference-aware GC exists. */
    @Synchronized
    fun cleanupStaging(olderThanMs: Long = STAGING_MAX_AGE_MS): Int {
        require(olderThanMs >= 0)
        if (!staging.isDirectory) return 0
        val cutoff = System.currentTimeMillis() - olderThanMs
        var removed = 0
        staging.listFiles().orEmpty().forEach { file ->
            if (file.isFile && file.name.endsWith(".part") && file.lastModified() <= cutoff && file.delete()) removed++
        }
        return removed
    }

    private fun writeMetadata(asset: StoredAsset) {
        val json = JSONObject().apply {
            put("version", METADATA_VERSION)
            put("id", asset.id)
            put("kind", asset.kind.name)
            put("mimeType", asset.mimeType)
            put("sizeBytes", asset.sizeBytes)
            put("sha256", asset.sha256)
            put("createdAt", asset.createdAt)
        }.toString()
        val target = metadataFile(asset.id)
        target.parentFile?.mkdirs()
        val atomic = AtomicFile(target)
        val output = atomic.startWrite()
        try {
            output.write(json.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (error: Throwable) {
            atomic.failWrite(output)
            throw error
        }
    }

    private fun readMetadata(
        source: File,
        verifyFile: Boolean,
        verifyContent: Boolean = false
    ): StoredAsset? {
        val atomic = AtomicFile(source)
        if (!atomic.exists()) return null
        val text = atomic.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
        val json = JSONObject(text)
        require(json.getInt("version") == METADATA_VERSION) { "Неподдерживаемая версия asset metadata" }
        val id = json.getString("id")
        val hash = json.getString("sha256")
        val kind = runCatching { AssetKind.valueOf(json.getString("kind")) }
            .getOrElse { throw IllegalArgumentException("Неизвестный тип asset") }
        val mime = json.getString("mimeType")
        val size = json.getLong("sizeBytes")
        val createdAt = json.getLong("createdAt")
        require(ASSET_ID.matches(id) && id == "a-$hash") { "Некорректный assetId/checksum" }
        require(hash.matches(SHA256)) { "Некорректный SHA-256" }
        require(size in 1..MAX_ASSET_BYTES) { "Некорректный размер asset" }
        require(createdAt > 0L) { "Некорректное время создания asset" }
        require(mime == canonicalMime(kind, mime)) { "MIME type не соответствует типу asset" }
        val file = objectFile(id)
        if (verifyFile) {
            require(file.isFile) { "Отсутствуют bytes asset $id" }
            require(file.length() == size) { "Размер asset $id не совпадает с metadata" }
            if (verifyContent) {
                require(sha256(file) == hash) { "Checksum mismatch: $id" }
                val detected = detectFile(file) ?: throw IllegalArgumentException("Asset $id имеет повреждённый magic header")
                require(detected.kind == kind && detected.mimeType == mime) { "Тип bytes asset $id не совпадает с metadata" }
            }
        }
        return StoredAsset(id, kind, mime, size, hash, createdAt, file)
    }

    private fun objectFile(id: String) = File(objects, "$id.bin")
    private fun metadataFile(id: String) = File(metadata, "$id.json")

    private data class DetectedType(val kind: AssetKind, val mimeType: String)

    private fun detectFile(file: File): DetectedType? {
        val header = ByteArray(HEADER_BYTES)
        val count = file.inputStream().use { it.read(header) }.coerceAtLeast(0)
        return detect(header, count)
    }

    private fun detect(header: ByteArray, size: Int): DetectedType? {
        fun ascii(offset: Int, value: String): Boolean {
            if (offset < 0 || offset + value.length > size) return false
            return value.indices.all { header[offset + it].toInt() and 0xff == value[it].code }
        }
        if (size >= 5) {
            // PDF headers are normally at byte 0, but ISO 32000 readers allow leading bytes.
            val last = minOf(size - 5, 1024)
            for (offset in 0..last) if (ascii(offset, "%PDF-")) return DetectedType(AssetKind.PDF, "application/pdf")
        }
        if (size >= 8 && header.sliceArray(0 until 8).contentEquals(PNG_MAGIC)) {
            return DetectedType(AssetKind.IMAGE, "image/png")
        }
        if (size >= 3 && (header[0].toInt() and 0xff) == 0xff && (header[1].toInt() and 0xff) == 0xd8 && (header[2].toInt() and 0xff) == 0xff) {
            return DetectedType(AssetKind.IMAGE, "image/jpeg")
        }
        if (size >= 12 && ascii(0, "RIFF") && ascii(8, "WEBP")) {
            return DetectedType(AssetKind.IMAGE, "image/webp")
        }
        return null
    }

    private fun canonicalMime(kind: AssetKind, mime: String): String = when (kind) {
        AssetKind.PDF -> require(mime == "application/pdf") { "PDF asset имеет неверный MIME" }.let { mime }
        AssetKind.IMAGE -> require(mime in IMAGE_MIME_TYPES) { "Image asset имеет неподдерживаемый MIME" }.let { mime }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    companion object {
        const val MAX_ASSET_BYTES = 512L * 1024L * 1024L
        private const val MIN_FREE_SPACE_AFTER_IMPORT = 16L * 1024L * 1024L
        private const val FREE_SPACE_CHECK_INTERVAL = 8L * 1024L * 1024L
        private const val COPY_BUFFER_BYTES = 64 * 1024
        private const val HEADER_BYTES = 4096
        private const val METADATA_VERSION = 1
        private const val STAGING_MAX_AGE_MS = 24L * 60L * 60L * 1000L
        private val ASSET_ID = Regex("a-[0-9a-f]{64}")
        private val SHA256 = Regex("[0-9a-f]{64}")
        private val IMAGE_MIME_TYPES = setOf("image/png", "image/jpeg", "image/webp")
        private val PNG_MAGIC = byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
        )
    }
}
