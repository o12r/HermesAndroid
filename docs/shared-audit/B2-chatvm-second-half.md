# B2 — ChatViewModel.kt second half: extraction audit

**START LINE: 1128** (`suspend fun screenShot(name: String): android.graphics.Bitmap`, first function boundary after the midpoint)
File: `app/src/main/java/dev/oscar/hermes/ChatViewModel.kt` · 2210 lines total · midpoint = 2210/2 = **1105** (falls mid-body of `loadScreens()`, 1090–1127) → audited range **1128–2210**.
Class closes at 2168; 2170–2210 are top-level declarations.

Read-only audit. No builds, no edits outside this file.

---

## 1. Inventory of the audited range

| Lines | Member(s) | Kind | State touched |
|---|---|---|---|
| 1128–1134 | `screenShot` | relay HTTP + Bitmap | — |
| 1136–1146 | `closeAgentScreen` | relay HTTP | `screensBusy`, `vdAgent` |
| 1148–1177 | `vdisplay` | relay HTTP (`/vdisplay/<op>`) | `vdMode/Agent/ScreenOff/ScreenOffAvailable/Busy/Agents` |
| 1179–1199 | `loadCtxSettings`, `ctxSettings/ctxBusy/ctxError` (1180–1182) | relay HTTP `/config/context` | ctx settings |
| 1201–1221 | `loadModelSettings`, `modelSettings/modelBusy/modelError` (1202–1204) | relay HTTP `/config/models` | model defaults |
| 1223–1283 | `sessionCtx`, `lastCtxProf`, `primeCtx`, `saveCtx`, `ctxStamp`, `loadSessionCtx` | RPC fan-out (`session.stats`, `session.resume omit_messages`, `session.active_list`, `session.close`) + disk cache | per-session ctx |
| 1285–1299 | `pcStats`, `fetchPcStats`, `addPcStat` | relay `/pc/stats` | 300-sample ring |
| 1301–1310 | `fsCall` | relay `/fs/<op>` | — |
| 1312–1321 | `fsJson` | relay `/fs/<op>` arbitrary body | — |
| 1323–1324 | `filesPath` | state | PC files tab |
| 1326–1350 | `fsDownload` | relay `/fs/download` → **MediaStore Downloads** | — |
| 1352–1370 | `fsUpload` | **ContentResolver** → relay `/fs/upload` | — |
| 1372–1374 | `pcHome`, `loadPcHome` | derived | home cwd |
| 1376–1382 | `moveTargets` | pure projection of `projects` | — |
| 1384–1393 | `changeProjectFolder` | RPC `projects.add_folder` / `remove_folder` | `status` |
| 1395–1407 | `createProject` | RPC `projects.create` + `fsCall(stat/mkdir)` | `missingFolder` |
| 1408–1410 | `archiveProject` | RPC `projects.archive` | — |
| 1411–1425 | `deleteProject` | RPC `projects.delete` + `fsCall(rmdir)` + `api().delete` | `chats/tabs` |
| 1426–1429 | `setActiveProject` | RPC `projects.set_active` | `activeProject` |
| 1431–1456 | `openFromNotification`, `open` | RPC attach | `cur`, `activePage`, tabs |
| 1458–1471 | `refreshIds` | REST `apiFor().messages` | message ids |
| 1473–1483 | `mutate` | **protocol core**: `session.mutate` + `revision_conflict`/`stale_generation` retry via `session.resume` | `revision`, `generation` |
| 1485–1497 | `editMessage` | mutate `rewind` | messages |
| 1499–1511 | `branchFrom` | mutate `branch` | opens new session |
| 1512–1519 | `newChat` | tab/chat lifecycle | `newChatCwd/None`, attachments |
| 1521–1523 | `apiFor`, `loadMessages` | REST | — |
| 1525–1539 | `sendMedia` | relay `POST /outbox/send` + idempotency marks | `store.mediaDone/markMedia/unmarkMedia` |
| 1541–1556 | `loadMessagesInto` | REST transcript rebuild, `Internal.clean`, reply stats, session files | `offline/error` |
| 1558–1561 | `rename`, `delete` | REST | chats/tabs |
| 1563–1606 | `send`, `slash` | **local command router** (19 branches) | `lastSent`, `modelMenuOpen` |
| 1608–1636 | `compressChat` | mutate `compress` + continuation session | `status` |
| 1638–1649 | `branchWhole` | mutate `branch` | — |
| 1651–1668 | `serverSlash` | RPC `slash.exec` | messages |
| 1670–1695 | `sendMessage` | optimistic bubble + reattach-once resend | `lastSent` |
| 1697–1707 | `agentWorkedAfter`, `resendFailed`, `deleteFailed`, `editFailed` | pure/failed-bubble logic | messages |
| 1709–1726 | `submit` | RPC `image.attach_bytes`, `prompt.submit` (submission_id) | `createdHere` |
| 1728–1737 | `dropEmpty`, `updateLast` | pure stream helpers | messages |
| 1739–1832 | `onEvent` | **event router**: session.usage / session.info / session.control.update / session.updated / message.start / message.delta / thinking.delta / reasoning.delta / tool.start / tool.complete / message.complete / approval.request / clarify.request / approval.settled / clarify.settled / subagent.* / background.complete / session.title / error | whole `Chat` |
| 1834–1882 | `taskChats`, `roAppend`, `taskEvent` | subagent + background cards, read-only transcripts | `taskChats` |
| 1884–1949 | `procJobs`, `kickProcPoll`, `pollProcs`, `fetchBgProgress`, `applyProc` | RPC `process.list` + relay `/bgtasks`, 3 s poll | `TaskInfo(percent,last)` |
| 1951–1963 | `legacyFileTime` | **android.net.Uri + ContentResolver query** | — |
| 1965–1976 | `placeByTime`, `placeCard` | pure ordering | messages |
| 1978–1979 | `openTask` | tab open | — |
| 1981–1996 | `onServerRequest` | gateway server-request `approval`/`clarify`, else "not supported on the phone" | `approval/clarify` |
| 1998–2003 | `answerApproval` | `approval.respond` (generation-fenced) or legacy `respond` | — |
| 2004–2014 | `answerClarify` | `clarify.respond` or legacy `respond` | — |
| 2016–2044 | `steer` | `session.steer` + generation refresh + fallback to queue | `queue`, `status` |
| 2046–2062 | `stop` | `session.interrupt` + `stale_generation` retry | — |
| 2064–2074 | `uploadStatus`, `uploadsActive`, `addAttachments` | attachment gate | `attachments` |
| 2076–2095 | `readAttachment` | **ContentResolver + ImageDecoder + Bitmap + Base64** | — |
| 2097 | `setUploadStatus` | string format | — |
| 2099–2155 | `uploadToPc` | **ContentResolver/ParcelFileDescriptor/cacheDir** + `/upload/init`, `PUT /upload/<id>?offset=`, `/upload/<id>/done` | upload progress |
| 2157–2167 | `saveSettings` | close all `GatewayWs`, reset, `pickRelay`, `api().health/sessions` | — |
| 2170–2175 | `SLASH_COMMANDS` | `R.string` id table | — |
| 2177–2182 | `JSONObject.str`, `JSONObject.optDoubleOrNull` | pure JSON helpers | — |
| 2185–2210 | `object Internal` (`HIDE`, `OOB`, `MEDIA`, `MEDIA_INLINE`, `inlinePath`, `mediaPaths`, `stripMedia`, `clean`) | **pure text processing, zero android** | — |

