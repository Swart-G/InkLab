package dev.swart.inklab.recognition.mlkit

import android.content.Context
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.recognition.Ink
import dev.swart.inklab.core.model.InkStroke
import dev.swart.inklab.core.recognition.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class MlKitDigitalInkProvider : RecognitionProvider {
    override val id = "mlkit-ru"
    override val displayName = "Google Digital Ink"
    override val subtitle = "Русский · штрихи · локально после загрузки"
    override val capabilities = RecognitionCapabilities(
        text = true, math = false, acceptsStrokes = true, russian = true
    )

    private val modelIdentifier by lazy {
        DigitalInkRecognitionModelIdentifier.fromLanguageTag("ru-RU")
            ?: error("ML Kit has no ru-RU model identifier")
    }

    private val model by lazy {
        DigitalInkRecognitionModel.builder(modelIdentifier).build()
    }

    override fun state(context: Context): ProviderState = ProviderState.MODEL_REQUIRED

    override suspend fun prepare(context: Context): Result<Unit> = runCatching {
        val manager = RemoteModelManager.getInstance()
        suspendCancellableCoroutine { cont ->
            manager.download(model, DownloadConditions.Builder().build())
                .addOnSuccessListener { if (cont.isActive) cont.resume(Unit) }
                .addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
        }
    }

    override suspend fun recognize(
        context: Context,
        strokes: List<InkStroke>,
        mode: RecognitionMode
    ): RecognitionResult {
        require(mode == RecognitionMode.TEXT)
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
        val started = System.currentTimeMillis()
        val response = suspendCancellableCoroutine { cont ->
            recognizer.recognize(ink)
                .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                .addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
        }
        recognizer.close()
        val candidates = response.candidates.map { it.text }
        return RecognitionResult(
            primary = candidates.firstOrNull().orEmpty(),
            candidates = candidates,
            latencyMs = System.currentTimeMillis() - started,
            providerId = id,
            note = "ML Kit Digital Ink · ru-RU"
        )
    }
}
