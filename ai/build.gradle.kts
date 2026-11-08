import java.security.MessageDigest
// On-device AI: model manager (download/verify/delete), speech recognition, VAD and
// diarization (sherpa-onnx), video matting (ONNX Runtime), source separation, command
// planning (MediaPipe LLM inference) and classical video analysis.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "app.bekocat.ai"
    compileSdk = 35
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

/**
 * sherpa-onnx is distributed as an AAR on Hugging Face (not on Maven). It is downloaded once
 * into ai/libs and verified by SHA-256 before use, so builds are reproducible.
 */
val sherpaVersion = "1.12.21"
val sherpaAar = file("libs/sherpa-onnx-static-link-onnxruntime-$sherpaVersion.aar")
val fetchSherpa by tasks.registering {
    outputs.file(sherpaAar)
    doLast {
        if (!sherpaAar.exists()) {
            sherpaAar.parentFile.mkdirs()
            val url = "https://huggingface.co/csukuangfj/sherpa-onnx-libs/resolve/main/android/aar/$sherpaVersion/sherpa-onnx-static-link-onnxruntime-$sherpaVersion.aar"
            logger.lifecycle("Downloading $url")
            ant.invokeMethod("get", mapOf("src" to url, "dest" to sherpaAar))
        }
        val expected = "6d1bf9a2ede218d9f33115f494058b83ae5c2af8a8275e64353c3de8c83a2603"
        val actual = MessageDigest.getInstance("SHA-256").digest(sherpaAar.readBytes()).joinToString("") { "%02x".format(it) }
        if (actual != expected) { sherpaAar.delete(); throw GradleException("sherpa-onnx AAR checksum mismatch: $actual") }
    }
}
tasks.named("preBuild") { dependsOn(fetchSherpa) }

dependencies {
    api(project(":core:timeline"))
    api(project(":engine"))
    // compileOnly here; the app module packages the AAR (AGP forbids local AARs inside library AARs).
    compileOnly(files(sherpaAar))
    implementation(libs.onnxruntime.android)
    implementation(libs.mediapipe.genai)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
}
