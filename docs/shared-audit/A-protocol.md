# A — Protocol-layer audit: `ApiClient.kt`, `GatewayWs.kt`, `Formats.kt`, `L10n.kt`

Read-only audit for extracting a pure Kotlin/JVM `:shared` module (see `docs/windows-client-plan.md` §3, §4.1, §8, §9.4, 附录 A).
Scope: `app/src/main/java/dev/oscar/hermes/`. No builds run, no source edited. All line numbers refer to the current working tree (CRLF, stored as LF per `.gitattributes: * text=auto eol=lf`).

## 0. Headline

| File | Android coupling | Verdict |
|---|---|---|
| `ApiClient.kt` | **none** (0 `android.*`/`androidx.*` refs) | move as-is; only `org.json` + OkHttp + coroutines needed |
| `GatewayWs.kt` | 1 line: `android.util.Log` (:90) | move as-is + `Logger` seam |
| `Formats.kt` | 4 sites: Compose runtime state (:3,:4,:22), `Context` (:23), `DateFormat.is24HourFormat` (:28) | move after 2 small seams (`is24h`, `Locale` param) |
| `L10n.kt` | whole file is `Context`-based (:3,:4,:18,:21,:22,:26,:29) | split: pure `L10n` core to shared, `Context` half stays Android |

`R.`, `Uri`, `Toast`, `Handler`, `SystemClock`, `SharedPreferences`: **zero occurrences** in all four files. The four files are already free of `Dispatchers.Main` (only `Dispatchers.IO`, `ApiClient.kt:94`), so no Main-dispatcher abstraction is needed for this batch.

## 1. Per-file dependency inventory (exact sites)

### 1.1 `ApiClient.kt` (236 lines)

Non-platform (fine on JVM): `okhttp3.*` (:5–9, :79), `org.json.*` (:10–11), `java.io.IOException` (:12), `java.net.*` (:13–17), `java.util.UUID` (:18), `java.util.concurrent.TimeUnit` (:19), `kotlinx.coroutines` (:3–4).

| Kind | Site | Notes |
|---|---|---|
| `android.*` / `androidx.*` | **none** | |
| `R.` | none | error strings are hardcoded English (:84, :180–182, :189) — not localized today |
| Process-global mutable state | `@Volatile var allowLanHttp` (:196) | read by `isTailscaleHost` (:201); also written by `SecureStore.kt:26` and read indirectly by `Inbox.kt:26`, `UpdateManager.kt:54` |
| `java.net.URI` parsing of the relay URL | :82–86 | throws `ApiException` for plain `http` unless Tailscale (:83) |
| `Dispatchers.IO` | :94 | JVM-safe |

**Latent portability issue (not an Android dep):** `http://127.0.0.1:8643` — the desktop default per §4.2 — fails the plain-http guard at :83–84, because `isTailscaleHost("127.0.0.1")` is false and `allowLanHttp` is false by default. The desktop step needs loopback explicitly allowed (`h == "127.0.0.1" || h == "localhost" || h == "::1"`), which cannot change phone behavior (the relay is never loopback on a phone). Flagged here so the shared move doesn't ship a desktop that cannot connect.

### 1.2 `GatewayWs.kt` (103 lines)

| Kind | Site | Notes |
|---|---|---|
| `android.util.Log.w` | **:90** | `android.util.Log.w("HermesRpc", "$method failed: $reason (code …)")`, comment says no params/content logged — keep that guarantee in the `Logger` seam |
| `android.*` other | none | |
| Process-global mutable state | `companion object { @Volatile var pingSec = 30L }` (:35) | written from `ChatViewModel.kt:313,315` and `KeepAlive.kt:46` (power saver → 60 s) |
| Global instance flag | `@Volatile var open` (:38) | |
| URL scheme rewrite | `:30` `replaceFirst("http", "ws")` | also turns `https` → `wss`; OK but string-based |
| Hardcoded port in user-facing text | `:49` `":8643"` | misleading for remote relays; cosmetic |
| `java.net.URI` + plain-http guard | :27–29 | duplicates `ApiClient` policy via `ApiClient.isTailscaleHost` — shared→shared call, fine |

No `Context`, `Uri`, `Handler`, `SystemClock`, `Toast`, `SharedPreferences`, `R.`.

