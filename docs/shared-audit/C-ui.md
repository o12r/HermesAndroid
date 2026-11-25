# C — UI/state extraction audit (Compose → Compose Multiplatform Desktop)

Read-only audit. Package `dev.oscar.hermes`, source
`app/src/main/java/dev/oscar/hermes/`. Line numbers are current as of this audit.
No builds were run; findings are from static reading.

Goal: reuse the Compose UI in a Compose Desktop Windows client.

---

## 0. Headline findings

| # | Finding | Impact |
|---|---|---|
| 1 | Every screen takes `ChatViewModel` directly. It is an `AndroidViewModel(app: Application)` (ChatViewModel.kt:124) that imports `android.graphics.Bitmap`, `ImageDecoder`, `ConnectivityManager`, `Uri`, `OpenableColumns`, `Base64` (ChatViewModel.kt:3–10). | Shared UI cannot compile without either a shim type or a `ChatViewModel` interface split. This is the single biggest blocker, bigger than any composable. |
| 2 | All UI text goes through `stringResource(R.string.…)` — **218** uses in MainActivity.kt alone, 30 in PcFiles.kt, 10 in SettingsGroups.kt, 3 each in ModelPicker/FileBubble. | Compose Multiplatform has no `R`/`stringResource`. Needs a resource indirection (`Strings` object or compose-resources) before anything is shareable. Mechanical but touches every composable. |
| 3 | Markdown rendering is Markwon + `AndroidView(TextView)` (MainActivity.kt:1147–1156). | Must be replaced by a Compose renderer (e.g. a pure-Kotlin markdown AST → `AnnotatedString`); everything else in the chat list is portable. |
| 4 | ~85% of the composables are plain Compose + Material3. Android-only code clusters in 5 small spots per screen (back handling, pickers, toasts, clipboard, insets) — each is a single call site, not spread out. | A ~15 call-site `Platform` interface (back, toast, pick file, clipboard, insets, orientation) unblocks nearly all screens. |
| 5 | `Downloader.kt` is already pure JVM (OkHttp + `java.io` only). | Reusable as-is on desktop. |
| 6 | `SecureStore`, `UpdateManager`, `Inbox`, `KeepAliveService`, `ScanActivity` are 100% Android. | Not shareable; need desktop counterparts, not ports. |

Out of scope here but relevant: `Formats.kt` and `PcStats.kt` are already plain Compose (no `android.*` imports) — they are free wins for the shared set.

---

## 1. Per-file inventory

### 1.1 MainActivity.kt — 1484 lines / 110 KB

Holds the activity, two theme transforms, two `ChatViewModel` extension shims, and **all** screens of the app.

**Non-composable content**
- `class MainActivity : ComponentActivity` — 79–140 (the only `Activity` in this file).
- `trueBlack` / `darkGray` theme transforms — 142–156 (plain `ColorScheme.copy`, portable).
- `ChatViewModel.openContextSettings` / `openModelSettings` — 158–161.
- `fmtSpeed` 1296–1297, `fmtTok` 1299–1304 — pure Kotlin, portable.
- Two **private** `Modifier` extensions — 1481–1484 (used only by `ZoomImage`).

**Composables (30 top-level + 2 local)**