Hotfix / update surface in this half is small: `hotfix.serverSlash` (1604, gates `serverSlash`), `hotfix.attachments` (2070, gates `addAttachments`). `forceUpdate`, `hotfix.minVersionCode`, `UpdateManager`, install/cancel/delete-update all live in 280–290 / 397–433 (first half).

---

## 2. Protocol surface in this half

**RPC (GatewayWs.call)** — `session.stats` 1268, `session.resume` 1270/1479/2056, `session.active_list` 1254, `session.close` 1275, `session.mutate` 1477/1481, `session.steer` 2027/2031, `session.info` 2029, `session.interrupt` 2052/2057, `slash.exec` 1655, `prompt.submit` 1723, `image.attach_bytes` 1716, `process.list` 1902, `approval.respond` 2001, `clarify.respond` 2007, `projects.add_folder` 1388, `projects.remove_folder` 1390, `projects.create` 1404, `projects.archive` 1409, `projects.delete` 1423, `projects.set_active` 1427. Legacy `g.respond` 1994/2000/2012.

**Relay HTTP** — `/vdisplay/shot` 1129, `/vdisplay/off` 1141, `/vdisplay/<op>` 1153, `/config/context` 1188, `/config/models` 1210, `/pc/stats` 1290, `/fs/<op>` 1303/1316, `/fs/download` 1331, `/fs/upload` 1362, `/outbox/send` 1534, `/bgtasks` 1915, `/upload/init` 2124, `PUT /upload/<id>` 2139, `/upload/<id>/done` 2149.

