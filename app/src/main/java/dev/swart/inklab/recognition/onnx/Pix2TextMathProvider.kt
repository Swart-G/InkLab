package dev.swart.inklab.recognition.onnx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import dev.swart.inklab.core.model.InkStroke
import dev.swart.inklab.core.models.ModelCatalog
import dev.swart.inklab.core.models.ModelPackageStore
import dev.swart.inklab.core.recognition.ProviderState
import dev.swart.inklab.core.recognition.RecognitionCapabilities
import dev.swart.inklab.core.recognition.RecognitionMode
import dev.swart.inklab.core.recognition.RecognitionProvider
import dev.swart.inklab.core.recognition.RecognitionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.max

class Pix2TextMathProvider : RecognitionProvider {
    override val id = "pix2text-math"
    override val displayName = "Pix2Text MFR"
    override val subtitle = "Формулы · ONNX int8 · полностью локально"
    override val capabilities = RecognitionCapabilities(
        text = false,
        math = true,
        acceptsStrokes = false,
        acceptsBitmap = true,
        offlineAfterSetup = true
    )
    override val downloadSizeBytes = ModelCatalog.pix2Text.sizeBytes
    override val licenseLabel = ModelCatalog.pix2Text.license

    @Volatile private var engine: Engine? = null

    override fun state(context: Context): ProviderState =
        if (ModelPackageStore(context).isInstalled(ModelCatalog.pix2Text)) ProviderState.READY
        else ProviderState.MODEL_REQUIRED

    override suspend fun prepare(context: Context, onProgress: (Float) -> Unit): Result<Unit> =
        ModelPackageStore(context).install(ModelCatalog.pix2Text, onProgress)

    override suspend fun remove(context: Context): Result<Unit> {
        engine?.close()
        engine = null
        return ModelPackageStore(context).remove(ModelCatalog.pix2Text)
    }

    override suspend fun recognize(
        context: Context,
        strokes: List<InkStroke>,
        mode: RecognitionMode
    ): RecognitionResult = withContext(Dispatchers.Default) {
        require(mode == RecognitionMode.MATH)
        check(strokes.isNotEmpty()) { "Нет штрихов для распознавания" }
        if (state(context) != ProviderState.READY) {
            prepare(context).getOrThrow()
        }
        val activeEngine = engine ?: synchronized(this@Pix2TextMathProvider) {
            engine ?: Engine(ModelPackageStore(context).directory(ModelCatalog.pix2Text)).also { engine = it }
        }
        val bitmap = rasterize(strokes)
        val started = System.currentTimeMillis()
        val latex = try {
            activeEngine.recognize(bitmap)
        } finally {
            bitmap.recycle()
        }
        RecognitionResult(
            primary = latex,
            latencyMs = System.currentTimeMillis() - started,
            providerId = id,
            note = "Pix2Text MFR · ONNX Runtime · offline"
        )
    }

