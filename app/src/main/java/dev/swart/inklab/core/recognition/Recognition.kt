package dev.swart.inklab.core.recognition

import android.content.Context
import dev.swart.inklab.core.model.InkStroke

enum class RecognitionMode { TEXT, MATH }
enum class ProviderState { READY, MODEL_REQUIRED, SDK_REQUIRED, UNAVAILABLE }

data class RecognitionCapabilities(
    val text: Boolean,
    val math: Boolean,
    val acceptsStrokes: Boolean = true,
    val acceptsBitmap: Boolean = false,
    val russian: Boolean = false,
    val offlineAfterSetup: Boolean = true
)

data class RecognitionResult(
    val primary: String,
    val candidates: List<String> = emptyList(),
    val latencyMs: Long,
    val providerId: String,
    val confidence: Float? = null,
    val note: String? = null
)

interface RecognitionProvider {
    val id: String
    val displayName: String
    val subtitle: String
    val capabilities: RecognitionCapabilities
    val downloadSizeBytes: Long? get() = null
    val licenseLabel: String? get() = null
    val isPackageBundled: Boolean get() = false
    fun state(context: Context): ProviderState
    suspend fun prepare(context: Context, onProgress: (Float) -> Unit = {}): Result<Unit> = Result.success(Unit)
    suspend fun remove(context: Context): Result<Unit> = Result.success(Unit)
    suspend fun recognize(context: Context, strokes: List<InkStroke>, mode: RecognitionMode): RecognitionResult
}

class RecognitionRegistry(private val providers: List<RecognitionProvider>) {
    fun all() = providers
    fun compatible(mode: RecognitionMode) = providers.filter {
        when (mode) {
            RecognitionMode.TEXT -> it.capabilities.text
            RecognitionMode.MATH -> it.capabilities.math
        }
    }
    private val languages = mutableMapOf<String, dev.swart.inklab.recognition.mlkit.MlKitDigitalInkProvider>()
    fun language(tag: String): dev.swart.inklab.recognition.mlkit.MlKitDigitalInkProvider {
        val canonical = com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier.fromLanguageTag(tag)?.languageTag ?: tag
        return languages.getOrPut(canonical) { dev.swart.inklab.recognition.mlkit.MlKitDigitalInkProvider(canonical) }
    }
    fun get(id: String): RecognitionProvider? = if (id.startsWith("mlkit-")) language(if (id == "mlkit-ru") "ru-RU" else id.removePrefix("mlkit-")) else providers.firstOrNull { it.id == id }
}