**Server-request methods** — `approval` 1983, `clarify` 1987 (phone answers "not supported" for anything else, 1994).

Notable invariants to preserve verbatim: revision/generation fencing in `mutate` (1473–1483), steer-fallback-to-queue (2036–2042), reattach-once resend (1681–1685), `submission_id` per submit (1722), process-poll loop condition `Notify.visible && c === cur` (1892), 3 s poll + 8 s RPC timeout (1895, 1902).

---

## 3. Android dependency ledger (exact lines)

| Line(s) | Dependency | Class of coupling |
|---|---|---|
| 1128, 1132 | `android.graphics.Bitmap`, `BitmapFactory` | return type + decode |
| 1271 | `android.util.Log.w("HermesRpc", …)` | logging |
| 1329 | `getApplication<Application>()` | context for cacheDir/contentResolver |
| 1332 | `ctx.cacheDir` | temp file location |
| 1338 | `android.webkit.MimeTypeMap` | MIME sniff |
| 1339–1341 | `android.content.ContentValues`, `MediaStore.Downloads.*` | Downloads insert |
| 1343–1347 | `contentResolver.insert/openOutputStream/update/delete`, `MediaStore.Downloads.EXTERNAL_CONTENT_URI` | MediaStore write |
| 1353, 1355, 1357, 1360 | `android.net.Uri` param, `contentResolver`, `android.provider.OpenableColumns.DISPLAY_NAME`, `openInputStream` | read phone file |
| 1632 | `Notify.alert(getApplication<Application>(), …)` | notification (compress done) |
| 1692 | `Notify.alert(getApplication<Application>(), …)` | notification (send failed) |
| 1743–1744 | `getApplication<Application>()`, `Notify.visible` | notification gating (`away`) |
| 1755, 1759, 1767, 1792 | `android.os.SystemClock.elapsedRealtime()` | turn timing / TPS math |
| 1756, 1801, 1813, 1819, 1822, 1823, 1830 | `Notify.clear` / `Notify.alert` | notifications (reply, approval, clarify, error) |
| 1892 | `Notify.visible` | poll loop gate |
| 1956–1957 | `android.net.Uri.parse`, `getApplication<Application>().contentResolver.query` | legacy file mtime |
| 1999, 2005 | `Notify.clear(getApplication(), …)` | notifications |
| 2078, 2080 | `getApplication<Application>().contentResolver`, `OpenableColumns.DISPLAY_NAME/SIZE` | attachment metadata |
| 2085–2091 | `ImageDecoder`, `Bitmap.CompressFormat`, `android.util.Base64` | image downscale→JPEG→base64 |
| 2101, 2109, 2115–2116 | `contentResolver.openFileDescriptor/openInputStream`, `app.cacheDir`, `android.os.ParcelFileDescriptor` | seekable chunk reads |

**Indirect (pervasive, not `android.*` imports):**
- `viewModelScope` × 30 (1128–2155) → needs a `CoroutineScope` + lifecycle owner.
- `s(R.string.…)` × 49 in range, plus `res`/`L10n.wrap(getApplication(), …)` (276) → `Strings` port.
- `store.*` (SecureStore → `SharedPreferences`, DPAPI on desktop): 11×`apiKey`, 10×`relayBase`, `profile`, `relayUrl`, `lanUrl`, `activeRelay`, `hasKey`, `ctxCache/setCtxCache`, `sessionFiles`, `replyStat/setReplyStat`, `mediaDone/markMedia/unmarkMedia` → `SettingsStore` port.
- `Notify` × 13, `hotfix` × 2, `relayHttp` × 6, `gateway()/gatewayFor` × 23.
- No direct `Toast`, no `Context` field, no direct `SharedPreferences`, no direct `NotificationCompat` in this half (all via `Notify`/`SecureStore`).

