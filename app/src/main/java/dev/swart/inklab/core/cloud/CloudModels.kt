package dev.swart.inklab.core.cloud

import java.security.MessageDigest

enum class CloudOperationKind { UPLOAD_REVISION, UPLOAD_FOLDER_EVENT, UPLOAD_TOMBSTONE, RECONCILE }
enum class CloudOperationState { PENDING, RUNNING, RETRY, COMPLETE, FAILED }
enum class RevisionRelation { SAME, LOCAL_AHEAD, REMOTE_AHEAD, DIVERGED, UNKNOWN }

data class CloudPreferences(
    val accountId: String? = null,
    val rootFileId: String? = null,
    val selectedDocumentIds: Set<String> = emptySet(),
    val wifiOnly: Boolean = true,
    val includeAudio: Boolean = true,
    val paused: Boolean = false
)

data class CloudOperation(
    val operationId: String,
    val accountId: String,
    val kind: CloudOperationKind,
    val documentId: String? = null,
    val snapshotSequence: Long? = null,
    val localPath: String? = null,
    val remoteFileId: String? = null,
    val retryCount: Int = 0,
    val nextAttemptAt: Long = 0L,
    val state: CloudOperationState = CloudOperationState.PENDING,
    val lastErrorCode: String? = null
) {
    init {
        require(operationId.isNotBlank())
        require(accountId.isNotBlank())
        require(retryCount >= 0)
        require(nextAttemptAt >= 0)
    }

    companion object {
        fun stableId(accountId: String, kind: CloudOperationKind, documentId: String?, snapshotSequence: Long?): String {
            val raw = listOf(accountId, kind.name, documentId.orEmpty(), snapshotSequence?.toString().orEmpty()).joinToString("|")
            val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
            return "op-" + digest.joinToString("") { "%02x".format(it) }
        }
    }
}

data class CloudRevision(
    val revisionId: String,
    val documentId: String,
    val packageSha256: String,
    val packageSize: Long,
    val parents: Set<String>,
    val snapshotSequence: Long,
    val createdAt: Long,
    val deviceId: String
) {
    init {
        require(revisionId.isNotBlank() && documentId.isNotBlank() && deviceId.isNotBlank())
        require(packageSha256.matches(Regex("[0-9a-f]{64}")))
        require(packageSize > 0)
        require(snapshotSequence > 0)
        require(createdAt > 0)
        require(revisionId !in parents)
    }
}

data class CloudTombstone(
    val eventId: String,
    val documentId: String,
    val parents: Set<String>,
    val createdAt: Long,
    val deviceId: String
)

data class CloudFolderEvent(
    val eventId: String,
    val folderId: String,
    val parentFolderId: String?,
    val title: String?,
    val deleted: Boolean,
    val parents: Set<String>,
    val createdAt: Long,
    val deviceId: String
)

/** Causal comparison intentionally ignores device wall-clock ordering. */
object CloudCausality {
    fun validate(revisions: Collection<CloudRevision>): Map<String, CloudRevision> {
        val result = LinkedHashMap<String, CloudRevision>()
        revisions.forEach { revision ->
            val previous = result.putIfAbsent(revision.revisionId, revision)
            require(previous == null || previous == revision) {
                "Один revisionId описывает разные immutable revisions: ${revision.revisionId}"
            }
        }
        result.values.forEach { revision ->
            require(revision.parents.none { it == revision.revisionId })
        }
        return result
    }

    fun relation(localHead: String?, remoteHead: String?, revisions: Collection<CloudRevision>): RevisionRelation {
        if (localHead == null || remoteHead == null) return RevisionRelation.UNKNOWN
        if (localHead == remoteHead) return RevisionRelation.SAME
        val byId = validate(revisions)
        if (localHead !in byId || remoteHead !in byId) return RevisionRelation.UNKNOWN
        return when {
            isAncestor(remoteHead, localHead, byId) -> RevisionRelation.LOCAL_AHEAD
            isAncestor(localHead, remoteHead, byId) -> RevisionRelation.REMOTE_AHEAD
            else -> RevisionRelation.DIVERGED
        }
    }

    fun heads(revisions: Collection<CloudRevision>, documentId: String): Set<String> {
        val byId = validate(revisions.filter { it.documentId == documentId })
        val referenced = byId.values.flatMapTo(mutableSetOf()) { it.parents }
        return byId.keys.filterTo(linkedSetOf()) { it !in referenced }
    }

    private fun isAncestor(ancestor: String, descendant: String, byId: Map<String, CloudRevision>): Boolean {
        val queue = ArrayDeque<String>()
        val seen = mutableSetOf<String>()
        queue.add(descendant)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!seen.add(current)) continue
            val revision = byId[current] ?: continue
            if (ancestor in revision.parents) return true
            revision.parents.forEach { queue.add(it) }
        }
        return false
    }
}
