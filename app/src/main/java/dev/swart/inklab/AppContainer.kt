package dev.swart.inklab

import dev.swart.inklab.core.recognition.RecognitionRegistry
import dev.swart.inklab.recognition.mlkit.MlKitDigitalInkProvider
import dev.swart.inklab.recognition.onnx.Pix2TextMathProvider

object AppContainer {
    val recognitionRegistry by lazy {
        RecognitionRegistry(listOf(MlKitDigitalInkProvider(), Pix2TextMathProvider()))
    }
}