---

## 4. Proposed split

### 4a. `shared/commonMain` — pure Kotlin/JVM (`org.json`, OkHttp, coroutines)

| Target file / type | Source lines | Content |
|---|---|---|
| `model/ChatModels.kt`: `UiMsg`, `Step`*, `TaskInfo`, `Attachment`, `Approval`, `ClarifyQ`, `Clarify`, `PcStat`, `Probe` | 39–71*, 864 | copy verbatim (owned by B1 range; needed here) |
| `model/Chat.kt`: `Chat` | 74–122* | copy; `mutableStateListOf` → `MutableList` behind `StateFlow`/snapshot, see 4c |
| `engine/ChatEngine.kt` (state fields) | 1179–1182, 1202–1204, 1224–1226, 1286, 1324, 1373, 2066–2067, 1835, 1887 | ctx/model settings JSON blobs, `pcStats`, `filesPath`, `pcHome`, upload status, `taskChats`, `procJobs` |
| `protocol/SessionProtocol.kt`: `mutate`, `submit`, `steer`, `stop`, `refreshIds`, `openServerRequest`, `answerApproval`, `answerClarify` | 1458–1483, 1709–1726, 2016–2062, 1981–2014 | fenced-mutation + prompt-submit + server-request handling; `g: GatewayWs` injected |
| `engine/EventRouter.kt`: `onEvent`, `taskEvent`, `roAppend`, `applyProc`, `placeByTime`, `placeCard`, `dropEmpty`, `updateLast` | 1739–1832, 1834–1882, 1923–1949, 1965–1976, 1728–1737 | event → state; notifications via `Notifier` port |
| `engine/ProcWatcher.kt`: `kickProcPoll`, `pollProcs`, `fetchBgProgress` | 1884–1921 | poll loop; `Notifier.isVisible` → `UiPresence` port |
| `engine/SendPipeline.kt`: `send`, `slash`, `sendMessage`, `resendFailed`, `deleteFailed`, `editFailed`, `agentWorkedAfter`, `compressChat`, `branchWhole`, `serverSlash`, `editMessage`, `branchFrom` | 1563–1668, 1670–1707, 1485–1511 | command router + send paths; `Strings` port |
| `engine/SessionLoader.kt`: `loadMessagesInto`, `loadMessages`, `sendMedia`, `rename`, `delete`, `open`, `newChat` | 1521–1561, 1431–1456, 1512–1519 | transcript rebuild; media via `MediaForwarder` port |
| `engine/ProjectsFlow.kt`: `moveTargets`, `changeProjectFolder`, `createProject`, `archiveProject`, `deleteProject`, `setActiveProject`, `loadPcHome` | 1376–1429, 1372–1374 | project CRUD (pure RPC) |
| `engine/CtxCache.kt`: `primeCtx`, `saveCtx`, `loadSessionCtx` | 1223–1283 | `android.util.Log` 1271 → `java.util.logging`; cache via `SettingsStore` |
| `net/RelayClient.kt`: `vdisplay`, `loadCtxSettings`, `loadModelSettings`, `fetchPcStats`, `fsCall`, `fsJson`, `closeAgentScreen`, `saveSettings` | 1148–1177, 1179–1199, 1201–1221, 1285–1299, 1301–1321, 1136–1146, 2157–2167 | direct OkHttp relay calls; extract a shared `RelayClient` with `getJson/postJson/upload` |
| `net/FileTransfer.kt`: chunked uploader core (`/upload/init`, PUT, `/done`) | 2099–2155 minus resolver bits | takes `suspend () -> SeekableSource` instead of `Uri` |
| `text/InternalText.kt`: `object Internal` + `JSONObject.str/optDoubleOrNull` | 2187–2210, 2177–2182 | **zero android deps today — straight move, highest-value/lowest-risk** |
| `strings/SlashCommands.kt` | 2170–2175 | `R.string.*` → generated `StringKey` ids |

`*` = declared in B1's range; listed because this half uses them.

### 4b. Ports (`shared/platform/`, `expect`/interface)

