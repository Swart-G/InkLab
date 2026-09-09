package dev.swart.inklab.core.cloud

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

object CloudDiagnostics {
    private val bearer = Regex("(?i)bearer\\s+[a-z0-9._~+/-]+")
    private val sessionUrl = Regex("https://[^\\s\"']*(?:upload|resumable)[^\\s\"']*", RegexOption.IGNORE_CASE)
    private val accessToken = Regex("(?i)(access_token|refresh_token|id_token)\\s*[=:]\\s*[^,\\s}]+")

    fun snapshot(
        preferences: CloudPreferences,
        operations: Collection<CloudOperation>,
        revisions: Collection<CloudRevision>,
        lastError: String? = null
    ): String = JSONObject().apply {
        put("schema", 1)
        put("linked", preferences.accountId != null)
        put("accountHash", preferences.accountId?.let(::shortHash) ?: JSONObject.NULL)
        put("rootHash", preferences.rootFileId?.let(::shortHash) ?: JSONObject.NULL)
        put("selectedDocuments", preferences.selectedDocumentIds.size)
        put("wifiOnly", preferences.wifiOnly)
        put("includeAudio", preferences.includeAudio)
        put("paused", preferences.paused)
        put("outbox", JSONObject().apply {
            CloudOperationState.entries.forEach { state -> put(state.name.lowercase(), operations.count { it.state == state }) }
        })
        put("revisionCount", revisions.size)
        put("documentsWithMultipleHeads", revisions.groupBy { it.documentId }.count { (documentId, values) -> CloudCausality.heads(values, documentId).size > 1 })
        put("lastError", lastError?.let(::sanitize) ?: JSONObject.NULL)
        put("recentErrorCodes", JSONArray(operations.mapNotNull { it.lastErrorCode }.distinct().takeLast(20)))
    }.toString(2)

    fun sanitize(value: String): String = value
        .replace(bearer, "Bearer <redacted>")
        .replace(sessionUrl, "<resumable-session-redacted>")
        .replace(accessToken) { match -> match.groupValues[1] + "=<redacted>" }
        .take(4_000)

    private fun shortHash(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return bytes.take(6).joinToString("") { "%02x".format(it) }
    }
}