| Lines | Composable | Plain Compose? | Android-only dependency (file:line) |
|---|---|---|---|
| 163–250 | `App` (shell, screen switch, all global dialogs) | No | `LifecycleEventEffect` 165; `BackHandler` 166; `systemBarsPadding/imePadding` 169; `android.R.string.cancel` 201, 207 |
| 252–261 | `HotfixBanner` | No | `L10n.isZh(LocalContext.current)` 254 |
| 263–285 | `UpdateDialog` | Yes | — (calls `vm.installUpdate()`) |
| 287–301 | `Banner` | No | clipboard 294; `Toast` 296 |
| 303–316 | `ProfileMenu` | Yes | — |
| 318–349 | `ReorderProfiles` (drag-reorder) | Yes | — (`LocalDensity` only) |
| 351–615 | `SessionsScreen` (tree of profiles › projects › sessions, multi-select, ~12 dialogs, embeds `PcFileBrowser`) | Mostly | `BackHandler` 368 |
| 617–754 | `ChatScreen` (list, auto-scroll, composer, fullscreen editor dialog) | No | `rememberLauncherForActivityResult(OpenMultipleDocuments)` 623; `SystemClock.uptimeMillis` 645; `LocalConfiguration` 705, 708; `Dialog(usePlatformDefaultWidth=false)` 716–717 |
| 756–840 | `RuntimeChips` (model / effort / tasks / context chips) | Yes | — |
| 844–856 | `HermesTopBar` | Yes | — |
| 858–922 | `ChatTabs` (draggable tab strip, unread dots) | Yes | — |
| 925–940 | `SlashSuggestions` | Yes | — |
| 943–966 | `Bubble` (dispatch: system / task / file / user / assistant) | No | via `FileBubble` 952 |
| 969–998 | `TaskCard` (sub-agent / background task card) | Yes | — |
| 1001–1009 | `AssistantBody` | Yes | — |
| 1012–1049 | `StepsView` (thinking / tool output, selectable) | Yes | — |
| 1052–1077 | `ColumnScope.FailedActions` | Yes | — |
| 1082–1113 | `ColumnScope.MessageActions` | No | `Toast` 1098, `LocalContext` 1085 |
| 1116–1126 | `Markdown` (splits prose vs code via `Formats.split`) | Yes | — |
| 1128–1145 | `CodeBlock` | Yes | — |
| 1147–1156 | `MarkdownText` | **No** | Markwon 1152–1153 + `AndroidView(TextView)` 1154–1155; `toArgb` 1150 |
| 1158–1163 | `ChoiceRow` (generic chip row) | Yes | — |
| 1166–1294 | `SettingsScreen` (card groups → conn / look / chat / model / upd / about) | No | `BackHandler` 1174; `LocalContext as? MainActivity` for QR scan 1216; `systemGestureExclusion` 1260; `recreate()` for font/language |
| 1307–1315 | `screenLabel` | Yes | — |
| 1319–1374 | `ScreensScreen` (PC screens: physical + agent, `PcStatsBar`) | Yes | none in file; screenshot arrives as `android.graphics.Bitmap` via `vm.screenShot()` 1424 |
| 1376–1382 | `screenActions` | Yes | — |
| 1385–1390 | `screenColor` | Yes | — |
| 1392–1413 | `ScreenRow` | Yes | — |
| 1416–1443 | `ScreenInline` (screenshot preview + actions) | No | `asImageBitmap()` on Android `Bitmap` 1424 |
| 1446–1479 | `ZoomImage` (pinch zoom, forced landscape, hidden system bars) | **No** | `Activity.requestedOrientation` 1449–1456; `LocalView` + `WindowCompat` 1459–1465; `DialogProperties(decorFitsSystemWindows)` 1457 |

**Activity-only Android surface** (all in 79–140):
`ComponentActivity` 79, `by viewModels()` 80, `attachBaseContext` + locale/fontScale 83–88,
`onNewIntent`/`handleOpen`/`handlePair` (deep link `hermes://pair`) 90–113, `Toast` 111,
`KeepAliveService.start/stop` 117, 122, `askBatteryExemption` 124,
`POST_NOTIFICATIONS` runtime permission 125–127, `enableEdgeToEdge` 121,
`dynamicLightColorScheme/dynamicDarkColorScheme` 131–136 (**Android-12 dynamic color — no desktop equivalent**; on desktop use a fixed scheme with the same `trueBlack`/`darkGray` transforms).

Counting: 20 of the 30 top-level composables in this file are plain Compose once `stringResource` is abstracted. The 10 that touch platform APIs are `App`, `HotfixBanner`, `Banner`, `ChatScreen`, `Bubble` (only via `FileBubble`), `MessageActions` (toast only), `MarkdownText`, `SettingsScreen`, `ScreenInline` and `ZoomImage` — of which `Banner`/`MessageActions`/`HotfixBanner` need just one hook each (toast / locale).

---

### 1.2 SettingsGroups.kt — 121 lines / 8 KB