### 1.3 `Formats.kt` (123 lines)

| Kind | Site | Notes |
|---|---|---|
| `androidx.compose.runtime.getValue` | :3 | needed for `var clock by mutableStateOf(…)` |
| `androidx.compose.runtime.setValue` | :4 | same |
| `androidx.compose.runtime.mutableStateOf` | **:22** (`var clock by androidx.compose.runtime.mutableStateOf("system")`) | fully-qualified in source, so no import for it; the two delegates above make it Compose state |
| `android.content.Context?` | **:23** (`var appCtx: android.content.Context? = null`) | only used at :28 |
| `android.text.format.DateFormat.is24HourFormat(it)` | **:28** | system 12/24 h preference |
| `java.util.Locale.getDefault()` | :36, :37 | implicitly inherited from the app's chosen language because `L10n.wrap` calls `Locale.setDefault` (`L10n.kt:20`) |
| `java.text.SimpleDateFormat`, `java.util.Calendar` | :38–52 | JVM-safe; `Calendar.getInstance()` uses default TZ |

Not a dependency but coupling to note: `Formats` is the only place in this batch that is *state that drives recomposition* — `MainActivity.kt:489` keys a `remember` on `Formats.clock`.

### 1.4 `L10n.kt` (30 lines)

| Kind | Site | Notes |
|---|---|---|
| `android.content.Context` | :3 (import), :18 `wrap(base: Context, …): Context`, :26 `ctx(base: Context)`, :29 `isZh(ctx: Context)` | |
| `android.content.res.Configuration` | :4, :21 | |
| `base.resources.configuration` | :21 | |
| `Context.createConfigurationContext` | :22 | |
| `SecureStore(base).lang` | :26 | pulls `SharedPreferences` in transitively (`SecureStore.kt:69–71`) |
| `ctx.resources.configuration.locales[0]` | :29 | API 24+, fine for minSdk 29 |
| **Process-global side effect** | **:20 `Locale.setDefault(loc)`** | not an Android API but a JVM-wide mutation; on desktop it would change the whole process locale (Compose + every formatter). Must stay opt-in and documented. |

Pure parts: `LANGS` (:9), `locale(lang)` (:11–15).

## 2. Public API actually called from other files

Call sites verified by grep across `app/src/main/java/dev/oscar/hermes`; grep also confirms **no** `L10n.LANGS` / `L10n.locale` call sites outside `L10n.kt` (dead-but-public today).

### 2.1 `ApiClient.kt`

| Symbol | Decl | External call sites |
|---|---|---|
| `Session` (id, title, preview, lastActive, count, source) | :21 | `ChatViewModel.kt:129,902,913,922,926,988,998,1009,1020,1439,1442,1507,1558,1560,1646`, `MainActivity.kt:354–356,373,383,664` |
| `ModelChoice` / `ModelOptions` | :22 / :25 | `ChatViewModel.kt:261` (`modelOptions`) |
| `Skill(name, description)` | :26 | `ChatViewModel.kt:262,675` (constructed there, not fetched via `ApiClient`) |
| `Msg` | :27 | only `ApiClient.messages()` (:128,138) returns it; `ChatViewModel.kt:1544–1550` immediately converts to its own `UiMsg` |
| `Step(kind,name,detail,output,toolId)` | :30 | `ChatViewModel.kt:41` field type, `:1764–1878`; `MainActivity.kt:1013` (`StepsView`) |
| `Steps.argsText` / `outputText` / `thinkingOf` | :34/:46/:58 | `ChatViewModel.kt:1772,1777`; `thinkingOf` internal (:142) |
| `ApiException(msg, unreachable)` | :72 | ~45 sites in `ChatViewModel.kt`, plus `Downloader.kt:55,69,92,102`, `Inbox.kt:36,38,53,63` |
| `ApiClient(relayUrl, profile, key)` | :78 | `ChatViewModel.kt:297,476,490,509,888,1110,1521` |
| `.lanIps()` | :105 | `ChatViewModel.kt:490` |
| `.health()` | :107 | `ChatViewModel.kt:476,509,771,888,2162` |
| `.sessions()` | :109 | `ChatViewModel.kt:839,936,1110,2162` |
| `.messages(id)` | :117 | `ChatViewModel.kt:1461,1544` |
| `.rename(id,title)` / `.delete(id)` | :167 / :172 | `ChatViewModel.kt:1559,1561,1012,1416,1595` |
| companion `allowLanHttp` | :196 | `ChatViewModel.kt:153`, `SecureStore.kt:26` |
| companion `isTailscaleHost` | :197 | `GatewayWs.kt:28`, `Inbox.kt:26`, `UpdateManager.kt:54` |
| companion `contentText` | :205 | internal (:138,143,153); public but unused elsewhere |
| companion `parseModelOptions` | :216 | `ChatViewModel.kt:683` |

