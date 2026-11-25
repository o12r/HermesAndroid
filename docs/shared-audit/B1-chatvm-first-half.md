SPLIT LINE: 1125 — this audit covers ChatViewModel.kt lines 1–1125 (last line of `loadScreens()`, `}`); the second half starts at line 1127 (`/** JPEG of one PC screen, decoded. */`). File is 2210 lines, so 1125 is 23 lines after the midpoint (1105) and lands exactly on a function boundary.

# B1 — ChatViewModel.kt first half: extraction audit for a pure-Kotlin `:shared` module

Read-only audit. No builds run. Line numbers are from the current CRLF file.

## 1. What lives in lines 1–1125

| Region | Lines | Content |
|---|---|---|
| imports | 1–36 | 8 `android.*` imports, 4 `androidx.compose.runtime` (state), AndroidViewModel, coroutines, `org.json`, OkHttp, `java.util.UUID` |
| UI state models | 38–71 | `Screen`, `UiMsg`, `TaskInfo`, `Attachment`, `Approval`, `Project`, `PcScreen`, `ClarifyQ`, `PcStat`, `Clarify` |
| `Chat` (per-conversation state) | 73–118 | tabs, streaming flag, approval/clarify slots, queue, draft, ctx/token counters, model/effort |
| `ChatViewModel` class 1st half | 120–1125 | profiles/projects/sessions, tab+page management, LAN relay discovery, WS lifecycle (`gatewayFor`, `attach`, `resync`, reconnect/backoff), model+effort switching, inbox, updates, icon, power mode, vdisplay screens |

Key finding: **this half contains no protocol event handling at all.** `onEvent` is at 1740, `onServerRequest` 1981, `send` 1564, `sendMessage` 1670, `stop` 2046, `loadMessagesInto` 1541, `steer` 2017, `answerApproval` 1998, `answerClarify` 2004 — all in the second half. The first half only *wires* the callbacks (582–583, 625) and issues request/response RPCs.

### Protocol coverage in this half (request side only)
- **Connection/session**: `session.resume` 208, 611, 758, 1023, 1029; `session.create` 612–616; `session.info` 757; `session.stats` 203; `session.mutate` 1026–1030 (workspace move); `session.active_list` 821; `client.capabilities` (GatewayWs 56).
- **Catalog/meta**: `commands.catalog` 671, `model.options` 683, `profiles.list` 142, `projects.list` 957, `projects.tree` 958 (+1113).
- **Model control**: `switchOnPc` 734 issues `session.mutate`/`config` ops with confirm-token handling 721–733, `syncFromPc` 756.
- **HTTP/relay**: `/tabs` 791–808/813, `/elevate/pending|approve|deny` 1043–1066, `/vdisplay/screens` 1094.
- **Queue/steer**: only the producer — `queueMsg` 252, `drainQueue` 253 (calls `sendMessage`, defined at 1670). No steer here.
- **Approvals/clarify**: only state slots `Chat.approval` 86 / `Chat.clarify` 87 and pass-through accessors 168–169; prompts are replayed at 624–625 via `onEvent("<kind>.request")`.
- **Streaming**: only `Chat.streaming` flags (84, 166, 566–567, 589, 591, 627).
- **Sessions/projects**: `refreshSessions` 829, `loadOther` 933, `fetchProjects` 954, `newChatIn` 980, `moveToProject` 988, `moveManyToProject` 998, `deleteMany` 1009, `moveOne` 1020, snapshot cache 901–931.

## 2. Android dependencies in lines 1–1125 (exact)

**Imports** 3 `android.app.Application`, 4 `android.graphics.Bitmap`, 5 `android.graphics.ImageDecoder`, 6 `android.net.ConnectivityManager`, 7 `android.net.Network`, 8 `android.net.Uri`, 9 `android.provider.OpenableColumns`, 10 `android.util.Base64`; 16 `AndroidViewModel`, 17 `viewModelScope` (lifecycle). Unused-in-half: 4, 5, 9, 10 (their users are in the second half).