- `ContextSettingsGroup` 31–58, `ModelSettingsGroup` 71–121, private `entryOf` 61–64.
- No `android.*` imports at all. Depends on `stringResource`, `ChatViewModel`, `org.json.JSONObject` (org.json must be added as an explicit dependency off-Android; on desktop prefer kotlinx.serialization or bundle org.json).
- **Verdict: portable as-is** (after string resources).

### 1.3 ModelPicker.kt — 82 lines / 5 KB

- `ModelPickerField` 37–82. No `android.*` imports.
- **Verdict: portable as-is.**

### 1.4 FileBubble.kt — 95 lines / 5 KB

- `isImageName` 47, `FileBubble` 54–95, private ext sets 43–44.
- Android: `ClipData` 3, `Intent` 4, `Bitmap` 5, `Uri` 6, `Size` 7, `MimeTypeMap` 8; thumbnail via `ctx.contentResolver.loadThumbnail` 62–64; `startActivity(createChooser)` 68–74.
- Portable core: the Surface/Image/progress layout (75–94).
- **Verdict: split.** `FileBubble(name, thumbnail: ImageBitmap?, isVideo, progress, onSave, onOpen)` = portable; thumbnail loading + open-with-intent = per-platform `FilePreviewer`/`openFile` hook.

### 1.5 PcFiles.kt — 246 lines / 17 KB

- `data class FsEntry` 36, `joinPath` 38, `PcFileBrowser` 44–246 (one large composable; local `row()` at 134).
- Android: `Uri` 3, `Toast` 4, `BackHandler` 5, `rememberLauncherForActivityResult` 6–7; `LocalClipboardManager` 46/80; `Dialog`/`DialogProperties` 199; `statusBarsPadding/navigationBarsPadding` 97; SAF upload picker 81–94 (`OpenMultipleDocuments`), `ApiException` overwrite flow 87.
- Everything else (`LazyColumn`, path bar `BasicTextField`, context menus, mkdir/rename/delete/overwrite dialogs) is plain Compose and network-only via `vm.fsJson/fsUpload/fsDownload`.
- **Verdict: portable with 4 hooks** — `onBack`, `toast`, `pickFiles`, `clipboard` — and a desktop `Dialog` policy. Good candidate to share early; it is self-contained and visually the same on desktop.

### 1.6 SecureStore.kt — 164 lines / 10 KB

- Plain class, no Compose. Android: `SharedPreferences` 15, `android.util.Base64` 147/151, `AndroidKeyStore` + AES-GCM 134–154.
- Public API is ~40 properties/methods, all UI-facing (`store.theme`, `store.lang`, `store.hasKey`, `apiKey`, per-profile model/effort, cached session snapshots, inbox seen-set, reply stats).
- **Verdict: not portable.** Extract an interface (`SettingsStore` / `SecretStore`) that the shared UI uses, with `AndroidSecureStore` (current) and `DesktopStore` (JSON file under `%APPDATA%\hermes-client` + DPAPI/Credential Manager for the key) behind it. Do this *before* moving UI, otherwise every screen drags `Context` in.

### 1.7 UpdateManager.kt — 184 lines / 10 KB

- `UpdateInfo` 18, `Hotfix` 21–35 (pure data + JSON parse — portable), `UpdateManager` 42–165, `InstallReceiver` 168–184.
- Android: `PackageManager` version lookup 47–49, `PowerManager`/`WifiManager` locks 108–112, `PackageInstaller` session 127–136, `PendingIntent` 3/113/135, `BroadcastReceiver` 168–183, `Notify.installReady` 177, `Toast` 180, `cacheDir` 103/141/144.
- Portable part: `check()/relay()/github()/hotfix()` (63–99) and `Hotfix.parse`.
- **Verdict: split.** Keep `UpdateInfo`/`Hotfix`/version-check logic in the shared layer; replace install with a desktop updater (download MSI/zip, `Desktop.getDesktop().open` or relaunch). `APP_ID`/`GITHUB_REPO` stay.

### 1.8 Inbox.kt — 86 lines / 5 KB