### 2.2 `GatewayWs.kt`

| Symbol | Decl | External call sites |
|---|---|---|
| ctor `(relayUrl, profile, key, onEvent, onServerRequest, onClosed)` | :20–25 | `ChatViewModel.kt:581` (single construction point `gatewayFor`, :576) |
| `.connect()`, `.call()`, `.respond()`, `.close()`, `.open` | :40,:82,:96,:102,:38 | `ChatViewModel.kt:601,614,666,756,954,1477,1709,1981,2034`; `.open` also read in `ChatViewModel` |
| companion `pingSec` | :35 | `ChatViewModel.kt:313,315`, `KeepAlive.kt:46` |

### 2.3 `Formats.kt`

| Symbol | Decl | External call sites |
|---|---|---|
| `Seg` / `Seg.Md` / `Seg.Code` | :10–13 | `MainActivity.kt:1118–1123` |
| `Formats.clock` (write) | :22 | `ChatViewModel.kt:310,311` |
| `Formats.appCtx` (write) | :23 | `ChatViewModel.kt:310` |
| `Formats.fmtTime` | :35 | `MainActivity.kt:489,1090` |
| `Formats.split` | :58 | `MainActivity.kt:1118` |
| `Formats.jsonPretty` | :114 | internal only (:84,90,102,103) |

### 2.4 `L10n.kt`

| Symbol | Decl | External call sites |
|---|---|---|
| `wrap(base, lang)` | :18 | `ChatViewModel.kt:276`, `MainActivity.kt:84` |
| `ctx(base)` | :26 | `KeepAlive.kt:23,24,94` |
| `isZh(ctx)` | :29 | `MainActivity.kt:254` |
| `LANGS`, `locale` | :9, :11 | none outside the file |

## 3. Extraction plan

### 3.1 Module shape

- `:shared` = `kotlin("jvm")` (not KMP) for this batch: nothing here needs `expect/actual`, so plain JVM interfaces + constructor/`install` injection are simpler and keep `app` able to depend on it (`implementation(project(":shared"))`). §4.1's `jvm + android` KMP target can be added later without moving code again.
- `jvmToolchain(17)` / `target 17` to match `app/build.gradle.kts:22–25`; `minSdk 29` irrelevant for a JVM lib.
- Dependencies: `kotlinx-coroutines-core`, `com.squareup.okhttp3:okhttp:4.12.0`, `org.json:json` (**≥ 20231013**, for `JSONObject.toString(2)` used at `Formats.kt:119`). Kotlin 2.4.21 is already pinned by `org.jetbrains.kotlin.plugin.compose` (`build.gradle.kts:3`) — reuse that version for the `kotlin("jvm")` plugin.
- **Keep `package dev.oscar.hermes`** in `shared`. Same FQN in two modules is legal on the JVM and on AGP; it means zero import edits in `ChatViewModel.kt`/`MainActivity.kt`/`Inbox.kt`/`UpdateManager.kt` when the files move (`git mv` + delete from `app`). Only `app/build.gradle.kts` gains one dependency line. If the team rejects split packages, the fallback is `git mv` + one import rewrite per consumer — 20 call sites total, mechanical.
- Models (`Session`, `ModelChoice`, `ModelOptions`, `Skill`, `Msg`, `Step`) should move with `ApiClient` in this batch (they live in that file) — `ChatEngine` will need them next.

### 3.2 `ApiClient.kt` — move unchanged, no seams

Nothing Android-specific exists. Concrete steps:

1. `git mv` to `shared/src/main/kotlin/dev/oscar/hermes/ApiClient.kt`.
2. No code change. `Steps`, `ApiException`, models move with it.
3. Keep `allowLanHttp` as-is (one process = one flag; desktop writes it from its settings exactly like `SecureStore.kt:26` does).
4. Do **not** change `errorText` (:176–183) or `wrap` (:186–191) wording — phone strings/UIs and `ApiException.unreachable` semantics (`ChatViewModel.kt:440`) depend on it.
5. Deferred (desktop step only): loopback allowance in `isTailscaleHost` / root guard (§1.1). Also `Dispatchers.IO` is fine; when `ChatEngine` lands, introduce `DispatcherProvider` then, not now.

### 3.3 `GatewayWs.kt` — one seam: `Logger`

```kotlin
// shared
interface Logger { fun w(tag: String, msg: String); companion object { var default: Logger = ConsoleLogger } }
```
- Replace :90 with `Logger.default.w("HermesRpc", …)` (tag/message unchanged, still no params/content).
- Alternative that avoids a global: add `logger: Logger = Logger.default` as the last ctor param — `ChatViewModel.kt:581` uses named args already (`relayUrl`, `prof`, key lambda args), so a defaulted trailing param compiles unchanged. **Preferred**: explicit, testable.
- Android impl: keep `android.util.Log.w` semantics via `AndroidLogger` (or just use `java.util.logging` on both sides; existing `android.util.Log` output is only diagnostic, no logcat parsing anywhere — grep shows 6 uses total in `app`, all informational).
- `pingSec` companion stays global (Android already drives it from power mode at `ChatViewModel.kt:313`; desktop pins 30 per §4.2).
- Keep `ApiException(..., unreachable = true)` on failure/close (:52,:62,:86) — `ChatViewModel.kt:440` branches on it.
- Do not touch the `:49` `":8643"` message in this batch; note it as a desktop follow-up (wrong for remote relays).

### 3.4 `Formats.kt` — two seams, `Seg` moves unchanged

Replace the three Android/Compose dependencies with:

```kotlin
object Formats {
    // 1) was: androidx.compose.runtime.mutableStateOf -> plain var + platform mirror
    var clock: String = "system"          // "system" | "12" | "24"
    // 2) was: var appCtx: Context? / DateFormat.is24HourFormat(appCtx)
    var is24h: () -> Boolean = { true }   // installed by the platform at startup
    fun fmtTime(ms: Long, loc: Locale = Locale.getDefault()): String
}
```

- `use24()` (:25–29) becomes `when (clock) { "12" -> false; "24" -> true; else -> is24h() }` — literally the same expression with the Context call replaced by the lambda (`?: true` default preserved).
- Android install site: `ChatViewModel.kt:310` becomes `Formats.clock = it; Formats.is24h = { android.text.format.DateFormat.is24HourFormat(app.applicationContext) }`.
- **Recomposition must be preserved.** Today `MainActivity.kt:489` does `remember(s.lastActive, Formats.clock)`. `ChatViewModel` already exposes `var clock by mutableStateOf(...)`; change that call site to key on `vm.clock` (or `formatsClock`) so the Compose-state read remains. `ChatViewModel.kt:311` must also keep writing both. Without this, times stop refreshing when the user flips 12/24 h — a real behavior regression, cheap to avoid.
- `appCtx` is deleted; `Formats.appCtx` has exactly one writer (`ChatViewModel.kt:310`).
- `java.text.SimpleDateFormat` / `Calendar` stay: `java.time` would change output for the `zh` patterns at :50–52 (e.g. `去年M月d日`) and risk `days<=0` boundary drift at :45,48. No reason to migrate.
- Locale: shared `fmtTime` keeping `Locale.getDefault()` preserves today's behavior *only* because `L10n.wrap` sets the default (`L10n.kt:20`). Passing `loc` explicitly is the safer API and lets desktop drop the `setDefault` side effect later; default-arg keeps phone call sites identical.
- `Seg`, `split`, `bare`, `code`, `looksXml`, `jsonPretty` move verbatim (pure Kotlin + `org.json`).

### 3.5 `L10n.kt` — split in two, no shared `Context`

