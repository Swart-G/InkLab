package dev.swart.inklab.recognition.mlkit

import android.content.Context
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.recognition.Ink
import com.google.mlkit.vision.digitalink.recognition.RecognitionContext
import com.google.mlkit.vision.digitalink.recognition.WritingArea
import dev.swart.inklab.core.model.InkStroke
import dev.swart.inklab.core.recognition.*
import kotlinx.coroutines.suspendCancellableCoroutine
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class MlKitDigitalInkProvider(val languageTag: String = "ru-RU") : RecognitionProvider {
    var wifiOnly = true
    var ready by androidx.compose.runtime.mutableStateOf(false)
        private set
    override val id = "mlkit-$languageTag"
    override val displayName = "Google Digital Ink"
    override val subtitle = java.util.Locale.forLanguageTag(languageTag).getDisplayName(java.util.Locale.forLanguageTag("ru"))
    override val capabilities = RecognitionCapabilities(
        text = true, math = false, acceptsStrokes = true, russian = languageTag.startsWith("ru")
    )
    override val downloadSizeBytes = 20L * 1024L * 1024L
    override val licenseLabel = "Google ML Kit Terms"

    private val modelIdentifier by lazy {
        DigitalInkRecognitionModelIdentifier.fromLanguageTag(languageTag)
            ?: error("ML Kit не поддерживает $languageTag")
    }

    private val model by lazy {
        DigitalInkRecognitionModel.builder(modelIdentifier).build()
    }

    override fun state(context: Context): ProviderState = if (ready) ProviderState.READY else ProviderState.MODEL_REQUIRED

    suspend fun refresh() {
        ready = suspendCancellableCoroutine { cont ->
            RemoteModelManager.getInstance().isModelDownloaded(model)
                .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                .addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
        }
    }

    override suspend fun prepare(context: Context, onProgress: (Float) -> Unit): Result<Unit> = runCatching {
        onProgress(0.05f)
        val manager = RemoteModelManager.getInstance()
        var downloaded = suspendCancellableCoroutine { cont ->
            manager.isModelDownloaded(model)
                .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                .addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
        }
        if (!downloaded) {
            suspendCancellableCoroutine { cont ->
                manager.download(model, DownloadConditions.Builder().apply { if (wifiOnly) requireWifi() }.build())
                    .addOnSuccessListener { if (cont.isActive) cont.resume(Unit) }
                    .addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
            }
            downloaded = suspendCancellableCoroutine { cont ->
                manager.isModelDownloaded(model)
                    .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                    .addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
            }
        }
        check(downloaded) { "ML Kit не подтвердил установку модели" }
        context.getSharedPreferences("models", Context.MODE_PRIVATE).edit().putBoolean(id, true).apply()
        ready = true
        onProgress(1f)
    }

    override suspend fun remove(context: Context): Result<Unit> = runCatching {
        val manager = RemoteModelManager.getInstance()
        suspendCancellableCoroutine { cont ->
            manager.deleteDownloadedModel(model)
                .addOnSuccessListener { if (cont.isActive) cont.resume(Unit) }
                .addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
        }
        context.getSharedPreferences("models", Context.MODE_PRIVATE).edit().putBoolean(id, false).apply()
        ready = false
    }

    override suspend fun recognize(
        context: Context,
        strokes: List<InkStroke>,
        mode: RecognitionMode
    ): RecognitionResult {
        require(mode == RecognitionMode.TEXT)
        check(strokes.any { it.points.isNotEmpty() }) { "Нет штрихов для распознавания" }
        refresh()
        check(ready) { "Сначала загрузите язык «$subtitle» в разделе Языки распознавания." }
        val ink = Ink.builder().apply {
            strokes.forEach { stroke ->
                val builder = Ink.Stroke.builder()
                stroke.points.forEach { p -> builder.addPoint(Ink.Point.create(p.x, p.y, p.timestamp)) }
                addStroke(builder.build())
            }
        }.build()

        val recognizer = DigitalInkRecognition.getClient(
            DigitalInkRecognizerOptions.builder(model).build()
        )
        val points = strokes.flatMap { it.points }
        val writingArea = WritingArea(
            (points.maxOf { it.x } - points.minOf { it.x }).coerceAtLeast(1f),
            (points.maxOf { it.y } - points.minOf { it.y }).coerceAtLeast(1f)
        )
        val recognitionContext = RecognitionContext.builder()
            .setPreContext("")
            .setWritingArea(writingArea)
            .build()
        val started = System.currentTimeMillis()
        val response = try {
            suspendCancellableCoroutine { cont ->
                recognizer.recognize(ink, recognitionContext)
                    .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                    .addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
            }
        } finally {
            recognizer.close()
        }
        val candidates = response.candidates.map { it.text }
        return RecognitionResult(
            primary = candidates.firstOrNull().orEmpty(),
            candidates = candidates,
            latencyMs = System.currentTimeMillis() - started,
            providerId = id,
            note = "ML Kit Digital Ink · $languageTag"
        )
    }
}