- `object Inbox`: `Item` 18–21, `list()` 34–42 (pure HTTP + JSON, portable), `save()` 45–79 (all Android: `cacheDir`, `MediaStore`, `ContentValues`, `contentResolver`), `sizeText()` 81–85 (pure, portable; already used by PcFiles.kt:146).
- **Verdict: split.** `InboxApi` (list + `sizeText`) shared; `save()` becomes platform `downloadToFolder()` (Downloads known folder + `Desktop.open`).

### 1.9 Downloader.kt — 107 lines / 6 KB

- `object Downloader.fetch()` 28–77, `probe` 80–85, `single` 87–106, private `executeCancellable` 16–19.
- Deps: OkHttp, `java.io`, coroutines, `kotlin.coroutines` — **zero Android**.
- **Verdict: fully reusable on desktop, unchanged.**

### 1.10 KeepAlive.kt — 135 lines / 8 KB

- `KeepAliveService` 16–73 (foreground service, wake lock, battery-saver receiver, `askBatteryExemption` 64–70), `object Notify` 76–135 (channels 92–97, `openApp` PendingIntent 103–107, `alert` 118–123, `installReady` 111–115).
- 100% Android; `Notify.openApp` even hard-references `MainActivity::class.java` (105).
- **Verdict: not shared.** Desktop replaces with tray icon + `SystemTray` balloon notifications (or WinRT toasts). The *calls* the ViewModel makes (`Notify.alert`, `Notify.clear`) should become a `Notifier` interface so the ViewModel is shareable.

### 1.11 ScanActivity.kt — 72 lines / 4 KB

- `ScanActivity : ComponentActivity` 28–72. CameraX (`ProcessCameraProvider`, `Preview`, `ImageAnalysis`, `PreviewView`) 13–17, zxing `MultiFormatReader` 19–24, runtime `CAMERA` permission 34/43, classic `FrameLayout`/`TextView` views 38–42 (not Compose).
- **Verdict: not shared.** Desktop needs a different QR path (webcam + zxing, or just paste the `hermes://pair` URL). Keep zxing decoding logic only if a desktop scanner is wanted.

---

## 2. Portable vs Android-required — summary table

| Layer | Shareable in CMP Desktop | Needs platform work |
|---|---|---|
| Settings screens (`SettingsGroups`, `ModelPicker`, `ChoiceRow`, `ProfileMenu`) | ✅ (all logic, no android.*) | string resources |
| Chat list (`Bubble`, `TaskCard`, `AssistantBody`, `StepsView`, `FailedActions`, `MessageActions`, `CodeBlock`, `Markdown`) | ✅ | `MarkdownText` (Markwon → Compose), `Toast` (2 sites) |
| Chat chrome (`HermesTopBar`, `ChatTabs`, `SlashSuggestions`, `RuntimeChips`, `ReorderProfiles`) | ✅ | — |
| Sessions tree (`SessionsScreen`) | ✅ | `BackHandler` (1), `PcFileBrowser` hooks |
| PC files (`PcFiles`) | ✅ | back, toast, SAF picker, clipboard, Dialog policy |
| PC screens (`ScreensScreen`, `ScreenRow`, `ScreenInline`, `screenLabel`, `screenActions`) | ✅ | `Bitmap`→`ImageBitmap` conversion at 1424 |
| `ZoomImage` | ⚠️ mostly | orientation lock, system-bar hiding, `DialogProperties` |
| `FileBubble` | ⚠️ layout only | thumbnails, open-with intent |
| `App` shell | ⚠️ | `BackHandler`, `LifecycleEventEffect`, insets |
| Activity, `ScanActivity`, `KeepAliveService`, `Notify` | ❌ | full replacement |
| `SecureStore`, `UpdateManager.install`, `Inbox.save` | ❌ | interface + desktop impl |
| `Downloader`, `Formats`, `PcStats`, `Hotfix`/update *check* | ✅ | none |

**Hook surface to introduce first (≈15 call sites):**
`back(cb)`, `toast(msg)`, `copy(text)`, `pickFiles(multi) -> List<Path>`, `openFile(path)`,
`saveFile(name, bytes)`, `screenInsets()/imeInsets()`, `orientation(landscape?)`, `openUrl(url)`,
`strings`, `notifier`, `settingsStore`.

