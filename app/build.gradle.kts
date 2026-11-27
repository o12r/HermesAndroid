import java.util.Properties

plugins {
    id("com.android.application")
}

// :app is only the packaging module: applicationId/version + which optional feature modules go into the APK.
// All code and resources live in :core; each optional feature is its own library (:feature-*).
// Choose features at build time: ./gradlew assembleDebug -Pfeatures=vdisplay,pcfiles   (default: all; -Pfeatures=none for core only)
val versionProps = Properties().apply { rootProject.file("version.properties").inputStream().use { this.load(it) } }

android {
    namespace = "dev.oscar.hermes.app"
    compileSdk { version = release(37) { minorApiLevel = 2 } }
    defaultConfig {
        applicationId = "dev.oscar.hermes"
        minSdk = 29
        targetSdk = 36
        versionCode = versionProps.getProperty("VERSION_CODE").toInt()
        versionName = versionProps.getProperty("VERSION_NAME")
    }
    buildTypes { debug { isMinifyEnabled = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core"))
    val all = listOf("vdisplay", "pcfiles", "pcstats")
    val want = (providers.gradleProperty("features").orNull ?: "all").let { p ->
        if (p == "all") all else if (p == "none") emptyList() else p.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }
    want.forEach { f -> require(f in all) { "unknown feature '$f' (known: $all)" }; implementation(project(":feature-$f")) }
}
