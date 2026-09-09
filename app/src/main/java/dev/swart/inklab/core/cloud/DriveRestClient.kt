package dev.swart.inklab.core.cloud

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.min

fun interface DriveAccessTokenProvider { fun accessToken(): String? }

data class DriveFile(
    val id: String,
    val name: String,
    val mimeType: String,
    val size: Long?,
    val md5Checksum: String?,
    val appProperties: Map<String, String>
)

data class DrivePage(val files: List<DriveFile>, val nextPageToken: String?)

data class ResumableUpload(
    val sessionUrl: String,
    val operationId: String,
    val totalBytes: Long
)

data class UploadProgress(val nextOffset: Long, val completedFile: DriveFile? = null)

/**
 * Minimal Drive v3 transport for the preview sync layer. OAuth UI stays outside this class; only an
 * ephemeral access token enters here. Resumable session URLs are returned to the executor but must
 * never be placed in diagnostics or backups.
 */
class DriveRestClient(private val tokenProvider: DriveAccessTokenProvider) {
    fun ensureAppRoot(): DriveFile {
        val query = "trashed=false and mimeType='application/vnd.google-apps.folder' and appProperties has { key='inklabRoot' and value='v1' }"
        val existing = listAll(query).firstOrNull()
        if (existing != null) return existing
        val metadata = JSONObject()
            .put("name", "InkLab")
            .put("mimeType", "application/vnd.google-apps.folder")
            .put("appProperties", JSONObject().put("inklabRoot", "v1"))
        return createMetadata(metadata)
    }

    fun listAll(query: String, pageSize: Int = 100): List<DriveFile> {
        require(pageSize in 1..1000)
        val result = mutableListOf<DriveFile>()
        var token: String? = null
        do {
            val page = listPage(query, token, pageSize)
            result += page.files
            token = page.nextPageToken
        } while (token != null)
        return result
    }

    fun listPage(query: String, pageToken: String? = null, pageSize: Int = 100): DrivePage {
        require(pageSize in 1..1000)
        val params = linkedMapOf(
            "q" to query,
            "spaces" to "drive",
            "pageSize" to pageSize.toString(),
            "fields" to "nextPageToken,files(id,name,mimeType,size,md5Checksum,appProperties)"
        )
        if (!pageToken.isNullOrBlank()) params["pageToken"] = pageToken
        val json = JSONObject(request("GET", "$DRIVE/files?${encode(params)}"))
        val files = json.optJSONArray("files") ?: JSONArray()
        return DrivePage(
            files = List(files.length()) { files.getJSONObject(it).toDriveFile() },
            nextPageToken = json.optString("nextPageToken").takeIf { it.isNotBlank() }
        )
    }

    fun findByOperationId(operationId: String): DriveFile? {
        require(operationId.matches(SAFE_PROPERTY_VALUE))
        val query = "trashed=false and appProperties has { key='operationId' and value='$operationId' }"
        val matches = listAll(query, 100)
        require(matches.size <= 1) { "Drive содержит несколько результатов одной logical operation" }
        return matches.singleOrNull()
    }

    fun startResumableUpload(
        localFile: File,
        name: String,
        parentId: String,
        operationId: String,
        appProperties: Map<String, String>
    ): ResumableUpload {
        require(localFile.isFile && localFile.length() > 0)
        require(operationId.matches(SAFE_PROPERTY_VALUE))
        require(parentId.isNotBlank())
        findByOperationId(operationId)?.let { existing ->
            throw AlreadyUploaded(existing)
        }
        val properties = JSONObject()
        properties.put("operationId", operationId)
        appProperties.forEach { (key, value) ->
            require(key.matches(SAFE_PROPERTY_KEY) && value.matches(SAFE_PROPERTY_VALUE))
            properties.put(key, value)
        }
        val metadata = JSONObject()
            .put("name", name.take(180))
            .put("parents", JSONArray().put(parentId))
            .put("appProperties", properties)
        val connection = open(
            "POST",
            "$UPLOAD/files?uploadType=resumable&fields=id,name,mimeType,size,md5Checksum,appProperties",
            extraHeaders = mapOf(
                "Content-Type" to "application/json; charset=UTF-8",
                "X-Upload-Content-Type" to "application/octet-stream",
                "X-Upload-Content-Length" to localFile.length().toString()
            )
        )
        connection.doOutput = true
        connection.outputStream.use { it.write(metadata.toString().toByteArray()) }
        val code = connection.responseCode
        val body = readBody(connection)
        if (code !in 200..299) throw DriveHttpException(code, body)
        val location = connection.getHeaderField("Location") ?: error("Drive не вернул resumable session URL")
        validateSessionUrl(location)
        return ResumableUpload(location, operationId, localFile.length())
    }