Shared (`shared/.../L10n.kt`):
```kotlin
object L10n {
    val LANGS = listOf("system", "en", "zh")
    fun locale(lang: String): Locale?           // :11–15 unchanged
    fun isZh(lang: String): Boolean             // new: pure, for ChatEngine decisions
}
```
Android (`app/.../L10nAndroid.kt` or keep file name, `internal object L10nCtx`):
```kotlin
fun wrap(base: Context, lang: String): Context  // :18–23 unchanged
fun ctx(base: Context): Context                 // :26 unchanged (SecureStore stays Android)
fun isZh(ctx: Context): Boolean                 // :29 unchanged
```
- `L10n.wrap` / `ctx` / `isZh(ctx)` call sites (`ChatViewModel.kt:276`, `KeepAlive.kt:23,24,94`, `MainActivity.kt:84,254`) get imported from the Android half. If the shared core is named `L10n` and the Android half `L10nCtx`, that's 5 import lines — or keep `object L10n` on the Android side and put the core in `L10nCore` to guarantee no call-site change. **Recommend `L10nCore` + unchanged `L10n`** for this batch (zero edits in `app`), then rename on the desktop port.
- `Locale.setDefault` (:20) stays in the Android `wrap` — it is required for the `zh` date patterns in `Formats` and for `getString` resource selection. Record it as a known global side effect; desktop should prefer passing `Locale` into `Formats.fmtTime` instead of replicating it.

### 3.6 Order of work (low risk → higher)

1. `shared` module skeleton + `kotlin("jvm")`, okhttp/org.json/coroutines, `app` depends on it. Build green with empty shared.
2. `ApiClient.kt` (+ models, `Steps`, `ApiException`) `git mv` — no edits.
3. `GatewayWs.kt` `git mv` + `Logger` param with default.
4. `Seg`/`Formats` move + `is24h` seam + `MainActivity.kt:489` key change.
5. `L10nCore` split (mechanical, 5 imports if you choose the rename variant).
6. Only then start `ChatEngine` (out of scope here) — it consumes exactly the API surface in §2.

## 4. Behavior-preservation checklist

- [ ] Error strings and `ApiException.unreachable` flags in `ApiClient`/`GatewayWs` byte-identical (`ChatViewModel.kt:440` and many `fail(e)` paths surface them raw).
- [ ] `Steps.argsText`/`outputText`/`thinkingOf` untouched — 600/1200/3000-char truncations and the `clarify` rendering are user-visible.
- [ ] `Formats.split` untouched — `\r\n` normalization (:62), unterminated fences while streaming (:70–73), `jsonPretty` `"\\/"` unescape (:121).
- [ ] 12/24 h still defaulting to true when no Context is installed (:28 `?: true`).
- [ ] `MainActivity.kt:489` still reads a Compose state for the clock (see §3.4).
- [ ] `GatewayWs.pingSec` still writable from power mode (`ChatViewModel.kt:313,315`, `KeepAlive.kt:46`).
- [ ] `ApiClient.allowLanHttp` still a single process-wide flag (`SecureStore.kt:26` writes it on the main thread at init).
- [ ] Line endings: files are CRLF in the worktree but `.gitattributes` stores LF, so `git mv` produces a clean rename — do **not** reformat while moving (`Formats.kt` already has mixed CRLF/LF; a "normalize" commit would bury the move).

## 5. Residual risks

1. **Duplicated `HttpUrl`/URI policy** between `ApiClient` (:81–86) and `GatewayWs` (:26–31): already shared via the companion today; keep it that way (don't copy the check into a third place).
2. **`org.json` dialect drift**: Android's bundled `JSONObject` vs `org.json:json`. Same package/API, but `toString(indent)` whitespace and number formatting differ slightly in old versions — pin `org.json:json` ≥ 20231013 and spot-check `Formats.jsonPretty` output on one JSON/XML reply. Only affects the desktop rendering path; phone keeps Android's class.
3. **Split package** (`dev.oscar.hermes` in two modules) is legal but will surprise future readers; if rejected, budget one mechanical pass over 20 call sites (§2).
4. **`Locale.setDefault`** is process-global; on desktop it will influence third-party formatters. Keep it out of shared.
5. **Companion-object globals** (`allowLanHttp`, `pingSec`) become process-wide the moment desktop runs both a relay-local and a remote client in one process — correct today, revisit if the desktop ever supports two simultaneous relays in one JVM (plan says single window/single relay, so fine).
6. Untested assumption: `Formats.fmtTime` "N days ago" math (:45) uses default `TimeZone`; desktop inherits it identically only if it does not set a fixed zone.
