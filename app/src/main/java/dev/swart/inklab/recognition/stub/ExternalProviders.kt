package dev.swart.inklab.recognition.stub

import android.content.Context
import dev.swart.inklab.core.model.InkStroke
import dev.swart.inklab.core.recognition.*

class ExternalProvider(
    override val id: String,
    override val displayName: String,
    override val subtitle: String,
    override val capabilities: RecognitionCapabilities,
    private val requiredState: ProviderState,
    private val setupHint: String
) : RecognitionProvider {
    override fun state(context: Context) = requiredState

    override suspend fun recognize(
        context: Context,
        strokes: List<InkStroke>,
        mode: RecognitionMode
    ): RecognitionResult = RecognitionResult(
        primary = "",
        latencyMs = 0,
        providerId = id,
        note = setupHint
    )
}

fun externalProviders() = listOf(
    ExternalProvider(
        id = "myscript-text",
        displayName = "MyScript Text",
        subtitle = "Русский · штрихи · коммерческий SDK",
        capabilities = RecognitionCapabilities(text = true, math = false, russian = true),
        requiredState = ProviderState.SDK_REQUIRED,
        setupHint = "Добавьте iink SDK и recognition resources. См. integrations/MYSCRIPT.md"
    ),
    ExternalProvider(
        id = "onnx-htr",
        displayName = "ONNX HTR",
        subtitle = "Эксперимент · bitmap · свои веса",
        capabilities = RecognitionCapabilities(text = true, math = false, acceptsStrokes = false, acceptsBitmap = true, russian = true),
        requiredState = ProviderState.MODEL_REQUIRED,
        setupHint = "Добавьте ONNX-модель русского HTR и токенизатор. См. integrations/ONNX.md"
    ),
    ExternalProvider(
        id = "myscript-math",
        displayName = "MyScript Math",
        subtitle = "Формулы · штрихи · коммерческий SDK",
        capabilities = RecognitionCapabilities(text = false, math = true),
        requiredState = ProviderState.SDK_REQUIRED,
        setupHint = "Добавьте iink SDK + math resources. См. integrations/MYSCRIPT.md"
    ),
)
