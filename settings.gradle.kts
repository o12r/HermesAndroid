pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "HermesAndroid"
include(":app")
include(":core")   // Android library: all app code + resources
include(":shared")   // pure Kotlin/JVM protocol layer (no AGP)

// Optional features (compile-time): see app/build.gradle.kts for the -Pfeatures switch.
listOf("vdisplay", "pcfiles", "pcstats").forEach { include(":feature-$it") }
