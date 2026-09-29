import java.net.URI
import java.security.MessageDigest
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.aura.companion"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aura.companion"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // LiteRT-LM only ships native libraries for these ABIs
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        // Load secrets securely from local.properties (which is git-ignored)
        val localProperties = Properties().apply {
            val localPropertiesFile = rootProject.file("local.properties")
            if (localPropertiesFile.exists()) {
                localPropertiesFile.inputStream().use { load(it) }
            }
        }
        val weatherApiKey = localProperties.getProperty("WEATHER_API_KEY") ?: "8607b8473f6168d5cc58040b2579bd38"

        buildConfigField("String", "WEATHER_API_KEY", "\"$weatherApiKey\"")
        buildConfigField("String", "DEFAULT_CITY", "\"Mumbai\"")
    }


    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Allow large model files; sherpa-onnx reads the ASR models straight out of the APK
    androidResources {
        noCompress += listOf("litertlm", "onnx")
    }
}

// ---- Offline speech models (sherpa-onnx) ----
// Neither the sherpa-onnx AAR nor its models are on Maven or small enough to commit, so they are
// downloaded once from the project's GitHub releases, verified by SHA-256, and git-ignored.
val sherpaOnnxVersion = "1.13.8"
val sherpaOnnxAar = file("libs/sherpa-onnx-$sherpaOnnxVersion.aar")
val asrAssetsDir = file("src/main/assets/asr")

fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").run {
    file.inputStream().use { input ->
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            update(buffer, 0, read)
        }
    }
    digest().joinToString("") { "%02x".format(it) }
}

fun download(url: String, dest: File, expectedSha256: String) {
    dest.parentFile.mkdirs()
    logger.lifecycle("Downloading $url")
    val partial = File(dest.path + ".part")
    URI(url).toURL().openStream().use { input -> partial.outputStream().use { input.copyTo(it) } }
    val actual = sha256(partial)
    if (actual != expectedSha256) {
        partial.delete()
        throw GradleException("Checksum mismatch for $url: expected $expectedSha256, got $actual")
    }
    partial.renameTo(dest)
}

// Runs at configuration time because the AAR must exist before dependencies resolve
fun fetchSpeechModels() {
    val releases = "https://github.com/k2-fsa/sherpa-onnx/releases/download"
    if (!sherpaOnnxAar.exists()) {
        download(
            "$releases/v$sherpaOnnxVersion/sherpa-onnx-$sherpaOnnxVersion.aar", sherpaOnnxAar,
            "633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96"
        )
    }
    val vad = File(asrAssetsDir, "silero_vad.onnx")
    if (!vad.exists()) {
        download(
            "$releases/asr-models/silero_vad.onnx", vad,
            "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6"
        )
    }
    // NVIDIA Parakeet TDT 110M (English, int8): punctuated, cased output at ~135 MB
    val asrFiles = listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt")
    if (asrFiles.any { !File(asrAssetsDir, it).exists() }) {
        val modelName = "sherpa-onnx-nemo-parakeet_tdt_transducer_110m-en-36000-int8"
        val archive = layout.buildDirectory.file("tmp/$modelName.tar.bz2").get().asFile
        download(
            "$releases/asr-models/$modelName.tar.bz2", archive,
            "f628312e9fdf8686374cb01a69425c41732529d540860311f16f37cbc32cfe9b"
        )
        val extractDir = layout.buildDirectory.dir("tmp/asr-extract").get().asFile
        ant.withGroovyBuilder {
            "untar"("src" to archive, "dest" to extractDir, "compression" to "bzip2")
        }
        asrFiles.forEach { name ->
            File(extractDir, "$modelName/$name").copyTo(File(asrAssetsDir, name), overwrite = true)
        }
        archive.delete()
        extractDir.deleteRecursively()
    }
}
fetchSpeechModels()

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore)

    // Room DB
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Networking
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.gson)

    // Coroutines
    implementation(libs.coroutines.android)

    // LiteRT-LM (on-device Gemma 4, GPU via OpenCL)
    implementation(libs.litertlm.android)

    // sherpa-onnx (on-device voice activity detection + speech-to-text), fetched by fetchSpeechModels()
    implementation(files(sherpaOnnxAar))

    debugImplementation(libs.androidx.ui.tooling)
}
