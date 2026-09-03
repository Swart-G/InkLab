package dev.swart.inklab.core.models

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class ModelArtifact(
    val path: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String
)

data class ModelPackage(
    val id: String,
    val displayName: String,
    val license: String,
    val sourceUrl: String,
    val artifacts: List<ModelArtifact>
) {
    val sizeBytes: Long get() = artifacts.sumOf { it.sizeBytes }
}

object ModelCatalog {
    private const val MATHORIUM_COMMIT = "f2411279317da9cf3a12ceb453c9a96aca5b4743"
    private const val ROOT = "https://raw.githubusercontent.com/samirahmed007/mathorium/$MATHORIUM_COMMIT/public/models/pix2text-mfr-quantized"

    val pix2Text = ModelPackage(
        id = "pix2text-math",
        displayName = "Pix2Text MFR int8",
        license = "MIT",
        sourceUrl = "https://github.com/samirahmed007/mathorium",
        artifacts = listOf(
            ModelArtifact(
                path = "encoder.onnx",
                url = "$ROOT/onnx/encoder_model.onnx",
                sizeBytes = 23_083_189,
                sha256 = "5e5141ed5f6e05851b1a38b6df85fe17c1dcb779358729fa707f9ee36b7b9dd9"
            ),
            ModelArtifact(
                path = "decoder-int8.onnx",
                url = "$ROOT/onnx/decoder_model_int8.onnx",
                sizeBytes = 7_989_844,
                sha256 = "1b81c99876de606631449eacf1af32ce372f5641ebed3216e0208bde2bdbeaa6"
            ),
            ModelArtifact(
                path = "tokenizer.json",
                url = "$ROOT/tokenizer.json",
                sizeBytes = 39_161,
                sha256 = "3e2ab757277d22639bec28c9d7972e352d3d1dba223051fa674002dc5ab64df3"
            )
        )
    )
}

class ModelPackageStore(context: Context) {
    private val root = File(context.noBackupFilesDir, "ocr-models")

    fun directory(modelPackage: ModelPackage) = File(root, modelPackage.id)

    fun isInstalled(modelPackage: ModelPackage): Boolean = modelPackage.artifacts.all { artifact ->
        val target = File(directory(modelPackage), artifact.path)
        target.isFile && target.length() == artifact.sizeBytes
    }

    suspend fun install(modelPackage: ModelPackage, onProgress: (Float) -> Unit = {}): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val directory = directory(modelPackage).apply { mkdirs() }
                var completed = 0L
                modelPackage.artifacts.forEach { artifact ->
                    val target = File(directory, artifact.path)
                    if (target.isFile && target.length() == artifact.sizeBytes && target.sha256() == artifact.sha256) {
                        completed += artifact.sizeBytes
                        onProgress(completed.toFloat() / modelPackage.sizeBytes)
                        return@forEach
                    }
                    val partial = File(directory, "${artifact.path}.part")
                    partial.delete()
                    val connection = (URL(artifact.url).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 20_000
                        readTimeout = 30_000
                        instanceFollowRedirects = true
                    }
                    try {
                        connection.connect()
                        check(connection.responseCode in 200..299) { "HTTP ${connection.responseCode} для ${artifact.path}" }
                        connection.inputStream.buffered().use { input ->
                            partial.outputStream().buffered().use { output ->
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                var artifactRead = 0L
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    output.write(buffer, 0, read)
                                    artifactRead += read
                                    onProgress(((completed + artifactRead).toFloat() / modelPackage.sizeBytes).coerceIn(0f, 1f))
                                }
                            }
                        }
                    } finally {
                        connection.disconnect()
                    }
                    check(partial.length() == artifact.sizeBytes) { "Неверный размер ${artifact.path}" }
                    check(partial.sha256() == artifact.sha256) { "SHA-256 не совпадает для ${artifact.path}" }
                    check(partial.renameTo(target) || run {
                        partial.copyTo(target, overwrite = true)
                        partial.delete()
                    })
                    completed += artifact.sizeBytes
                }
                onProgress(1f)
            }.onFailure {
                directory(modelPackage).walkTopDown().filter { file -> file.name.endsWith(".part") }.forEach(File::delete)
            }
        }

    suspend fun remove(modelPackage: ModelPackage): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            directory(modelPackage).deleteRecursively()
            Unit
        }
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
