plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.screencaptureapp"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.screencaptureapp"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        ndk {          // only 64‑bit ABI (Pixel 9 Pro)
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
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
    kotlinOptions {
        jvmTarget = "17"
    }

    // Enable Compose
    buildFeatures {
        compose = true
    }

    /** -------- Modern packaging / resource config ---------- **/
    // Exclude duplicate licences or other META‑INF noise
    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
        }
        // keep legacy .so layout if you really need it
        jniLibs {
            useLegacyPackaging = true
        }
    }

    // Prevent .task (and .tflite / .lite) from being gzipped in the APK
    androidResources {
        noCompress += listOf("task", "tflite", "lite")
    }

    /** -------- Disable compressDebugAssets (optional) -------- **/
    // Kotlin DSL way – configure AFTER evaluation
    tasks.matching { it.name == "compressDebugAssets" }
        .configureEach { enabled = false }
}

dependencies {
    // Core Android dependencies
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)

    // Compose BOM - manages all Compose library versions
    implementation(platform(libs.androidx.compose.bom))

    // Compose dependencies
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    // Lifecycle
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // ADDED: LocalBroadcastManager for service communication
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")

    // Coroutines for async operations
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")

    // For OCR - Google ML Kit Text Recognition (Keep this - it's stable)
    implementation("com.google.mlkit:text-recognition:16.0.0")

    // REMOVED: ML Kit Language Identification - let Gemma handle language detection
    // implementation("com.google.mlkit:language-id:17.0.4")

    // MediaPipe for LLM inference
    implementation(libs.mediapipe.tasks.genai)

    // GPU Dependencies for MediaPipe (CRITICAL for GPU acceleration):
    implementation(libs.tflite)
    implementation(libs.tflite.gpu)
    implementation(libs.tflite.support)

    // For API calls
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.11.0")

    // CRITICAL: Add GPU Dependencies for TensorFlow Lite GPU acceleration
    implementation("org.tensorflow:tensorflow-lite:2.14.0")
    implementation("org.tensorflow:tensorflow-lite-gpu:2.14.0")
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")

    // Multi-language text recognition for better OCR (Keep these - they're for OCR, not language detection)
    implementation("com.google.mlkit:text-recognition-japanese:16.0.0")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.0")
    implementation("com.google.mlkit:text-recognition-korean:16.0.0")

    // MediaPipe for potential future use (like your original app)
    implementation("com.google.mediapipe:tasks-genai:0.10.8")

    // For image processing
    implementation("androidx.exifinterface:exifinterface:1.3.6")

    // Testing dependencies
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    // Compose testing
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}