| Line(s) | Dependency |
|---|---|
| 124 | `class ChatViewModel(app: Application) : AndroidViewModel(app)` |
| 148 | `android.util.Log.w("HermesRpc", …)` |
| 244 | `Notify.clear(getApplication(), …)` (notification state) |
| 276–277 | `L10n.wrap(getApplication(), store.lang)` → `res.getString(R.string.*)`; `s()` then used at 223, 297, 301, 403, 407, 417, 424, 431, 443, 547, 552, 567, 589, 688, 741, 750, 868, 873–874, 879, 990–992, 993, 1004, 1081, 1116, 1187 |
| 282 | `UpdateManager(app, store)` (APK download/PackageInstaller) |
| 299–302, 304 | `ConnectivityManager.NetworkCallback`, `registerDefaultNetworkCallback` |
| 310 | `Formats.appCtx = app.applicationContext` |
| 313, 315–316 | `KeepAliveService.isSaver/start(getApplication())` (foreground service, wake lock) |
| 321–337 | `saveInboxNow(): android.net.Uri`, `Inbox.save(getApplication(), …)` |
| 339–349 | `saveInbox`, `showPendingFile` (Inbox cards) |
| 379–391 | `packageManager`, `ComponentName`, `PackageManager.setComponentEnabledSetting` (icon switch) |
| 396–433 | `checkUpdates`/`installUpdate`/`cancelUpdate`/`deleteUpdateFile` (APK) |
| 434–437 | `onCleared` → `unregisterNetworkCallback`, close sockets |
| 447–465 | `ConnectivityManager`, `NetworkCapabilities.TRANSPORT_VPN/WIFI`, `LinkProperties`, `NetworkInterface` (LAN discovery) |
| 528–533 | `KeepAliveService.start(getApplication())` |
| 547, 552 | `Notify.alert(getApplication<Application>(), …)` |
| 553 | `KeepAliveService.stop(getApplication())` |
| 614, 840, 940 | `android.util.Log.w` |
| 790 | `android.provider.Settings.Secure.ANDROID_ID` via `contentResolver` (device id) |
| 887, 889 | `android.os.SystemClock.elapsedRealtime()` (latency probe) |
| 1128 (boundary) | `android.graphics.Bitmap` + `BitmapFactory` in `screenShot` — assign to Android |
| 1108–1110 | `store.apiKey` + `ApiClient(...)` — fine on JVM |
| pervasive | `store` = `SecureStore(app)` (125): SharedPreferences + AndroidKeyStore; accessed at 128, 131, 136, 139, 147, 153, 162, 187, 269–270, 281, 297, 306, 313–315, 335, 348, 355, 363, 379, 394, 400–405, 470–472, 477–479, 493, 500–518, 535, 581, 617, 643, 704, 714, 742, 769, 782, 904, 919, 923, 948, 1081–1082, 1108 |

Not Android but UI-coupled: `mutableStateOf`/`mutableStateListOf`/`mutableStateMapOf` (12–14) and `LazyListState` (177). Compose Multiplatform provides the runtime in `commonMain`, so these can move as-is; `androidx.compose.foundation.lazy.LazyListState` at 177 has a CMP equivalent and is better left UI-side.

Also note `java.util.UUID` (36, 1024) and `System.currentTimeMillis` (53, 402, 405, 500–501) — JVM-safe, no change.

## 3. Proposed split

Target layout follows `docs/windows-client-plan.md` §4.1 (`shared/ChatEngine.kt`, `shared/model/`, `shared/platform/`).

### 3.1 `shared` — pure Kotlin/JVM (no android.*)

