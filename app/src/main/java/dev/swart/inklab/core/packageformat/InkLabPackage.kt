package dev.swart.inklab.core.packageformat

import android.content.Context
import android.net.Uri
import dev.swart.inklab.audio.*
import dev.swart.inklab.core.model.*
import dev.swart.inklab.core.storage.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.*

enum class InkLabImportMode { RESTORE, COPY }
data class InkLabImportResult(val boards: List<InkBoard>, val folders: List<InkFolder>, val sourcePackageId: String)

/** Versioned portable snapshot. Legacy backup-v2 remains handled by DocumentTransfer.restore. */
class InkLabPackage(private val context: Context) {
    private val repo = BoardRepository(context)
    private val assets = AssetStore(context)
    private val audio = AudioStore(context)

    fun write(boards: List<InkBoard>, folders: List<InkFolder>, uri: Uri, includeAudio: Boolean = true) {
        require(boards.isNotEmpty())
        val boardIds = boards.map { it.id }.toSet()
        require(boardIds.size == boards.size)
        val assetRecords = boards.flatMap { it.pages + it.trashedPages }.mapNotNull { it.background?.assetId }
            .distinct().map(assets::verify)
        val notes = if (includeAudio) audio.list().filter { it.boardId in boardIds }.map { note ->
            if (note.id == AudioHub.activeId || note.status == "recording") note.copy(
                status = "interrupted", error = "В snapshot включены только завершённые аудиофрагменты."
            ) else note
        } else emptyList()
        val files = JSONArray()
        assetRecords.forEach { a -> files.put(fileRecord("assets/${a.id}.bin", a.file, "asset", a.kind.name, a.mimeType)) }
        notes.forEach { note -> note.segments.forEach { s ->
            val f = audio.file(s.file); require(f.isFile)
            files.put(fileRecord("audio/${note.id}/${s.file}", f, "audio"))
        } }
        val manifest = JSONObject().apply {
            put("format", FORMAT); put("version", VERSION); put("packageId", UUID.randomUUID().toString())
            put("boards", repo.encode(boards)); put("folders", encodeFolders(folders)); put("audio", JSONArray().apply { notes.forEach { put(audio.encode(it)) } })
            put("files", files)
        }
        context.contentResolver.openOutputStream(uri, "wt")?.use { raw -> ZipOutputStream(raw.buffered()).use { zip ->
            put(zip, MANIFEST, manifest.toString().toByteArray())
            assetRecords.forEach { putFile(zip, "assets/${it.id}.bin", it.file) }
            notes.forEach { n -> n.segments.forEach { s -> putFile(zip, "audio/${n.id}/${s.file}", audio.file(s.file)) } }
        } } ?: error("Не удалось открыть .inklab для записи")
    }

    fun read(uri: Uri, mode: InkLabImportMode): InkLabImportResult {
        val stage = File(context.cacheDir, "inklab-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val manifest = extract(uri, stage)
            require(manifest.getString("format") == FORMAT && manifest.getInt("version") == VERSION)
            val boards = repo.decode(manifest.getString("boards"))
            val folders = decodeFolders(manifest.optJSONArray("folders") ?: JSONArray())
            val records = manifest.getJSONArray("files").let { a -> List(a.length()) { a.getJSONObject(it) } }
            require(records.map { it.getString("path") }.distinct().size == records.size)
            records.forEach { r ->
                val f = File(stage, r.getString("path")); require(f.isFile && f.length() == r.getLong("size")); require(hash(f) == r.getString("sha256"))
            }
            val refs = boards.flatMap { it.pages + it.trashedPages }.mapNotNull { it.background?.assetId }.toSet()
            refs.forEach { id ->
                val r = records.singleOrNull { it.getString("path") == "assets/$id.bin" } ?: error("Нет asset $id")
                val installed = assets.import(File(stage, r.getString("path")).inputStream(), AssetKind.valueOf(r.getString("assetKind")), r.getString("mimeType"))
                require(installed.id == id)
            }
            val remap = Remap(mode)
            val outFolders = folders.map(remap::folder)
            val outBoards = boards.map(remap::board)
            val notes = manifest.optJSONArray("audio") ?: JSONArray()
            repeat(notes.length()) { i ->
                val source = audio.decode(notes.getJSONObject(i)); require(boards.any { it.id == source.boardId })
                val mapped = remap.note(source)
                val segments = source.segments.map { s ->
                    val src = File(stage, "audio/${source.id}/${s.file}"); require(src.isFile)
                    val name = if (mode == InkLabImportMode.RESTORE) s.file else "${UUID.randomUUID()}.m4a"
                    val dst = audio.file(name); if (!dst.exists()) src.copyTo(dst) else require(hash(src) == hash(dst))
                    AudioSegment(name, s.durationMs)
                }
                require(mapped.id != AudioHub.activeId)
                audio.save(mapped.copy(segments = segments))
            }
            return InkLabImportResult(outBoards, outFolders, manifest.getString("packageId"))
        } finally { stage.deleteRecursively() }
    }

