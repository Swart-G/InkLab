package dev.swart.inklab.core.cloud

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Durable, account-scoped queue. WorkManager/foreground code is intentionally only an executor: the
 * authoritative operation state lives here and survives process death/reboot.
 */
class DurableCloudOutbox(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "cloud-v1/outbox.json").apply { parentFile?.mkdirs() })

    @Synchronized
    fun list(accountId: String? = null): List<CloudOperation> = read().filter { accountId == null || it.accountId == accountId }

    @Synchronized
    fun enqueue(operation: CloudOperation): CloudOperation {
        val current = read().toMutableList()
        val same = current.indexOfFirst { it.operationId == operation.operationId }
        if (same >= 0) {
            require(current[same].accountId == operation.accountId) { "operationId уже принадлежит другому аккаунту" }
            return current[same]
        }
        current += operation.copy(state = CloudOperationState.PENDING)
        write(current)
        return operation
    }

    @Synchronized
    fun claim(accountId: String, now: Long = System.currentTimeMillis()): CloudOperation? {
        val current = read().toMutableList()
        val index = current.indexOfFirst {
            it.accountId == accountId &&
                it.state in setOf(CloudOperationState.PENDING, CloudOperationState.RETRY) &&
                it.nextAttemptAt <= now
        }
        if (index < 0) return null
        val claimed = current[index].copy(state = CloudOperationState.RUNNING, lastErrorCode = null)
        current[index] = claimed
        write(current)
        return claimed
    }

    @Synchronized
    fun complete(operationId: String, remoteFileId: String? = null) {
        mutate(operationId) { it.copy(state = CloudOperationState.COMPLETE, remoteFileId = remoteFileId ?: it.remoteFileId, lastErrorCode = null) }
    }

    @Synchronized
    fun retry(operationId: String, code: String, now: Long = System.currentTimeMillis()) {
        mutate(operationId) { operation ->
            val retries = operation.retryCount + 1
            if (retries > MAX_RETRIES) operation.copy(state = CloudOperationState.FAILED, retryCount = retries, lastErrorCode = code)
            else operation.copy(
                state = CloudOperationState.RETRY,
                retryCount = retries,
                nextAttemptAt = now + retryDelayMs(retries),
                lastErrorCode = code
            )
        }
    }

    /** RUNNING means "lease held by the previous process", never a reason to drop the operation. */
    @Synchronized
    fun recoverInterrupted() {
        val current = read()
        val recovered = current.map { operation ->
            if (operation.state == CloudOperationState.RUNNING) operation.copy(state = CloudOperationState.RETRY, nextAttemptAt = 0L)
            else operation
        }
        if (recovered != current) write(recovered)
    }

    @Synchronized
    fun removeCompleted(accountId: String? = null) {
        write(read().filterNot { it.state == CloudOperationState.COMPLETE && (accountId == null || it.accountId == accountId) })
    }

    /** Unlink never moves an old queue to a newly linked account. */
    @Synchronized
    fun cancelAccount(accountId: String) {
        val updated = read().map {
            if (it.accountId == accountId && it.state !in setOf(CloudOperationState.COMPLETE, CloudOperationState.FAILED))
                it.copy(state = CloudOperationState.FAILED, lastErrorCode = "ACCOUNT_UNLINKED")
            else it
        }
        write(updated)
    }

    private fun mutate(operationId: String, transform: (CloudOperation) -> CloudOperation) {
        val current = read().toMutableList()
        val index = current.indexOfFirst { it.operationId == operationId }
        require(index >= 0) { "Операция не найдена: $operationId" }
        current[index] = transform(current[index])
        write(current)
    }

    private fun read(): List<CloudOperation> {
        val text = runCatching { file.openRead().bufferedReader().use { it.readText() } }.getOrNull() ?: return emptyList()
        val root = JSONObject(text)
        require(root.getInt("version") == VERSION) { "Неподдерживаемая версия cloud outbox" }
        val array = root.getJSONArray("operations")
        return List(array.length()) { array.getJSONObject(it).toOperation() }.also { operations ->
            require(operations.map { it.operationId }.distinct().size == operations.size) { "Дубли operationId в outbox" }
        }
    }

    private fun write(operations: List<CloudOperation>) {
        val json = JSONObject().put("version", VERSION).put("operations", JSONArray().apply {
            operations.forEach { put(it.toJson()) }
        }).toString()
        val stream = file.startWrite()
        try {
            stream.write(json.toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Throwable) {
            file.failWrite(stream)
            throw error
        }
    }

    private fun CloudOperation.toJson() = JSONObject().apply {
        put("operationId", operationId)
        put("accountId", accountId)
        put("kind", kind.name)
        put("documentId", documentId ?: JSONObject.NULL)
        put("snapshotSequence", snapshotSequence ?: JSONObject.NULL)
        put("localPath", localPath ?: JSONObject.NULL)
        put("remoteFileId", remoteFileId ?: JSONObject.NULL)
        put("retryCount", retryCount)
        put("nextAttemptAt", nextAttemptAt)
        put("state", state.name)
        put("lastErrorCode", lastErrorCode ?: JSONObject.NULL)
    }

    private fun JSONObject.toOperation() = CloudOperation(
        operationId = getString("operationId"),
        accountId = getString("accountId"),
        kind = CloudOperationKind.valueOf(getString("kind")),
        documentId = nullableString("documentId"),
        snapshotSequence = if (isNull("snapshotSequence")) null else getLong("snapshotSequence"),
        localPath = nullableString("localPath"),
        remoteFileId = nullableString("remoteFileId"),
        retryCount = getInt("retryCount"),
        nextAttemptAt = getLong("nextAttemptAt"),
        state = CloudOperationState.valueOf(getString("state")),
        lastErrorCode = nullableString("lastErrorCode")
    )

    private fun JSONObject.nullableString(key: String) = if (isNull(key)) null else getString(key)

    companion object {
        private const val VERSION = 1
        private const val MAX_RETRIES = 8
        fun retryDelayMs(retryCount: Int): Long = (15_000L shl (retryCount - 1).coerceIn(0, 7)).coerceAtMost(30L * 60L * 1000L)
    }
}
