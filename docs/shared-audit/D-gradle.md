# D — Gradle feasibility audit: adding `:shared` (Kotlin/JVM) and later `:desktop` (Compose Multiplatform) to `HermesAndroid` without breaking the offline build

Read-only study for `docs/windows-client-plan.md` §3/§4.1/§8/§9.4. No Gradle invoked, no build file edited.
Everything below was read from the working tree plus `~/.gradle/caches/modules-2/files-2.1` (GRADLE_USER_HOME is **not** overridden → `C:\Users\oscar\.gradle`, 2.8 GB of caches, `modules-2` + `jars-9` present).

## 0. Headline

| Question | Answer |
|---|---|
| Can `:shared` be added **and still build offline today**? | **Yes, but not via a plain `plugins { kotlin("jvm") }`** — the plugin *marker* `org.jetbrains.kotlin.jvm.gradle.plugin` is not in the cache. Two working options in §3.3 (one is a 5-line `pluginManagement` shim, the other is one online sync). |
| Are the libraries a JVM module needs already cached? | OkHttp 4.12.0 ✅, okio 3.6.0 ✅, coroutines-core-jvm 1.9.0/1.10.2 ✅, kotlin-stdlib 2.4.21 ✅, KGP 2.4.21 jar ✅. **`org.json:json` ✗** (single 70 KB jar to fetch, or drop it — see §3.5). |
| Can `:desktop` build offline today? | **No.** Zero desktop artifacts in cache: no `org.jetbrains.compose` plugin group at all, no `*-desktop` jars beyond three `androidx` desktop jars, **no skiko**. Full download list in §4. |
| Does the machine have internet for Gradle? | **Yes.** `repo1.maven.org` → 200, `plugins.gradle.org/m2` → 200, `dl.google.com/dl/android/maven2` → 200 (verified with the exact artifacts this plan needs). So "one online build, then offline forever" is viable. |
| jpackage | **Already present**: `C:\Java\jdk21\bin\jpackage.exe` (Temurin 21.0.13). No artifact download. WiX **absent** → `app-image` works, `.msi`/`.exe` installers do not (§5). |

## 1. Current build baseline

| Item | Value | Source |
|---|---|---|
| Gradle | **9.9.1** (`gradle-9.9.1-bin.zip`, dist already unpacked in `~/.gradle/wrapper/dists/gradle-9.9.1-bin`; a 9.4.1 dist is also present) | `gradle/wrapper/gradle-wrapper.properties` |
| AGP | **9.5.1** — declared `apply false` in root only | `build.gradle.kts`, cache `com.android.tools.build/gradle/9.5.1` |
| Kotlin | **2.4.21** — only via `org.jetbrains.kotlin.plugin.compose` (also `apply false` in root) | `build.gradle.kts`, `kotlin-stdlib/2.4.21` |
| `kotlin-android` plugin | **not applied anywhere** — AGP 9 has built-in Kotlin support; `app/build.gradle.kts` uses only `com.android.application` + compose plugin | `app/build.gradle.kts` |
| Compose (app) | BOM `androidx.compose:compose-bom:2026.11.01`, material3, activity-compose 1.13.0, lifecycle 2.11.1 | `app/build.gradle.kts` |
| SDK levels | compileSdk 37.2, minSdk 29, targetSdk 36 | `app/build.gradle.kts` |
| Java | `compileOptions` **17**; daemon pinned via `org.gradle.java.home=C:/Java/jdk21` → toolchain 21 resolves to the daemon JVM, **no toolchain download needed** | `gradle.properties` |
| Repos | `pluginManagement` = google + mavenCentral + gradlePluginPortal; `dependencyResolutionManagement` = google + mavenCentral, **`FAIL_ON_PROJECT_REPOS`** | `settings.gradle.kts` |
| Version catalog | **none** — there is no `gradle/libs.versions.toml`; versions are hard-coded strings | `gradle/` contains only `wrapper/` |

Consequences for new modules: **do not** declare `repositories {}` inside a module (hard failure under `FAIL_ON_PROJECT_REPOS`), and prefer explicit coordinates over a version catalog (creating one is not needed, and its `libs.versions.toml` support is cache-independent anyway).

## 2. Offline cache inventory (what a JVM `:shared` needs)

`~/.gradle/caches/modules-2/files-2.1`:

| Coordinate | Cached versions | Verdict |
|---|---|---|
| `org.jetbrains.kotlin:kotlin-gradle-plugin` | 2.2.10, **2.4.21** (with `-gradle813` and `-gradle96` variants) | ✅ jar present, Gradle-9 compatible |
| `org.jetbrains.kotlin:kotlin-gradle-plugin-api` | 2.2.10, **2.4.21** | ✅ |
| `org.jetbrains.kotlin:kotlin-stdlib` | 2.2.10, 2.3.0, **2.4.21** | ✅ |
| `org.jetbrains.kotlin:kotlin-compiler-embeddable`, `-build-tools-impl`, `-daemon-embeddable`, `-script-runtime`, `-reflect` | 2.4.21 present | ✅ (compilation works offline once the plugin resolves) |
| **`org.jetbrains.kotlin.jvm:…gradle.plugin` (marker)** | — | ❌ **missing** → §3.3 |
| `org.jetbrains.kotlin.android:…gradle.plugin` (marker) | present | ✅ (irrelevant: app doesn't use it) |
| `org.jetbrains.kotlin.plugin.compose:…gradle.plugin` (marker) | 2.4.21 | ✅ |
| `com.android.application:…gradle.plugin` (marker) | present | ✅ |
| **`com.android.library:…gradle.plugin` (marker)** | — | ❌ missing (matters only if you pick the `aar` route, §3.2) |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core` / `-core-jvm` | 1.9.0, **1.10.2** (+ Android coords) | ✅ |
| `com.squareup.okhttp3:okhttp` | 4.12.0 (matches app), 5.3.2 | ✅ incl. `okhttp-coroutines` |
| `com.squareup.okio:okio` (`okio-jvm`) | 3.6.0, 3.16.0/3.16.4/3.18.1 | ✅ |
| `org.jetbrains.kotlin:kotlin-stdlib-jdk8` | 1.8.21 (okhttp 4.12 POM dep) | ✅ |
| **`org.json:json`** | — | ❌ **missing** (see §3.5) |
| `org.jetbrains.kotlinx:kotlinx-serialization-json` (+ `-jvm`) | 1.9.0 | ✅ |
| `org.jetbrains.kotlin.plugin.serialization` (marker) | — | ❌ missing (avoid the compiler plugin; `JsonObject`/`Json.parseToJsonElement` need no plugin) |
| `org.jetbrains.kotlin:kotlin-test*` | — | ❌ missing (skip `kotlin("test")` in the first iteration) |

So `:shared` = **1 plugin-resolution problem + at most 1 tiny jar**. Nothing else.

## 3. Recommended `:shared` design

### 3.1 `kotlin("jvm")`, not `com.android.library`

`com.android.library` produces an **AAR**, which `:desktop` (a JVM app) cannot consume; it would force a second copy of the protocol layer and defeat the whole point. A pure `kotlin("jvm")` jar is consumable by both `:app` (D8 handles plain jars) and `:desktop`. It also avoids AGP's `BuildConfig`/res plumbing, and — decisive for the offline requirement — a `kotlin("jvm")` module touches no Android tooling at all, so `assembleDebug --offline` only needs `:app`'s existing cache.

### 3.2 Settings change

```kotlin
include(":app")
include(":shared")          // pure Kotlin/JVM; no AGP
// later:
// include(":desktop")      // Compose Multiplatform JVM app
```
`pluginManagement`/`dependencyResolutionManagement` blocks stay untouched (they already list google + mavenCentral, which is where every Kotlin/Compose artifact lives). Root `build.gradle.kts` gains one line *only if* you want the canonical form:

```kotlin
plugins {
    id("com.android.application") version "9.5.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    kotlin("jvm") version "2.4.21" apply false     // ← requires the missing marker, see 3.3
}
```

### 3.3 The one offline blocker, and two ways through it

`./gradlew --offline` fails at configuration time with
`Plugin [id: 'org.jetbrains.kotlin.jvm', version: '2.4.21'] was not found` because Gradle resolves plugin ids through the **marker** artifact `org.jetbrains.kotlin.jvm:org.jetbrains.kotlin.jvm.gradle.plugin:2.4.21`, which is not cached (the *implementation* jar `org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21` **is**).

**Option A (primary, zero code changes): one online build.** Internet is confirmed working. Run once *without* `--offline`:

```
./gradlew :shared:compileKotlin          # fills marker + org.json + any missing metadata
./gradlew assembleDebug --offline        # verify the offline path still passes
```
After this the marker is in `modules-2` and `--offline` works for every later build.

**Option B (if the machine must stay offline): map the id onto the cached module** in `settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "org.jetbrains.kotlin.jvm" && requested.version != null)
                useModule("org.jetbrains.kotlin:kotlin-gradle-plugin:${requested.version}")
        }
    }
}
```
(Also works for `org.jetbrains.compose` → `org.jetbrains.compose:compose-gradle-plugin` once that jar is fetched; for `org.jetbrains.kotlin.plugin.serialization` → `org.jetbrains.kotlin:kotlin-serialization`.) Note `requested.version` must be given in the `plugins {}` block for this to work. KGP's runtime deps (api, compiler-embeddable, build-tools-impl, daemon-embeddable, stdlib 2.4.21) are all cached, so offline compilation should succeed — but this is *reasoned from the cache*, not observed (Gradle was off-limits). Option A is the low-risk one.

### 3.4 Bytecode target — the trap

`jvmToolchain(21)` sets the *compiler JDK* to 21; it does **not** by itself produce Java-17 bytecode. `:app` compiles with `sourceCompatibility/targetCompatibility = 17`, so `:shared` must emit **class file v61 (Java 17)** or `:app`'s D8 step is the thing that breaks, on the Android side, in a way that is easy to misdiagnose. Pin both:

```kotlin
plugins { kotlin("jvm") }

