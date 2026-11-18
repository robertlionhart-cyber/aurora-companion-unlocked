import java.util.Properties

val auroraLocal = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
// Voice assets (Robert Piper voice + espeak-ng-data + Moonshine ASR + Silero VAD) and the sherpa-onnx AAR.
// Default: third_party/ in the repo (filled by scripts/fetch_deps); override in local.properties.
val voiceAssetsDir: String = auroraLocal.getProperty("aurora.voiceAssets")
    ?: rootProject.file("third_party/voice-assets").path
val sherpaAar: String = auroraLocal.getProperty("aurora.sherpaAar")
    ?: rootProject.file("third_party/sherpa-onnx-1.13.8.aar").path

// Release signing: keystore.properties (storeFile, storePassword, keyAlias, keyPassword) kept OUTSIDE the repo.
// Looked up via local.properties "aurora.keystoreProps", env AURORA_KEYSTORE_PROPS, or ./keystore.properties.
// Without it, release builds fall back to the debug key (fine for your own builds, not for publishing).
val keystoreProps = Properties().apply {
    val path = auroraLocal.getProperty("aurora.keystoreProps") ?: System.getenv("AURORA_KEYSTORE_PROPS")
    val f = if (path != null) file(path) else rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.aurora.companion.android"
    compileSdk = 36
    ndkVersion = "29.0.13113456"  // same NDK as :lib (also used to strip native libs)

    defaultConfig {
        applicationId = "com.aurora.companion.android"
        minSdk = 26          // Android 8.0+ : covers Xiaomi Pad 5 and Galaxy A54 easily
        targetSdk = 36
        versionCode = 29
        versionName = "0.9.9-beta"

        ndk {
            // Pad 5 (Snapdragon 860) and Galaxy A54 (Exynos 1380) are both 64-bit ARM.
            abiFilters += listOf("arm64-v8a")
        }
    }

    // 0.8.6: two editions from one code base, both signed with the same release key.
    //  family   - no 18+ mode at all; keeps the ORIGINAL applicationId, so 0.8.2-0.8.5 installs update into it in place.
    //  unlocked - adds the optional 18+ mode (off by default); its own applicationId (.unlocked) so both can be installed.
    // Edition-specific code lives in src/family and src/unlocked (Edition.kt).
    flavorDimensions += "edition"
    productFlavors {
        create("family") {
            dimension = "edition"
        }
        // Only when its source set is present (the public repo may ship the Family edition alone).
        if (file("src/unlocked").exists()) create("unlocked") {
            dimension = "edition"
            applicationIdSuffix = ".unlocked"
        }
    }

    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // R8 stays off: llama.cpp (JNI) and sherpa-onnx call into Kotlin/Java by name.
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets["main"].assets.srcDirs(voiceAssetsDir)
    androidResources {
        // Models are read straight from the APK; storing them uncompressed makes loading faster.
        noCompress += listOf("onnx", "ort", "mp4")
    }
    buildFeatures {
        compose = true
    }
    packaging {
        // llama.cpp loads its CPU backend variants from nativeLibraryDir at runtime,
        // so native libs must be extracted on install.
        jniLibs { useLegacyPackaging = true }
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":lib"))
    implementation(files(sherpaAar))   // sherpa-onnx 1.13.8 (offline ASR + Piper TTS)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
}
