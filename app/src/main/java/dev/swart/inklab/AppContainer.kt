package dev.swart.inklab

import dev.swart.inklab.core.recognition.RecognitionRegistry
import dev.swart.inklab.recognition.mlkit.MlKitDigitalInkProvider
import dev.swart.inklab.recognition.stub.externalProviders

object AppContainer {
    val recognitionRegistry by lazy {
        RecognitionRegistry(listOf(MlKitDigitalInkProvider()) + externalProviders())
    }
}