---

## 3. Recommended split of MainActivity.kt

Pure file moves — same package, same declarations, same order, imports pruned per file, no renames, no signature changes. Behavior is identical because nothing but file boundaries change (all symbols are top-level and `internal`-by-default visibility is unchanged inside one module).

| New file | Lines from MainActivity.kt | Contents |
|---|---|---|
| `MainActivity.kt` *(stays, shrinks to ~161)* | 1–161 | `class MainActivity`, `trueBlack`, `darkGray`, `openContextSettings`, `openModelSettings` |
| `AppShell.kt` | 163–250 | `App` |
| `AppChrome.kt` | 252–349 | `HotfixBanner`, `UpdateDialog`, `Banner`, `ProfileMenu`, `ReorderProfiles` |
| `SessionsScreen.kt` | 351–615 | `SessionsScreen` |
| `ChatScreen.kt` | 617–754 | `ChatScreen` |
| `ChatComposer.kt` | 756–840 | `RuntimeChips` |
| `ChatTabs.kt` | 842–940 | `HermesTopBar`, `ChatTabs`, `SlashSuggestions` |
| `MessageBubbles.kt` | 942–1113 | `Bubble`, `TaskCard`, `AssistantBody`, `StepsView`, `FailedActions`, `MessageActions` |
| `Markdown.kt` | 1115–1156 | `Markdown`, `CodeBlock`, `MarkdownText` (Android-only island — easy to swap later) |
| `SettingsScreen.kt` | 1158–1294 | `ChoiceRow`, `SettingsScreen` |
| `Format.kt` | 1296–1304 | `fmtSpeed`, `fmtTok` |
| `ScreensScreen.kt` | 1306–1443 | `screenLabel`, `ScreensScreen`, `screenActions`, `screenColor`, `ScreenRow`, `ScreenInline` |
| `ZoomImage.kt` | 1445–1484 | `ZoomImage`, `androidx_transform`, `graphicsLayerZoom` |

Notes:
- Line 1481/1483 helpers are `private` → they travel with `ZoomImage.kt`, no visibility change.
- `ChoiceRow` goes with `SettingsScreen.kt` because it is only used there (1248, 1251, 1253, 1256, 1267, 1276).
- Cross-file references that must keep compiling: `App`→`SettingsScreen/SessionsScreen/ChatScreen/ScreensScreen/PcFileBrowser`; `SessionsScreen`→`PcFileBrowser`, `ReorderProfiles`, `ChatTabs`, `HermesTopBar`; `ChatScreen`→`Bubble`, `FileBubble`, `RuntimeChips`. All same-package top-level, so fine.
- Import hygiene per file: `App.kt`/`ChatScreen.kt`/`SettingsScreen.kt` need `androidx.activity.compose.BackHandler`, `androidx.lifecycle.compose.LifecycleEventEffect`, Markwon imports only in `Markdown.kt`, etc. The wildcard imports (`androidx.compose.material3.*`, `layout.*`, `runtime.*`) can stay as-is to minimize diff.
- Recommended order of work: `Format.kt` + `Markdown.kt` + `ChatTabs.kt` first (leaf, no deps), then `MessageBubbles`, then `ChatScreen`, then `SessionsScreen`, then `SettingsScreen`/`ScreensScreen`, `AppShell` last.

---

## 4. Sequencing note for the desktop client

1. `Format.kt`, `MessageBubbles.kt`, `ChatTabs.kt`, `ModelPicker.kt` — zero-platform, prove the shared source set compiles.
2. Introduce `Strings` + `PlatformHooks` and replace `stringResource`/`BackHandler`/`Toast` call sites.
3. `SettingsStore`/`Notifier` interfaces so `ChatViewModel` stops being `AndroidViewModel`-only (or expose a `ChatUi` facade the shared UI consumes).
4. `MarkdownText` → Compose renderer; `FileBubble`/`ZoomImage`/`PcFiles` hooks last.