    private fun extract(uri: Uri, stage: File): JSONObject {
        var manifest: JSONObject? = null; var bytes = 0L; var count = 0; val seen = mutableSetOf<String>()
        context.contentResolver.openInputStream(uri)?.use { raw -> ZipInputStream(raw.buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break; val path = e.name
                require(++count <= MAX_ENTRIES && seen.add(path) && safe(path) && !e.isDirectory)
                if (count == 1) require(path == MANIFEST)
                if (path == MANIFEST) {
                    val data = zip.readBytesLimited(MAX_MANIFEST); bytes += data.size; require(bytes <= MAX_BYTES)
                    manifest = JSONObject(data.toString(Charsets.UTF_8))
                } else {
                    val target = File(stage, path).apply { parentFile?.mkdirs() }
                    target.outputStream().use { out ->
                        val b=ByteArray(64*1024)
                        while(true){ val n=zip.read(b); if(n<0) break; if(n>0){ bytes+=n; require(bytes<=MAX_BYTES && stage.usableSpace>16L*1024*1024); out.write(b,0,n) } }
                    }
                }
            }
        } } ?: error("Не удалось открыть .inklab")
        val root = manifest ?: error("Нет manifest.json")
        val declared = root.getJSONArray("files").let { a -> List(a.length()) { a.getJSONObject(it).getString("path") }.toSet() }
        require(seen - MANIFEST == declared)
        return root
    }

    private fun ZipInputStream.readBytesLimited(limit: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream(); val b = ByteArray(64 * 1024)
        while (true) { val n = read(b); if (n < 0) break; if (n > 0) { require(out.size().toLong() + n <= limit); out.write(b, 0, n) } }
        return out.toByteArray()
    }

    private fun fileRecord(path: String, file: File, kind: String, assetKind: String? = null, mime: String? = null) = JSONObject().apply {
        put("path", path); put("size", file.length()); put("sha256", hash(file)); put("kind", kind)
        put("assetKind", assetKind ?: JSONObject.NULL); put("mimeType", mime ?: JSONObject.NULL)
    }
    private fun put(zip: ZipOutputStream, path: String, bytes: ByteArray) { zip.putNextEntry(ZipEntry(path)); zip.write(bytes); zip.closeEntry() }
    private fun putFile(zip: ZipOutputStream, path: String, file: File) { require(file.isFile); zip.putNextEntry(ZipEntry(path)); file.inputStream().use { it.copyTo(zip,64*1024) }; zip.closeEntry() }
    private fun safe(path: String) = path == MANIFEST || (path.length <= 240 && !path.startsWith('/') && !path.contains("\\") && path.split('/').none { it == ".." || it == "." || it.isBlank() } && (path.startsWith("assets/") || path.startsWith("audio/")))
    private fun hash(file: File): String { val d = MessageDigest.getInstance("SHA-256"); file.inputStream().use { input -> val b=ByteArray(65536); while(true){val n=input.read(b); if(n<0)break; if(n>0)d.update(b,0,n)} }; return d.digest().joinToString(""){"%02x".format(it)} }
    private fun encodeFolders(v: List<InkFolder>) = JSONArray().apply { v.forEach { f -> put(JSONObject().put("id",f.id).put("title",f.title).put("parentId",f.parentId?:JSONObject.NULL).put("createdAt",f.createdAt).put("updatedAt",f.updatedAt)) } }
    private fun decodeFolders(a: JSONArray) = List(a.length()) { i -> a.getJSONObject(i).let { InkFolder(it.getString("id"),it.getString("title"),if(it.isNull("parentId"))null else it.getString("parentId"),it.getLong("createdAt"),it.getLong("updatedAt")) } }

    private class Remap(private val mode: InkLabImportMode) {
        private val ids=mutableMapOf<String,String>(); private fun id(v:String)=if(mode==InkLabImportMode.RESTORE)v else ids.getOrPut(v){UUID.randomUUID().toString()}
        fun folder(v:InkFolder)=v.copy(id=id(v.id),parentId=v.parentId?.let(::id))
        fun board(v:InkBoard)=v.copy(id=id(v.id),title=if(mode==InkLabImportMode.COPY)v.title+" (копия)" else v.title,folderId=v.folderId?.let(::id),pages=v.pages.map(::page),trashedPages=v.trashedPages.map(::page))
        private fun page(v:InkPage)=v.copy(id=id(v.id),strokes=v.strokes.map{it.copy(id=id(it.id))},convertedObjects=v.convertedObjects.map{c->c.copy(id=id(c.id),sourceStrokes=c.sourceStrokes.map{it.copy(id=id(it.id))})})
        fun note(v:AudioNote)=v.copy(id=id(v.id),boardId=id(v.boardId),markers=v.markers.map{it.copy(pageId=id(it.pageId))})
    }

    companion object { private const val FORMAT="inklab-package"; private const val VERSION=3; private const val MANIFEST="manifest.json"; private const val MAX_ENTRIES=20_000; private const val MAX_MANIFEST=16L*1024*1024; private const val MAX_BYTES=2L*1024*1024*1024 }
}
