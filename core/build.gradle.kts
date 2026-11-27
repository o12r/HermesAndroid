plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.oscar.hermes"
    compileSdk { version = release(37) { minorApiLevel = 2 } }
    defaultConfig {
        minSdk = 29
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2026.11.01")
    api(bom)
    api("androidx.compose.material3:material3")
    api("androidx.compose.ui:ui")
    api("androidx.compose.material:material-icons-extended:1.7.8")
    api("androidx.activity:activity-compose:1.13.0")
    api("androidx.core:core-ktx:1.19.1")
    api("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.1")
    api("androidx.lifecycle:lifecycle-runtime-compose:2.11.1")
    api("com.squareup.okhttp3:okhttp:4.12.0")
    api(project(":shared"))            // protocol layer (ApiClient, GatewayWs)
    api("com.google.zxing:core:3.5.3")            // QR decoding (open source)
    api("androidx.camera:camera-camera2:1.4.2")
    api("androidx.camera:camera-lifecycle:1.4.2")
    api("androidx.camera:camera-view:1.4.2")
    api("io.noties.markwon:core:4.6.2")
    api("io.noties.markwon:ext-tables:4.6.2")
    api("io.noties.markwon:ext-strikethrough:4.6.2")
}
