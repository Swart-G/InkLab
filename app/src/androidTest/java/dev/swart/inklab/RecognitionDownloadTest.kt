package dev.swart.inklab

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.swart.inklab.core.model.*
import dev.swart.inklab.core.recognition.*
import dev.swart.inklab.recognition.mlkit.MlKitDigitalInkProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecognitionDownloadTest {
    @Test fun russianAndEnglishModelsDownloadAndRecognize() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        withTimeout(120000) {
            for(tag in listOf("ru-RU","en-US")) {
                val provider=MlKitDigitalInkProvider(tag).apply {wifiOnly=false}
                provider.prepare(context).getOrThrow();provider.refresh();assertEquals(ProviderState.READY,provider.state(context))
                val stroke=InkStroke(points=listOf(InkPoint(10f,10f,1),InkPoint(10f,90f,2),InkPoint(45f,90f,3)))
                val result=provider.recognize(context,listOf(stroke),RecognitionMode.TEXT)
                assertTrue(result.candidates.isNotEmpty())
            }
        }
    }
}