| New file | From lines | Notes |
|---|---|---|
| `shared/model/UiModels.kt` — `Screen`, `UiMsg`, `TaskInfo`, `Attachment`, `Approval`, `Project`, `PcScreen`, `ClarifyQ`, `PcStat`, `Clarify` | 38–71 | Already platform-free. `Attachment.payload` stays a String. |
| `shared/model/Chat.kt` — `class Chat` | 73–118 | Pure state holder; `mutableStateOf` → CMP runtime. Keeps `queue: SnapshotStateList<String>`. |
| `shared/engine/ChatEngine.kt` — `class ChatEngine(settings: SettingsStore, strings: Strings, scope: CoroutineScope, …)` | 120–135, 150–182, 261–273, 288–297, 439–444, 573–629, 644–666, 668–693, 695–718, 764–800, 828–861, 863–899, 901–952, 1037–1066, 1088–1125 | The state machine core. Constructor replaces `app: Application` + `viewModelScope`; `store` becomes `SettingsStore`. |
| `shared/engine/TabController.kt` (`switchTo`, `openPage`, `leavePage`, `closeTab`, `pageEntered`, `loadChatCtx`, `seen` w/o Notify) | 183–241, 199–212, 214–218, 220–241 | Can stay a section of `ChatEngine` if a single class is preferred (no partial classes in Kotlin); otherwise `TabController(engine)`. |
| `shared/engine/ConnectionSupervisor.kt` (`gatewayFor`, `gateway`, `attach`, `ensureLive`, `resync`, `scheduleReconnect`, `onForeground`, `onClosed` handler, `cancelRequest`) | 370–376, 523–571, 573–598, 600–629, 665–666 | `attach()` needs `onEvent` sink — injected as `EventSink` lambda, not a VM reference. |
| `shared/engine/RelayDiscovery.kt` (`pickRelay`, `learnLan`, `scanLan`, `sameSubnet`, `wifiIp`, `localNet`, `hotspotNet`) | 446–521 | Behind `interface NetworkTopology { fun localIpv4(): String?; fun defaultGateway(): String?; fun isOnWifi(): Boolean }`; `scanLan` (48-way /24 sweep) works on JVM unchanged. |
| `shared/engine/ModelController.kt` (`loadCatalog`, `adoptServerModel`, `applyUsage`, `chooseModel`, `chooseEffort`, `confirmModel`, `answerCompress`, `switchOnPc`, `syncFromPc`, `modelLabel`, `localCommands`, `helpText`, `commandList`) | 635–644, 646–663, 668–693, 695–762 | Pure RPC + `SettingsStore`; `s(R.string.*)` → `strings.get(StrKey.*)`. |
| `shared/engine/SessionCatalog.kt` (`scanProfiles`, `refreshSessions`, `refreshChat` (minus Bitmap), `loadOther`, `fetchProjects`, `saveSnap`, `loadSnap`, `cachedFor`, `enterProfile`, `toggleProfileExpanded`, `newChatIn`, `moveToProject`, `moveManyToProject`, `deleteMany`, `moveOne`, `loadLive`, `reportTabs`, `testConnections`/`pathLabel`, `pollElevate`/`decideElevate`) | 134–149, 151–159, 764–785, 787–808, 810–827, 829–849, 851–861, 863–894, 896–899, 901–952, 954–1035, 1037–1066 | Depends on `ApiClient`, `GatewayWs`, `SettingsStore`; `deviceId` (790) moves to `DeviceInfo` interface. |
| `shared/engine/ScreenAdmin.kt` (`openScreens`, `loadScreens`, `vdisplay`, `closeAgentScreen`) | 1068–1125, 1136–1179 | `screenShot` (1128–1134) stays Android (Bitmap); expose `requestScreenShot(name): ByteArray` in shared and decode in the client. |

### 3.2 `shared/platform` — expect/interface seams (declared in shared, implemented per client)