    private fun rasterize(strokes: List<InkStroke>): Bitmap {
        val points = strokes.flatMap { it.points }
        val left = points.minOf { it.x }
        val top = points.minOf { it.y }
        val right = points.maxOf { it.x }
        val bottom = points.maxOf { it.y }
        val contentWidth = max(1f, right - left)
        val contentHeight = max(1f, bottom - top)
        val padding = max(14f, max(contentWidth, contentHeight) * 0.12f)
        val sourceWidth = contentWidth + padding * 2f
        val sourceHeight = contentHeight + padding * 2f
        val sx = IMAGE_SIZE / sourceWidth
        val sy = IMAGE_SIZE / sourceHeight

        return Bitmap.createBitmap(IMAGE_SIZE, IMAGE_SIZE, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            strokes.forEach { stroke ->
                if (stroke.points.size < 2) return@forEach
                paint.strokeWidth = max(2f, stroke.width * (sx + sy) / 2f)
                val path = Path()
                stroke.points.forEachIndexed { index, point ->
                    val x = (point.x - left + padding) * sx
                    val y = (point.y - top + padding) * sy
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                canvas.drawPath(path, paint)
            }
        }
    }

    private class Engine(modelDirectory: File) : AutoCloseable {
        private val environment = OrtEnvironment.getEnvironment()
        private val options = OrtSession.SessionOptions()
        private val encoder = environment.createSession(File(modelDirectory, "encoder.onnx").absolutePath, options)
        private val decoder = environment.createSession(File(modelDirectory, "decoder-int8.onnx").absolutePath, options)
        private val tokenizer = Tokenizer(File(modelDirectory, "tokenizer.json"))

        @Synchronized
        fun recognize(bitmap: Bitmap): String {
            val pixels = FloatArray(3 * IMAGE_SIZE * IMAGE_SIZE)
            val argb = IntArray(IMAGE_SIZE * IMAGE_SIZE)
            bitmap.getPixels(argb, 0, IMAGE_SIZE, 0, 0, IMAGE_SIZE, IMAGE_SIZE)
            val plane = IMAGE_SIZE * IMAGE_SIZE
            argb.forEachIndexed { index, pixel ->
                pixels[index] = Color.red(pixel) / 127.5f - 1f
                pixels[plane + index] = Color.green(pixel) / 127.5f - 1f
                pixels[plane * 2 + index] = Color.blue(pixel) / 127.5f - 1f
            }

            OnnxTensor.createTensor(
                environment,
                FloatBuffer.wrap(pixels),
                longArrayOf(1, 3, IMAGE_SIZE.toLong(), IMAGE_SIZE.toLong())
            ).use { pixelTensor ->
                encoder.run(mapOf("pixel_values" to pixelTensor)).use { encoderResult ->
                    val hidden = encoderResult[0] as OnnxTensor
                    val ids = mutableListOf(DECODER_START_TOKEN)
                    val generated = mutableListOf<Int>()
                    repeat(MAX_TOKENS) {
                        OnnxTensor.createTensor(
                            environment,
                            LongBuffer.wrap(ids.map(Int::toLong).toLongArray()),
                            longArrayOf(1, ids.size.toLong())
                        ).use { idTensor ->
                            decoder.run(
                                mapOf("input_ids" to idTensor, "encoder_hidden_states" to hidden)
                            ).use { output ->
                                val logits = output[0] as OnnxTensor
                                val dimensions = logits.info.shape
                                val vocabulary = dimensions.last().toInt()
                                val sequence = dimensions[dimensions.lastIndex - 1].toInt()
                                val values = logits.floatBuffer
                                val offset = (sequence - 1) * vocabulary
                                var bestId = 0
                                var best = Float.NEGATIVE_INFINITY
                                for (token in 0 until vocabulary) {
                                    val value = values.get(offset + token)
                                    if (value > best) {
                                        best = value
                                        bestId = token
                                    }
                                }
                                if (bestId == EOS_TOKEN) return tokenizer.decode(generated)
                                ids += bestId
                                generated += bestId
                                if (generated.size >= 24 && hasRepeatingTail(generated)) {
                                    error("Модель зациклилась: попробуйте выделить формулу плотнее")
                                }
                            }
                        }
                    }
                    error("Формула слишком длинная или не распознана")
                }
            }
        }

        override fun close() {
            encoder.close()
            decoder.close()
            options.close()
        }
    }

    private class Tokenizer(file: File) {
        private val tokens: Array<String?>
        private val byteDecoder: Map<Char, Int> = buildByteDecoder()

        init {
            val vocabulary = JSONObject(file.readText()).getJSONObject("model").getJSONObject("vocab")
            val maxId = vocabulary.keys().asSequence().maxOf { vocabulary.getInt(it) }
            tokens = arrayOfNulls(maxId + 1)
            vocabulary.keys().forEach { token -> tokens[vocabulary.getInt(token)] = token }
        }

        fun decode(ids: List<Int>): String {
            val joined = buildString {
                ids.filter { it >= SPECIAL_TOKEN_COUNT }.forEach { id -> tokens.getOrNull(id)?.let(::append) }
            }
            val bytes = ByteArrayOutputStream(joined.length)
            joined.forEach { character -> byteDecoder[character]?.let(bytes::write) }
            return postProcess(bytes.toByteArray().toString(Charsets.UTF_8))
        }

        private fun buildByteDecoder(): Map<Char, Int> {
            val bytes = mutableListOf<Int>().apply {
                addAll(0x21..0x7e)
                addAll(0xa1..0xac)
                addAll(0xae..0xff)
            }
            val characters = bytes.toMutableList()
            var extra = 0
            for (byte in 0..255) {
                if (byte !in bytes) {
                    bytes += byte
                    characters += 256 + extra++
                }
            }
            return characters.indices.associate { characters[it].toChar() to bytes[it] }
        }

        private fun postProcess(value: String): String = value
            .replace(Regex("\\\\[.=-]")) { "\\\\ ${it.value.last()}" }
            .replace(Regex("\\\\l(?![A-Za-z])"), "l")
            .replace(Regex("\\s+([}])"), "$1")
            .replace(Regex("([{])\\s+"), "$1")
            .replace(Regex("\\s*([+\\-=^_])\\s*"), "$1")
            .trim()
    }

    companion object {
        private const val IMAGE_SIZE = 384
        private const val DECODER_START_TOKEN = 2
        private const val EOS_TOKEN = 2
        private const val SPECIAL_TOKEN_COUNT = 5
        private const val MAX_TOKENS = 256

        private fun hasRepeatingTail(ids: List<Int>, maxCycle: Int = 12, minRepeats: Int = 6): Boolean {
            for (period in 1..maxCycle) {
                val required = period * minRepeats
                if (ids.size < required) break
                val tail = ids.takeLast(required)
                if ((period until required).all { tail[it] == tail[it % period] }) return true
            }
            return false
        }
    }
}
