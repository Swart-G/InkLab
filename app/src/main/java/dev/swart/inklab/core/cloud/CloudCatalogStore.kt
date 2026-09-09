package dev.swart.inklab.core.cloud

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Atomic local mirror of immutable cloud metadata. Revision/tombstone/folder event IDs are immutable:
 * seeing the same ID with different payload is treated as corruption/conflict, never last-write-wins.
 */
class CloudCatalogStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "cloud-v1/catalog.json").apply { parentFile?.mkdirs() })

    data class Snapshot(
        val revisions: List<CloudRevision> = emptyList(),
        val tombstones: List<CloudTombstone> = emptyList(),
        val folderEvents: List<CloudFolderEvent> = emptyList(),
        val pageToken: String? = null
    )

    @Synchronized fun load(): Snapshot = read()

    @Synchronized
    fun merge(
        revisions: Collection<CloudRevision> = emptyList(),
        tombstones: Collection<CloudTombstone> = emptyList(),
        folderEvents: Collection<CloudFolderEvent> = emptyList(),
        pageToken: String? = null
    ): Snapshot {
        val old = read()
        val mergedRevisions = immutableMerge(old.revisions, revisions, CloudRevision::revisionId)
        CloudCausality.validate(mergedRevisions)
        val mergedTombstones = immutableMerge(old.tombstones, tombstones, CloudTombstone::eventId)
        val mergedFolders = immutableMerge(old.folderEvents, folderEvents, CloudFolderEvent::eventId)
        val next = Snapshot(mergedRevisions, mergedTombstones, mergedFolders, pageToken ?: old.pageToken)
        write(next)
        return next
    }

    @Synchronized
    fun clearCursor() {
        val old = read()
        write(old.copy(pageToken = null))
    }

    private fun <T> immutableMerge(old: List<T>, fresh: Collection<T>, id: (T) -> String): List<T> {
        val result = LinkedHashMap<String, T>()
        old.forEach { item -> result[id(item)] = item }
        fresh.forEach { item ->
            val key = id(item)
            val previous = result[key]
            require(previous == null || previous == item) { "Immutable cloud event $key изменил payload" }
            result[key] = item
        }
        return result.values.toList()
    }

    private fun read(): Snapshot {
        val text = runCatching { file.openRead().bufferedReader().use { it.readText() } }.getOrNull() ?: return Snapshot()
        val root = JSONObject(text)
        require(root.getInt("version") == VERSION) { "Неподдерживаемая версия cloud catalog" }
        return Snapshot(
            revisions = root.getJSONArray("revisions").objects(::revisionFromJson),
            tombstones = root.getJSONArray("tombstones").objects(::tombstoneFromJson),
            folderEvents = root.getJSONArray("folderEvents").objects(::folderFromJson),
            pageToken = if (root.isNull("pageToken")) null else root.getString("pageToken")
        ).also { CloudCausality.validate(it.revisions) }
    }

    private fun write(snapshot: Snapshot) {
        val root = JSONObject().apply {
            put("version", VERSION)
            put("revisions", JSONArray().apply { snapshot.revisions.forEach { put(it.toJson()) } })
            put("tombstones", JSONArray().apply { snapshot.tombstones.forEach { put(it.toJson()) } })
            put("folderEvents", JSONArray().apply { snapshot.folderEvents.forEach { put(it.toJson()) } })
            put("pageToken", snapshot.pageToken ?: JSONObject.NULL)
        }
        val stream = file.startWrite()
        try {
            stream.write(root.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Throwable) {
            file.failWrite(stream)
            throw error
        }
    }

    private fun CloudRevision.toJson() = JSONObject().apply {
        put("revisionId", revisionId); put("documentId", documentId); put("packageSha256", packageSha256)
        put("packageSize", packageSize); put("parents", JSONArray(parents.toList()))
        put("snapshotSequence", snapshotSequence); put("createdAt", createdAt); put("deviceId", deviceId)
    }

    private fun CloudTombstone.toJson() = JSONObject().apply {
        put("eventId", eventId); put("documentId", documentId); put("parents", JSONArray(parents.toList()))
        put("createdAt", createdAt); put("deviceId", deviceId)
    }

    private fun CloudFolderEvent.toJson() = JSONObject().apply {
        put("eventId", eventId); put("folderId", folderId); put("parentFolderId", parentFolderId ?: JSONObject.NULL)
        put("title", title ?: JSONObject.NULL); put("deleted", deleted); put("parents", JSONArray(parents.toList()))
        put("createdAt", createdAt); put("deviceId", deviceId)
    }

    private fun revisionFromJson(json: JSONObject) = CloudRevision(
        revisionId = json.getString("revisionId"), documentId = json.getString("documentId"),
        packageSha256 = json.getString("packageSha256"), packageSize = json.getLong("packageSize"),
        parents = json.getJSONArray("parents").strings(), snapshotSequence = json.getLong("snapshotSequence"),
        createdAt = json.getLong("createdAt"), deviceId = json.getString("deviceId")
    )

    private fun tombstoneFromJson(json: JSONObject) = CloudTombstone(
        eventId = json.getString("eventId"), documentId = json.getString("documentId"),
        parents = json.getJSONArray("parents").strings(), createdAt = json.getLong("createdAt"), deviceId = json.getString("deviceId")
    )

    private fun folderFromJson(json: JSONObject) = CloudFolderEvent(
        eventId = json.getString("eventId"), folderId = json.getString("folderId"),
        parentFolderId = if (json.isNull("parentFolderId")) null else json.getString("parentFolderId"),
        title = if (json.isNull("title")) null else json.getString("title"), deleted = json.getBoolean("deleted"),
        parents = json.getJSONArray("parents").strings(), createdAt = json.getLong("createdAt"), deviceId = json.getString("deviceId")
    )

    private fun <T> JSONArray.objects(mapper: (JSONObject) -> T): List<T> = List(length()) { mapper(getJSONObject(it)) }
    private fun JSONArray.strings(): Set<String> = List(length()) { getString(it) }.toSet()

    companion object { private const val VERSION = 1 }
}