```kotlin
interface AppDirs { val cacheDir: File; val downloadsWriter: DownloadSink }
interface SecretSettings { var apiKey: String?; fun relayBase(): String; var profile: String; /* …17 more store.* keys */ }
interface Notifier { fun alert(title: String, body: String, profile: String?, session: String?, kind: String); fun clear(session: String?, kind: String); val isVisible: Boolean }
interface UiClock { fun elapsedMillis(): Long }            // SystemClock.elapsedRealtime
interface Strings { fun get(key: StringKey, vararg args: Any): String }
interface UriSource { fun displayName(): String?; fun size(): Long?; fun mime(): String?; fun openSeekable(): SeekableChannel?; fun openStream(): InputStream }
interface ImageCodec { fun toJpegBase64(src: UriSource, maxDim: Int, quality: Int): String? }
interface MediaForwarder { suspend fun forward(pcPath: String, session: String): Boolean }  // /outbox/send
interface UiPresence { val visible: Boolean; val activeChatId: String? }
```

### 4c. Android-only glue (stays in `app/`)

| Glue class | Wraps source lines | Notes |
|---|---|---|
| `AndroidAppDirs` | 1332, 2106/2114 | `cacheDir`, `File.createTempFile` |
| `AndroidUriSource` | 1353–1357, 2077–2082, 2109–2117 | ContentResolver + `OpenableColumns` + `ParcelFileDescriptor` |
| `AndroidImageCodec` | 2083–2092 | `ImageDecoder` + `Bitmap` + `android.util.Base64` |
| `MediaStoreDownloadSink` | 1338–1349 | MimeTypeMap + `MediaStore.Downloads` + `IS_PENDING` dance |
| `AndroidNotifier` | 1632, 1692, 1743–1744, 1756, 1801, 1813, 1819, 1822–1823, 1830, 1892, 1999, 2005 | existing `Notify` |
| `ElapsedClock` | 1755, 1759, 1767, 1792 | `SystemClock.elapsedRealtime()` |
| `AndroidStrings` | 49 `s(R.string…)` sites + 1745 | `L10n.wrap(getApplication())` |
| `SecureStoreSettings` | all `store.*` | implements `SecretSettings` over `SecureStore` |
| `ChatViewModel` (thin) | remainder | `AndroidViewModel` + `viewModelScope`, delegates to `ChatEngine`; keeps `screen()` (127), `tabsState` (177), compose `mutableStateOf` adapters |
| `LegacyFileTimeResolver` | 1954–1963 | `Uri.parse` + resolver query |
| `VdisplayBitmap` | 1128–1134 | decode bytes → `Bitmap`; engine returns `ByteArray` |
| `FsDownloadSink` / `fsUpload` name lookup | 1326–1350, 1353–1370 | split: engine owns URL/body, glue owns streams |

### 4d. Suggested order (phone must not regress)

1. `text/InternalText.kt` (2187–2210) + JSON ext (2177–2182) — pure move, no behavior change.
2. `RelayClient` extraction for 1148–1321, 1136–1146 — keep `relayHttp` semantics (90 s read timeout at 1086).
3. Ports `Strings`/`Notifier`/`UiClock` — mechanical; verify `away` logic (1744) unchanged.
4. `EventRouter` (1739–1882) — the real state machine; golden-transcript diff against phone.
5. `SendPipeline`/`SessionProtocol` (1458–1511, 1563–1726, 1981–2062).
6. Attachments / `uploadToPc` (2064–2155) last — highest Android surface, chunking + retry must stay byte-identical.

### 4e. Risks specific to this half

- `Chat` fields are `mutableStateOf` (77–117): shared `ChatEngine` needs a snapshot/`StateFlow` facade or Compose-runtime stays a `shared` dependency (already KMP-safe, but `androidx.compose.runtime` in `:shared` pulls skiko-free runtime only).
- 1271 `android.util.Log` is inside a `.onFailure` on a hot path — swap to `java.util.logging` (already flagged in plan appendix A for `GatewayWs`).
- `store.mediaDone/markMedia/unmarkMedia` (1531–1536) are the idempotency guard against duplicate `/outbox/send`; port must keep mark-before-send then unmark-on-failure ordering.
- `Notify.visible` doubles as the process-poll lifecycle gate (1892): desktop must supply an equivalent foreground/visibility signal or background polling never starts.
- `answerApproval` legacy path (2000) uses `gw?.respond` (null-safe, silently drops) while the modern path throws into `fail` — do not unify behavior.