    fun uploadNextChunk(upload: ResumableUpload, localFile: File, offset: Long, chunkBytes: Int = DEFAULT_CHUNK): UploadProgress {
        require(localFile.isFile && localFile.length() == upload.totalBytes)
        require(offset in 0 until upload.totalBytes)
        require(chunkBytes in 256 * 1024..16 * 1024 * 1024 && chunkBytes % (256 * 1024) == 0)
        validateSessionUrl(upload.sessionUrl)
        val count = min(chunkBytes.toLong(), upload.totalBytes - offset).toInt()
        val end = offset + count - 1
        val connection = openSession(upload.sessionUrl)
        connection.requestMethod = "PUT"
        connection.setRequestProperty("Content-Length", count.toString())
        connection.setRequestProperty("Content-Range", "bytes $offset-$end/${upload.totalBytes}")
        connection.setRequestProperty("Content-Type", "application/octet-stream")
        connection.doOutput = true
        localFile.inputStream().use { input ->
            var skipped = 0L
            while (skipped < offset) {
                val delta = input.skip(offset - skipped)
                if (delta <= 0) error("Не удалось перейти к offset upload")
                skipped += delta
            }
            connection.outputStream.use { output ->
                val buffer = ByteArray(64 * 1024)
                var remaining = count
                while (remaining > 0) {
                    val read = input.read(buffer, 0, min(buffer.size, remaining))
                    require(read > 0) { "Локальный snapshot неожиданно закончился" }
                    output.write(buffer, 0, read)
                    remaining -= read
                }
            }
        }
        val code = connection.responseCode
        val body = readBody(connection)
        return when {
            code == 308 -> {
                val acknowledged = connection.getHeaderField("Range")
                    ?.substringAfterLast('-')?.toLongOrNull()?.plus(1)
                    ?: (offset + count)
                UploadProgress(acknowledged.coerceIn(0, upload.totalBytes))
            }
            code in 200..299 -> UploadProgress(upload.totalBytes, JSONObject(body).toDriveFile())
            else -> throw DriveHttpException(code, body)
        }
    }

    /** Resolve an uncertain final response without starting a second logical upload. */
    fun reconcile(operationId: String): DriveFile? = findByOperationId(operationId)

    fun download(fileId: String, destination: File) {
        require(fileId.isNotBlank())
        destination.parentFile?.mkdirs()
        val connection = open("GET", "$DRIVE/files/${url(fileId)}?alt=media")
        val code = connection.responseCode
        if (code !in 200..299) throw DriveHttpException(code, readBody(connection))
        val partial = File(destination.parentFile, destination.name + ".part")
        try {
            connection.inputStream.use { input -> partial.outputStream().use(input::copyTo) }
            if (destination.exists()) destination.delete()
            check(partial.renameTo(destination)) { "Не удалось атомарно опубликовать download" }
        } catch (error: Throwable) {
            partial.delete()
            throw error
        }
    }

    private fun createMetadata(metadata: JSONObject): DriveFile {
        val body = request(
            "POST",
            "$DRIVE/files?fields=id,name,mimeType,size,md5Checksum,appProperties",
            metadata.toString().toByteArray(),
            "application/json; charset=UTF-8"
        )
        return JSONObject(body).toDriveFile()
    }

    private fun request(method: String, endpoint: String, body: ByteArray? = null, contentType: String? = null): String {
        val connection = open(method, endpoint, contentType?.let { mapOf("Content-Type" to it) }.orEmpty())
        if (body != null) {
            connection.doOutput = true
            connection.outputStream.use { it.write(body) }
        }
        val code = connection.responseCode
        val text = readBody(connection)
        if (code !in 200..299) throw DriveHttpException(code, text)
        return text
    }

    private fun open(method: String, endpoint: String, extraHeaders: Map<String, String> = emptyMap()): HttpURLConnection {
        val token = tokenProvider.accessToken()?.takeIf { it.isNotBlank() } ?: throw AuthorizationRequired()
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 20_000
        connection.readTimeout = 45_000
        connection.setRequestProperty("Authorization", "Bearer $token")
        connection.setRequestProperty("Accept", "application/json")
        extraHeaders.forEach(connection::setRequestProperty)
        return connection
    }

    private fun openSession(endpoint: String): HttpURLConnection {
        validateSessionUrl(endpoint)
        val token = tokenProvider.accessToken()?.takeIf { it.isNotBlank() } ?: throw AuthorizationRequired()
        return (URL(endpoint).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
        }
    }

    private fun validateSessionUrl(value: String) {
        val url = URL(value)
        require(url.protocol == "https" && (url.host == "www.googleapis.com" || url.host.endsWith(".googleapis.com"))) {
            "Недопустимый resumable session host"
        }
    }

    private fun readBody(connection: HttpURLConnection): String {
        val stream = if (connection.responseCode in 200..399) connection.inputStream else connection.errorStream
        return stream?.bufferedReader()?.use { it.readText() }.orEmpty()
    }

    private fun JSONObject.toDriveFile(): DriveFile {
        val props = optJSONObject("appProperties")
        val map = linkedMapOf<String, String>()
        if (props != null) {
            val keys = props.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                map[key] = props.getString(key)
            }
        }
        return DriveFile(
            id = getString("id"),
            name = optString("name"),
            mimeType = optString("mimeType"),
            size = optString("size").toLongOrNull(),
            md5Checksum = optString("md5Checksum").takeIf { it.isNotBlank() },
            appProperties = map
        )
    }

    private fun encode(values: Map<String, String>) = values.entries.joinToString("&") { (key, value) -> "${url(key)}=${url(value)}" }
    private fun url(value: String) = URLEncoder.encode(value, "UTF-8")

    class AuthorizationRequired : IllegalStateException("Требуется повторная авторизация Google Drive")
    class DriveHttpException(val status: Int, rawBody: String) : RuntimeException("Drive HTTP $status: ${CloudDiagnostics.sanitize(rawBody)}")
    class AlreadyUploaded(val file: DriveFile) : IllegalStateException("Операция уже опубликована: ${file.id}")

    companion object {
        private const val DRIVE = "https://www.googleapis.com/drive/v3"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        private const val DEFAULT_CHUNK = 4 * 1024 * 1024
        private val SAFE_PROPERTY_KEY = Regex("[A-Za-z0-9_.-]{1,64}")
        private val SAFE_PROPERTY_VALUE = Regex("[A-Za-z0-9_.:-]{1,124}")
    }
}
