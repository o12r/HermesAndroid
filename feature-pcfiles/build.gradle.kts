// Optional feature module (:feature-pcfiles): controller + Compose UI. Depends only on :core.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.oscar.hermes.pcfiles"
    compileSdk { version = release(37) { minorApiLevel = 2 } }
    defaultConfig { minSdk = 29 }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":core"))
}
