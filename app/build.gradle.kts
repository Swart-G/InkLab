import java.net.URI
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val ciVersionCode = 201_000 + (System.getenv("GITHUB_RUN_NUMBER")
    ?.toIntOrNull()
    ?.coerceAtLeast(2)
    ?: 0)
val releaseKeyPassword = "InkLabPreviewRelease2026"

android {
    namespace = "dev.swart.inklab"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.swart.inklab"
        minSdk = 24
        targetSdk = 36
        versionCode = ciVersionCode
        versionName = "2.0.1"
        vectorDrawables.useSupportLibrary = true
        ndk.abiFilters += providers.gradleProperty("testAbi").getOrElse("arm64-v8a")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            storeFile = file("inklab-release.jks")
            storePassword = releaseKeyPassword
            keyAlias = "inklab"
            keyPassword = releaseKeyPassword
        }
    }

    buildTypes {
        getByName("release") {
            isDebuggable = false
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

val prepareBundledModels by tasks.registering {
    val expected = mapOf(
        "encoder.onnx" to Triple(
            "https://raw.githubusercontent.com/samirahmed007/mathorium/f2411279317da9cf3a12ceb453c9a96aca5b4743/public/models/pix2text-mfr-quantized/onnx/encoder_model.onnx",
            23_083_189L,
            "5e5141ed5f6e05851b1a38b6df85fe17c1dcb779358729fa707f9ee36b7b9dd9"
        ),
        "decoder-int8.onnx" to Triple(
            "https://raw.githubusercontent.com/samirahmed007/mathorium/f2411279317da9cf3a12ceb453c9a96aca5b4743/public/models/pix2text-mfr-quantized/onnx/decoder_model_int8.onnx",
            7_989_844L,
            "1b81c99876de606631449eacf1af32ce372f5641ebed3216e0208bde2bdbeaa6"
        ),
        "tokenizer.json" to Triple(
            "https://raw.githubusercontent.com/samirahmed007/mathorium/f2411279317da9cf3a12ceb453c9a96aca5b4743/public/models/pix2text-mfr-quantized/tokenizer.json",
            39_161L,
            "3e2ab757277d22639bec28c9d7972e352d3d1dba223051fa674002dc5ab64df3"
        )
    )
    val modelDir = layout.projectDirectory.dir("src/main/assets/ocr/pix2text-mfr")
    outputs.files(expected.keys.map { modelDir.file(it) })
    outputs.upToDateWhen { false }

    doLast {
        expected.forEach { (name, metadata) ->
            val file = modelDir.file(name).asFile
            file.parentFile.mkdirs()
            if (!file.isFile || file.length() != metadata.second) {
                val partial = file.resolveSibling("${file.name}.part")
                partial.delete()
                URI(metadata.first).toURL().openStream().buffered().use { input ->
                    partial.outputStream().buffered().use { output -> input.copyTo(output) }
                }
                file.delete()
                check(partial.renameTo(file)) { "Could not install bundled model: $name" }
            }
            check(file.length() == metadata.second) { "Unexpected size for bundled model: $name" }
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
            check(actualHash == metadata.third) { "SHA-256 mismatch for bundled model: $name" }
        }
    }
}

tasks.named("preBuild").configure { dependsOn(prepareBundledModels) }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.29.0")
    implementation("com.google.mlkit:digital-ink-recognition:19.0.0")
    implementation("ru.noties:jlatexmath-android:0.2.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.06.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