kotlin {
    jvmToolchain(21)                                  // reuse the daemon JDK (C:/Java/jdk21) — no provisioning
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    compileOnly("org.json:json:20240303")                                  // provided by android.jar on device
    implementation("com.squareup.okhttp3:okhttp:4.12.0")                   // ✅ cached
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2") // ✅ cached
}
```
No `repositories {}` (would violate `FAIL_ON_PROJECT_REPOS`), no `kotlin("test")` yet (artifact not cached), no `kotlinx-serialization` plugin.

### 3.5 `org.json` — two clean options

1. **`compileOnly("org.json:json:20240303")` in `:shared`** — one-time 70 KB download; Android supplies the class at runtime so no duplicate-class/`AGP` packaging conflict, and `:desktop` adds `implementation("org.json:json:20240303")` itself. Zero source churn; `ApiClient.kt` keeps working as audited in `A-protocol.md`.
2. **Switch `:shared` to `kotlinx-serialization-json:1.9.0` (already cached) using only `JsonObject`/`JsonElement` builders** — no compiler plugin, hence no missing marker. Cheaper offline, but it is a real edit to `ApiClient.kt` (its `Msg`/`Step` folding parses `org.json` trees) and it breaks `:app` still using `org.json` unless `:app` also migrates. Recommend (1) for the first milestone.

### 3.6 `:app` side

`implementation(project(":shared"))` in `app/build.gradle.kts`. Nothing else: no `kotlin-android` plugin, no `compileOptions` change, no new repo. Risk to `assembleDebug --offline` is limited to the new module's own resolution (which §3.3/§3.4 keep inside the existing cache).

## 4. `:desktop` (Compose Multiplatform) — what must be downloaded

Nothing desktop-related is cached. Present in cache are only *metadata-level* CMP module files (`org.jetbrains.compose.ui:ui:1.12.0` `.pom`/`.module`, plus `androidx.compose.material:material-icons-*-desktop:1.7.8`, `androidx.graphics:graphics-shapes-desktop:1.0.1`, `org.jetbrains.androidx.lifecycle:*:2.9.6` roots). There is **no** `org.jetbrains.compose` artifact group, **no** `ui-desktop`/`foundation-desktop`/`runtime-desktop`/`material-desktop` jar, and **no** `org.jetbrains.skiko`.

### 4.1 Plugin layer (2 artifacts + transitives)

| Coordinate | Note |
|---|---|
| `org.jetbrains.compose:org.jetbrains.compose.gradle.plugin:1.12.0` | marker, id `org.jetbrains.compose` (verified 200 on plugins.gradle.org) |
| `org.jetbrains.compose:compose-gradle-plugin:1.12.0` | implementation (verified 200 on Maven Central); brings the `compose.desktop.application` DSL used for `createDistributable`/`packageMsi` |

### 4.2 Runtime layer (via `compose.desktop.currentOs` on this machine = windows-x64)

| Coordinate |
|---|
| `org.jetbrains.compose.desktop:desktop-jvm-windows-x64:1.12.0` |
| `org.jetbrains.compose.desktop:desktop-jvm:1.12.0` |
| `org.jetbrains.compose.runtime:runtime-desktop:1.12.0`, `runtime-saveable-desktop:1.12.0` |
| `org.jetbrains.compose.foundation:foundation-desktop:1.12.0`, `foundation-layout-desktop:1.12.0` |
| `org.jetbrains.compose.material:material-desktop:1.12.0` |
| `org.jetbrains.compose.material3:material3-desktop:` **1.9.0** ← material3 lags the ui train: `1.12.0` does **not** exist (metadata ends at `1.13.0-rc01`; last stable is **1.9.0**) |
| `org.jetbrains.compose.ui:ui-desktop`, `ui-graphics-desktop`, `ui-text-desktop`, `ui-unit-desktop`, `ui-geometry-desktop`, `ui-util-desktop`, `ui-tooling-preview-desktop` — all `1.12.0` |
| `org.jetbrains.compose.animation:animation-desktop:1.12.0`, `animation-core-desktop:1.12.0` |
| `org.jetbrains.skiko:skiko-awt-runtime-windows-x64:` **0.150.1** (the version `desktop-jvm-windows-x64:1.12.0` pins) |
| `org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2` — **missing**, required by the Compose Desktop main dispatcher |
| `org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose-desktop:2.9.6`, `lifecycle-runtime-compose-desktop:2.9.6` (only root metadata cached; jars must come down) |
| `org.json:json:20240303` (runtime, for `:shared`) |
| optional: `org.jetbrains.compose.components:components-resources-desktop:1.12.0` if `compose.components.resources` is used |

Expect roughly **150–250 MB** of first-sync downloads (skiko alone ~10–15 MB, desktop Compose jars ~40–60 MB); the Kotlin compiler and AGP are already cached and are *not* re-downloaded. After that sync, `--offline` covers desktop builds too. Because the whole set lives on `repo1.maven.org`, only `mavenCentral()` is strictly required for `:desktop`.

`:desktop` build file shape (for the later phase):

```kotlin
plugins {
    kotlin("jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")   // ✅ 2.4.21 marker already cached
}
kotlin { jvmToolchain(21) }
dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(project(":shared"))
    implementation("org.json:json:20240303")
}
compose.desktop {
    application {
        mainClass = "dev.oscar.hermes.desktop.MainKt"
        nativeDistributions { targetFormats(TargetFormat.AppImage) }
    }
}
```

**Version-compat caveat (unverifiable offline):** 1) CMP `1.12.0` against Kotlin `2.4.21` — the Compose compiler comes from Kotlin itself (`org.jetbrains.kotlin.plugin.compose:2.4.21`), so the pairing is only a *runtime* ABI question; if the first online sync reports a Kotlin/CMP incompatibility, drop CMP to `1.12.1`/`1.12.2` (both stable, both published) or to `1.11.1`. 2) material3 at `1.9.0` alongside ui `1.12.0` is the published state of the repo, not a typo — if the plugin's `compose.material3` mapping disagrees, pin the coordinate explicitly and let the first online build tell you.

## 5. jpackage / installers

- `jpackage.exe` + `jlink` ship with the JDK already on disk: `C:\Java\jdk21\bin\jpackage.exe` (Temurin 21.0.13). **No Maven artifact is needed** for packaging — the Compose plugin only drives those binaries, and `createDistributable` (app-image) needs nothing else.
- **WiX is not installed** (no `C:\Program Files (x86)\WiX Toolset*`, no `candle`/`light` on PATH). Therefore `TargetFormat.Msi` / `.Exe` will fail at packaging time; `TargetFormat.AppImage` works today. Install WiX 3.14 (or switch the installer stage to Inno Setup, as `windows-client-plan.md` already suggests) before promising an MSI.
- `jpackage` also wants the JDK's `jmods` — present in Temurin 21 full JDK, so `--add-modules`-based runtime trimming is available offline.

## 6. Risk register / next actions

| Risk | Impact | Mitigation |
|---|---|---|
| Missing `kotlin.jvm` marker breaks `--offline` | config-time failure of the whole build (including `:app`) | Option A (one online sync) — do it once, verify with `./gradlew assembleDebug --offline` immediately after |
| `:shared` emitting Java-21 bytecode | `:app` D8 failure at `assembleDebug`, easy to blame on something else | explicit `jvmTarget = JVM_17` + `java { targetCompatibility = 17 }` (§3.4) |
| `org.json` duplicate classes | runtime/`packaging` conflicts | `compileOnly` in `:shared`, `implementation` in `:desktop` (§3.5) |
| CMP `1.12.0` vs Kotlin `2.4.21` | compile error on first desktop build | fall back to CMP `1.12.1`/`1.12.2`/`1.11.1`; empty desktop module first, then add deps |
| WiX absent | no MSI/EXE export | ship `app-image` + Inno Setup for the MVP |
| Anyone adding `repositories {}` to `:shared`/`:desktop` | hard failure (`FAIL_ON_PROJECT_REPOS`) | keep all repos in `settings.gradle.kts` only |
| Version drift by hand-editing strings | app/plan divergence | no catalog exists; when adding modules consider `gradle/libs.versions.toml` (cache-independent, but a separate refactor) |

**Suggested sequence (each step offline-verifiable):**
1. `include(":shared")` + `:shared/build.gradle.kts` (§3.4) + root `kotlin("jvm") … apply false`.
2. One online `./gradlew :shared:compileKotlin`, then `./gradlew assembleDebug --offline` → must pass.
3. Move `ApiClient.kt`/`GatewayWs.kt`/`Formats.kt` core (`A-protocol.md` verdicts) into `:shared`; `:app` gains `implementation(project(":shared"))`; re-verify offline.
4. Only then: `include(":desktop")`, empty Composable `main`, one online `:desktop:run` sync (~150–250 MB), re-verify `assembleDebug --offline`.
