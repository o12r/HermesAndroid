// :shared — pure Kotlin/JVM protocol layer (ApiClient, GatewayWs).
// Consumed by :app (D8 handles a plain jar) and, later, by :desktop.
// No Android tooling here on purpose; repos stay in settings.gradle.kts (FAIL_ON_PROJECT_REPOS).

plugins {
    kotlin("jvm") version "2.4.21"
}

kotlin {
    jvmToolchain(21)   // reuses the daemon JDK (gradle.properties: org.gradle.java.home=C:/Java/jdk21) — no provisioning
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    // Provided by android.jar at runtime on device; :desktop will add this as implementation().
    compileOnly("org.json:json:20240303")
}