| Interface | Replaces (lines) |
|---|---|
| `SettingsStore` | `SecureStore` (125 and all `store.*` uses) |
| `Strings` (`fun get(key: StrKey, vararg args): String`) | `L10n.wrap` + `R.string` (276–277, all `s(...)`) |
| `Notifier` (`alert`, `clear`, `visible`) | `Notify` (244, 547, 552) |
| `InboxSink` (`save(name): Any`, `list(known)`) | `Inbox` (321–368) |
| `NetworkTopology` | `ConnectivityManager`/`NetworkCapabilities` (299–304, 447–465) |
| `MonotonicClock` | `SystemClock.elapsedRealtime` (887–889) |
| `DeviceInfo` | `Settings.Secure.ANDROID_ID` (790) |
| `Logger` | `android.util.Log.w` (148, 614, 840, 940) |
| `Updater` (optional, desktop has its own) | `UpdateManager` (282, 396–433) |

### 3.3 Android-only glue that stays in `app/` (`ChatViewModel` becomes a thin host)

| Lines | Keep as Android |
|---|---|
| 124–125 | `AndroidViewModel(app)` + `SecureStore(app)` construction, `viewModelScope` → engine scope |
| 244, 275–287 + 396–433 | `s()/res`, theme/fontScale/update UI state, `UpdateManager`, hotfix banner, `installUpdate`/`cancelUpdate`/`deleteUpdateFile` |
| 299–317 + 434–437 | `ConnectivityManager.NetworkCallback` registration/unregister, `KeepAliveService`, `GatewayWs.pingSec`, `Formats.appCtx` |
| 319–368 | `fileProgress`, `saveInboxNow(): Uri`, `saveInbox`, `showPendingFile`, `inboxWatch` (needs `Inbox`/MediaStore) |
| 370–394 | `cancelRequest` (delegates to engine), `appIcon`/`chooseIcon` (packageManager), `chooseFontScale` |
| 434–437, 1068–1087 partial | network callback teardown; `screenShot` (1128) Bitmap decode |

### 3.4 Blocking structural issue

There are **no partial classes in Kotlin** and this half calls second-half members: `newChat()` (984, 1014 → def 1512), `apiFor()` (936 → 1521), `loadMessagesInto()` (192, 566, 857 → 1541), `onEvent()` (582, 583, 625 → 1740), `onServerRequest()` (583 → 1981), `stop()` (374 → 2046), `kickProcPoll()` (195, 529 → 1889), `loadSessionCtx()` (848 → 1245), `fsJson()` (first used 1374 → def 1313), `pcHome` (989, 999 → 1373), `sendMessage()` (253 → 1670).

Consequences, in order of risk:
1. `ChatEngine` must be extracted as **one whole class** (lines 124–2176) first; only then can the sections above be split into collaborators. Splitting this half alone yields code that cannot compile.
2. The safest sequence: move models (38–118) and `Chat` (73–118) first — zero risk; then `SettingsStore`/`Strings`/`Logger` seams; then the whole class into `shared` with `viewModelScope` → injected scope; then peel `ScreenAdmin`, `RelayDiscovery`, `SessionCatalog`, `ModelController` out as internal delegates, each compiling independently.
3. Android behaviour must stay identical: keep the `/24` sweep (48 permits, 900 ms), 1500 ms→60 s backoff with 3-try background give-up (534–558), the `session.create`-without-cwd retry (612–614), and the "cache snapshot on every refresh" path (913–920).

## 4. Summary counts (lines 1–1125)

- 10 state/data classes: 38, 39, 57, 60, 61, 62, 65, 68, 70, 71; plus `Chat` 74 and nested `Probe` 864.
- ~95 Android touch points, concentrated in: Application (124, 282, 435), ConnectivityManager (299–304, 450–457), Uri (321, 326, 330, 334), Notify (244, 547, 552), KeepAliveService (313–316, 531, 553), packageManager (380–391), SharedPreferences/SecureStore (125 + ~40 sites), SystemClock (887, 889), Log (148, 614, 840, 940), resources/R.string (~27 sites).
- No Toast, no `Uri.parse` beyond Inbox, no `SharedPreferences` direct use (only through `SecureStore`), no Notification builder (only `Notify`).
