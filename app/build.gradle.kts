import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Release signing is read from keystore.properties (not committed). Without it, release builds
// are produced unsigned rather than silently signed with the debug key.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// iris.prebuiltNative=<dir>: skip CMake, package libiris_*.so from <dir>/arm64-v8a (no NDK needed)
val prebuiltNative = (project.findProperty("iris.prebuiltNative") as String?)?.let { rootProject.file(it) }

android {
    namespace = "com.iris.localai"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    // Optional: point at a specific NDK (e.g. an arm64-host NDK) via local.properties `ndk.path`.
    val customNdk = localProps.getProperty("ndk.path")
    if (customNdk != null) {
        ndkPath = customNdk
        ndkVersion = Properties().apply { file("$customNdk/source.properties").inputStream().use { load(it) } }
            .getProperty("Pkg.Revision")
    } else {
        ndkVersion = "27.2.12479018" // r27c, LTS
    }

    defaultConfig {
        applicationId = "com.iridis"
        minSdk = 31
        targetSdk = 35
        versionCode = 238
        versionName = "2.4.6"

        ndk { abiFilters += "arm64-v8a" }
        if (prebuiltNative == null) externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_static",
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DIRIS_KLEIDIAI=${(project.findProperty("iris.kleidiai") ?: "ON")}",
                    "-DIRIS_LTO=${(project.findProperty("iris.lto") ?: "ON")}",
                )
            }
        }
    }

    if (prebuiltNative == null) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
            }
        }
    } else {
        sourceSets["main"].jniLibs.srcDir(prebuiltNative)
    }

    // lite (default): GGUF only — the small build.  full: adds Google LiteRT-LM 0.18 (+22 MB native).
    flavorDimensions += "engines"
    productFlavors {
        create("lite") {
            dimension = "engines"
            isDefault = true
            buildConfigField("boolean", "HAS_LITERT", "false")
        }
        create("full") {
            dimension = "engines"
            versionNameSuffix = "-full"
            buildConfigField("boolean", "HAS_LITERT", "true")
            // Third engine: MNN-LLM (taobao-mnn model folders) in libiris_mnn.so.
            if (prebuiltNative == null) externalNativeBuild { cmake { arguments += "-DIRIS_MNN=ON" } }
        }
    }

    // 2.2.15: built-in llama.cpp Hexagon (NPU) runtime, full flavour only, as assets (scripts/build-llama-htp.sh).
    rootProject.file("prebuilt-assets").takeIf { it.isDirectory }?.let { sourceSets["full"].assets.srcDir(it) }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
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

    packaging {
        jniLibs {
            // Compressed in the APK (≈55% smaller download); extracted once at install time.
            useLegacyPackaging = true
            // 2.2.0: GenieX (geniex-android 0.10.0 since 2.4.1, full flavour) ships 52 native libraries (≈ 89 MB AAR).
            // None go into the APK: the ones this phone needs are downloaded on demand from Maven
            // Central into app storage (GenieXRuntime, 设置 › 模型与后端), like the LiteRT NPU runtime.
            // No name overlaps ours (libiris_llama / libiris_mnn / liblitertlm_jni / sherpa).
            excludes += setOf(
                "**/libnpu_jni.so", "**/libgeniex*.so", "**/libllama.so", "**/libllama-common.so", "**/libmtmd.so",
                "**/libggml*.so", "**/libomp.so", "**/libQnn*.so", "**/libhta_hexagon_runtime_*.so",
                "**/libPlatformValidatorShared.so", "**/libcalculator.so", "**/libCalculator_skel.so",
                // 2.4.0: stable-diffusion.cpp (≈64 MB) is downloaded with the FLUX.2 / Z-Image models (ImageCatalog.SD_LIB).
                "**/libiris_sd.so",
            )
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    androidResources {
        // Model/voice files are never compressed in assets.
        noCompress += listOf("onnx", "bin")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        disable += listOf("MissingTranslation")
    }
}

dependencies {
    // 2.2.14 无障碍保活: one-tap WRITE_SECURE_SETTINGS grant through Shizuku (Apache-2.0).
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.process)

    implementation(platform(libs.compose.bom))
    implementation(libs.haze)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // Google LiteRT-LM runtime (.litertlm models: Gemma 4, Qwen3.5, …) — full flavour only
    "fullImplementation"(libs.litertlm.android)
    // Qualcomm GenieX 0.8.0 (GGUF on Hexagon / AI Hub QAIRT bundles) — full flavour only; classes only (see packaging).
    "fullImplementation"(libs.geniex.android)
    // sherpa-onnx: on-device ASR (SenseVoice), VAD (Silero) and TTS (Kokoro). Fetched by
    // scripts/fetch-deps.sh with a pinned SHA-256. The static-link build folds onnxruntime
    // into one library (≈3 MB smaller than the shared build, one less .so to load).
    implementation(files("libs/sherpa-onnx-static-link-onnxruntime-1.13.8.aar"))

    // .tar.bz2 unpacking for voice packs published only as sherpa-onnx GitHub release archives (ZipVoice).
    implementation(libs.commons.compress)
    // 2.2.3: GUI agent OCR fallback — ML Kit text recognition, Chinese, bundled model (full only).
    "fullImplementation"("com.google.mlkit:text-recognition-chinese:16.0.1")

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.sqlite.jdbc)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
