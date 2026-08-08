import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.jarvis.assistant"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jarvis.assistant"
        // On-device LLM inference needs Android 8+ (SpeechRecognizer on-device
        // recognition and CameraManager torch APIs used by the tool set need 26+).
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // -- Core / Compose UI --
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.1")

    // -- On-device LLM inference (Google AI Edge) --
    // https://github.com/google-ai-edge/LiteRT-LM
    implementation("com.google.ai.edge.litertlm:litertlm-android:latest.release")

    // -- Ollama + Cloud API backends and WebTools (HTTP/SSE streaming) --
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // -- Encrypted storage for cloud API keys (see BackendSettings.kt) --
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // -- Coroutines --
    // Pinned high (not just left to transitive resolution): litertlm-android's
    // compiled bytecode calls kotlinx.coroutines APIs from a newer coroutines
    // release than 1.8.1. With 1.8.1 declared here, Gradle resolved the whole
    // graph down to it, and litertlm crashed at runtime with
    // NoSuchMethodError: SendChannel.close$default(...) the moment a
    // Conversation's response stream finished (see Conversation.kt:452,
    // ~2s after Jarvis's reply completes). Forcing the newest stable
    // coroutines here makes the resolved version the highest in the graph
    // again, matching what litertlm-android actually needs.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.09.03"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
