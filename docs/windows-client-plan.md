# Hermes Windows 桌面客户端 —— 移植方案（Plan only）

> 目标：把 `HermesAndroid`（Kotlin + Jetpack Compose，`app/src/main/java/dev/oscar/hermes`）移植为 Windows 桌面客户端，
> 连接**同一个 PC phone relay**（`pc/relay/hermes-phone-relay.py`，HTTP `:8643`，Bearer `API_SERVER_KEY`），
> 功能对齐手机端（会话 / 标签页 / 任务 / 文件 / 设置 / 主题 / 中英文）。
>
> 本文只做方案，不改代码。版本基线：`version.properties` = 0.3.31 (code 66)。

---

## 0. 结论（TL;DR）

| 项 | 结论 |
|---|---|
| 技术路线 | **Compose Multiplatform Desktop（Kotlin/JVM + Skiko）**，在现有仓库内加 `:shared` + `:desktop` 两个 Gradle 模块 |
| 复用率 | 网络层 / 协议层 ≈ **95%** 直接复用；业务状态机（`ChatViewModel.kt`）≈ **75%** 改造复用；UI ≈ **60–70%** 复用（Compose 同一套 API）；资源/平台胶水层重写 |
| 安装包 | jpackage `app-image` + jlink 裁剪 JRE ≈ **60–80 MB 解压 / 35–50 MB 安装包**；不含 WebView2、不含 Chromium |
| 自动更新 | 复用 relay `/app/{app}` 渠道 + `latest.json`（需给 relay 加 1 处白名单），Inno Setup 静默安装；GitHub Releases 兜底 |
| 总工作量 | **约 9–11 周**（单人、含联调），MVP **4–5 周** |
| 最大风险 | **中文输入法（IME）在 Skia 文本框里的表现**；第 0 阶段必须先验证，失败则启用 Swing `JTextField` interop 或退到 Tauri |

**一句话**：这是同一门语言、同一套 Compose、同一个协议端点的移植，不是重写；Kotlin 的沉没成本几乎可以全额收回。

---

## 1. 现状盘点

### 1.1 客户端代码结构（Android）

| 文件 | 行数 | 职责 | 平台耦合 |
|---|---:|---|---|
| `ChatViewModel.kt` | 2210 | 全部业务状态机：会话/项目/标签页/流式/审批/任务/附件/设置/更新 | `AndroidViewModel`、`Uri`、`Context`、`Toast`、`SystemClock`、`ConnectivityManager` |
| `MainActivity.kt` | 1484 | 全部 Compose UI：会话页、聊天页、设置页、屏幕页、各对话框、主题 | `Activity`、`Markwon`+`AndroidView`、`ActivityResultContracts`、`Insets` |
| `ApiClient.kt` | 236 | relay HTTP：`/api/sessions*`、`mutation-snapshot`、`/lan`；`Msg`/`Step` 折叠逻辑 | OkHttp（JVM 可用）、`org.json`（JVM 有 Maven 包） |
| `GatewayWs.kt` | 103 | JSON-RPC over WebSocket（`/ws?profile=`），并发请求表、超时、错误映射 | OkHttp WebSocket（JVM 可用） |
| `PcFiles.kt` | 246 | PC 文件浏览器（盘符/Quick access/pin/上传/下载/改名/删除） | Compose + `Uri` + `Toast` |
| `SecureStore.kt` | 164 | SharedPreferences 全量设置 + AndroidKeyStore AES-GCM 存 key | **必须替换** |
| `UpdateManager.kt` | 184 | `latest.json` PC/GitHub 双源、APK 下载、`PackageInstaller`、`hotfix.json` | **必须替换**安装部分 |
| `Inbox.kt` | 86 | `/outbox` 长轮询、下载到 Downloads、`DELETE` 清 PC | MediaStore → `Files` |
| `KeepAlive.kt` | 135 | 前台服务 + WakeLock + `Notify`（通知渠道/点击跳转） | **删除**，换托盘常驻 |
| `ScanActivity.kt` | 72 | CameraX + zxing 扫配对二维码 | 换成粘贴/图片/文件 |
| `SettingsGroups.kt` | 121 | 上下文参数、默认模型（main/delegation/aux）编辑 | 基本可复用 |
| `ModelPicker.kt` / `FileBubble.kt` / `Formats.kt` / `PcStats.kt` / `L10n.kt` / `Downloader.kt` | 82/95/123/121/30/107 | 模型选择器、文件气泡、代码块切分、PC 采样、语言包装、断点下载 | 局部 |
| `res/values/strings.xml` + `values-zh-rCN` | 306×2 | 全部 UI 文案 | 换资源方案 |

### 1.2 协议面（Windows 端必须逐条对齐）

**HTTP（relay，Bearer）**
- `GET /health`、`GET /lan`、`GET /pc/stats`
- `* /api/{tail}` → 网关原生 HTTP：`/api/sessions?limit=100&order=recent`、`/api/sessions/{id}/messages`、`/api/sessions/{id}/mutation-snapshot`、`PATCH /api/sessions/{id}`、`DELETE /api/sessions/{id}`
- `/fs/list|stat|mkdir|rename|delete|pin|unpin`、`/fs/download?path=`、`/fs/upload?path=&name=[&overwrite=1]`
- `/upload/init` → `PUT /upload/{id}?offset=N`（4 并发 × 4 MB）→ `POST /upload/{id}/done`
- `/outbox`（`wait`/`known` 长轮询）、`POST /outbox/send`、`GET|DELETE /outbox/{name}`
- `/vdisplay/status|on|off|screen-off|screen-on`、`POST /vdisplay/shot`
- `/elevate/pending`、`POST /elevate/{op}/{id}`
- `/app/{app}/{latest.json|hotfix.json|apk}`（更新通道）
- `/bgtasks`（后台进程进度解析）
- `/tabs`（多端标签同步，手机端目前只上报，桌面端可选接）

**WebSocket `/ws?profile=<p>`（JSON-RPC 2.0, `hermes-gateway-v1`）**
- 客户端 → 服务端：`client.capabilities`、`session.resume|create|info|interrupt|steer|mutate|close|stats|active_list`、`prompt.submit`、`model.options`、`commands.catalog`、`slash.exec`、`approval.respond`、`clarify.respond`、`image.attach_bytes`、`process.list`、`profiles.list`、`projects.list|tree|create|add_folder|remove_folder|archive|delete|set_active`
- 事件：`message.start|delta|complete`、`thinking.delta`/`reasoning.delta`、`tool.start|complete`、`approval.request|settled`、`clarify.request|settled`、`subagent.*`、`background.complete`、`session.usage|info|title|updated|control.update`、`error`
- 服务端 → 客户端请求：`approval`、`clarify`（`GatewayWs.respond`）

> 关键：桌面端**不新增服务端协议**。relay 只加“桌面安装包文件分发”的白名单（见 §6.3），其余零改动。

---

## 2. 功能清单与对齐表

工作量：**S** ≤2 人日，**M** 3–7 人日，**L** 8–15 人日，**XL** >15。

| # | 功能 | Android 实现（文件） | Windows 方案 | 工作量 | 风险 |
|---|---|---|---|---:|---|
| 1 | 流式聊天（发送/停止/steer/排队） | `ChatViewModel.send/sendMessage/stop/steer`；`GatewayWs` | 原样复用（OkHttp WS on JVM） | S | 低 |
| 2 | Markdown + 代码块 + 表格/删除线 | `MainActivity.Markdown/CodeBlock`、`Formats.split`、Markwon | 换 `multiplatform-markdown-renderer`（Apache-2.0），保留 `Formats.split` 做代码块分流 | M | 中（渲染差异） |
| 3 | 思考/工具步骤折叠、输出展开 | `StepsView`、`ApiClient.Steps` | Compose 直接复用 | S | 低 |
| 4 | 标签页（拖拽排序、未读点、⏳/🖥/❓ 标记） | `ChatTabs`、`Chat.page`、`Chat.unread` | 复用；拖拽换鼠标手势；补 `Ctrl+Tab`/`Ctrl+W` | S | 低 |
| 5 | 会话列表（按 profile/project 分组、折叠、批量选择） | `SessionsScreen`、`buildGroups` | 复用，行高按鼠标微调 | M | 低 |
| 6 | Profile 扫描 / 排序 / 切换 / 断开 | `scanProfiles`、`ReorderProfiles`、`ProfileMenu` | 复用（`profiles.list`） | S | 低 |
| 7 | 项目 CRUD、目录绑定、归档、删除（可带目录/会话） | `createProject/changeProjectFolder/archiveProject/deleteProject`、`projects.*` | 复用；目录选择用原生 `FileDialog` + relay `/fs/list` | M | 中（路径/权限） |
| 8 | 会话移动 / 批量删除 | `moveToProject`、`moveManyToProject`、`deleteMany` | 复用 | S | 低 |
| 9 | 新建会话（含“在项目中新建”） | `newChat`、`newChatIn` | 复用 | S | 低 |
| 10 | 重命名 / 删除（fenced mutation） | `ApiClient.identity/rename/delete`、`rename()` | 复用 | S | 低 |
| 11 | 斜杠命令（本地 8 个 + 服务端 `slash.exec` + 补全） | `slash()`、`serverSlash`、`SlashSuggestions` | 复用 | S | 低 |
| 12 | 审批对话框（once/session/always/deny） | `approval.request`、`answerApproval`、`App()` 弹窗 | 复用；窗口置前 + 任务栏闪烁 | M | 中（通知可达性） |
| 13 | Clarify（单选/多选/自由文本） | `clarify.request`、`answerClarify` | 复用 | M | 中 |
| 14 | 模型 chip + `model.options` + `session.mutate` | `RuntimeChips`、`chooseModel/chooseEffort` | 复用 | S | 低 |
| 15 | 上下文用量 chip / 上下文参数页 | `RuntimeChips`、`ContextSettingsGroup` | 复用 | S | 低 |
| 16 | 默认模型页（main/delegation/aux） | `ModelSettingsGroup`、`ModelPickerField` | 复用 | S | 低 |
| 17 | 任务 chip / 子代理卡片 / 只读转录标签 | `TaskCard`、`taskEvent`、`openTask` | 复用 | M | 中 |
| 18 | 后台进程进度（3 s 轮询 `/bgtasks`） | `kickProcPoll`、`pollProcs`、`applyProc` | 复用（窗口可见时轮询） | M | 中 |
| 19 | 附件：图片 ≤1600 px JPEG、文本内联、大文件分片上传 | `addAttachments`、`readAttachment`、`uploadToPc` | `ImageIO`/Skia 缩放；文件选择/拖拽入库 | M | 中（拖拽/编码） |
| 20 | `MEDIA:` 内联卡片 + `/outbox/send` | `Internal.mediaPaths/stripMedia`、`sendMedia`、`FileBubble` | 复用；图片走 Skia | S | 低 |
| 21 | 收件箱（PC→本机文件，长轮询） | `Inbox.kt` | 复用；保存到 `Downloads` 或用户目录 | S | 低 |
| 22 | PC 文件浏览器（盘符/Quick access/pin/上传/下载/改名/删除/复制路径） | `PcFiles.kt`、`fsJson/fsUpload/fsDownload` | 复用；补“在资源管理器中打开” | M | 中 |
| 23 | PC 屏幕管理（vdisplay 开关、截屏、缩放查看） | `ScreensScreen`、`vdisplay()`、`ZoomImage` | 复用 `/vdisplay/*` | L | 中（低优先级） |
| 24 | 设置页（连接/外观/上下文/模型/屏幕/更新/关于） | `SettingsScreen` | 复用 | S | 低 |
| 25 | 主题 System/Light/Dark/**True black** + 字号 50–150% + 12/24h | `trueBlack/darkGray`、`fontScale`、`Formats.clock` | 固定 accent 调色板（无 Material You）；`LocalDensity.fontScale` | S | 中（桌面动态取色不可用） |
| 26 | 中/英 in-app 切换（306 键） | `L10n`、`values-zh-rCN` | 自建 `Strings` 表（见 §4.7），脚本从 XML 生成 | M | 中（CMP 资源不能运行时切 locale） |
| 27 | 配对（扫码 / `hermes://pair` / 手填） | `ScanActivity`、`handlePair`、`SettingsScreen` | 粘贴链接、粘贴 key、二维码图片、剪贴板、协议注册；无摄像头 | M | 中 |
| 28 | 明文 HTTP 开关 + 私网校验 + 警告 | `SecureStore.allowLan`、`ApiClient.isTailscaleHost` | 复用；本机 loopback 默认放行 | S | 低 |
| 29 | 连接探测（relay/gateway/局域网三路） | `testConnections`、`probes` | 复用 | S | 低 |
| 30 | 断线重连 / 恢复前台重连 | `reconnect`、`onForeground`、`LifecycleEventEffect` | 窗口 focus + 退避重连 | S | 低 |
| 31 | 通知（回复完成/审批/提问/错误，点击进会话，已读清除） | `Notify`、`KeepAliveService` | 托盘气泡/Microsoft Toast + `FlashWindowEx` | M | 中（AUMID） |
| 32 | 应用内更新（PC 优先 → GitHub，下载并安装） | `UpdateManager` | 新版 `UpdateManager`（manifest + sha256 + 静默安装） | M | 中 |
| 33 | Hotfix（横幅 / min_version / relay_url / features） | `Hotfix.parse`、`HotfixBanner` | 复用，`hotfix.json` 增 `desktop` 段（向后兼容） | S | 低 |
| 34 | 提权请求（elevate 审批） | `pollElevate`、`decideElevate` | 复用；可加 UAC 提示 | S | 中 |
| 35 | 单条 token/速度统计 | `applyUsage`、`store.replyStat` | 复用 | S | 低 |
| 36 | 应用图标切换 / 省电模式 / 电池豁免 | `chooseIcon`、`choosePower`、`KeepAliveService` | **不做**（桌面无意义），设置页隐藏 | — | — |
| 37 | 后台常驻 | `KeepAliveService` | 托盘常驻 + 关闭到托盘 + 开机自启 | S | 低 |
| 38 | 多窗口 / 全局快捷键 | 无 | 托盘“新窗口”、`Ctrl+Alt+H` 显隐 | M | 中（P5） |
| 39 | 标签页跨端同步 | `/tabs`（手机只上报） | 可选接 `POST /tabs` | S | 低 |

---

## 3. 技术选型对比

### 3.1 评分（1–5，5 最好）

| 维度 | Compose MP Desktop | Tauri 2 | Electron | WinUI 3 / .NET MAUI |
|---|---:|---:|---:|---:|
| Kotlin/现有代码复用 | **5** | 1 | 1 | 1 |
| UI 复用（现成 Compose 页面） | **5** | 1 | 1 | 1 |
| 安装包体积 | **4**（35–50 MB） | 5（3–8 MB） | 1（80–150 MB） | 3（自包含 ~60 MB） |
| 内存占用 | 3（JVM ~180–300 MB） | 5（~60 MB） | 2（~250 MB） | 4 |
| 自动更新成熟度 | 3（自建，简单） | 4（内置 updater） | 5（electron-updater） | 3（MSIX） |
| 托盘 / 多窗口 / 全局快捷键 | **4**（Tray/Window 原生支持；快捷键需 JNA） | 4 | 5 | 5 |
| 中文 IME / 文本输入 | **3（待验证，最大风险）** | 4（WebView2） | 5 | 5 |
| 纯开源依赖 | **5** | 3（**WebView2 闭源**） | 4（Chromium/Node 开源，体积大） | 4（WinUI/MAUI MIT，需 MSIX 签名） |
| 交付速度（到 MVP） | **5** | 2 | 2 | 2 |

### 3.2 逐项结论

**① Compose Multiplatform Desktop —— 推荐**
- 与现有 `MainActivity.kt` 是同一套 Compose API，`LazyColumn`/`InputChip`/`AlertDialog`/`MaterialTheme` 基本零改动；`ChatViewModel` 只需替换 Android 类型。
- 网络层（`ApiClient.kt`、`GatewayWs.kt`）是纯 JVM 友好的 OkHttp + `org.json`，直接搬。
- 纯开源栈：Kotlin (Apache-2.0)、Compose MP + Skiko (Apache-2.0)、OpenJDK (GPLv2+CE)、OkHttp (Apache-2.0)、JNA (Apache-2.0)、zxing (Apache-2.0)、Inno Setup（BSD 类许可）。
- 代价：JVM 体积/内存；**中文 IME 必须实测**（Skia 文本框在 Windows 上经 AWT 输入法桥，候选窗定位历史上有瑕疵）。

**② Tauri 2 —— 不推荐**
- UI 全部重写为 TypeScript，Kotlin 资产归零；Rust 侧还要重写流式状态机。
- 依赖 **WebView2（闭源，微软）**，与“只用开源”要求冲突；虽然 Win11 预装，但企业/精简系统可能缺。
- 唯一优势是 3–8 MB 安装包，对本项目不值得。

**③ Electron —— 不推荐**
- 功能没问题（Markdown、WebSocket、托盘、自动更新都成熟），但 80–150 MB 安装包 + 250 MB 内存，且 100% 重写。
- 若团队是前端背景才考虑；这里不是。

**④ WinUI 3 / .NET MAUI —— 不推荐**
- C# 全量重写；中文输入、原生感最好，但复用率为 0。
- MSIX 打包需要签名证书；MAUI 的 Windows 目标成熟度一般。
- 适合“从零做 Windows 原生”的场景，不适合移植。

### 3.3 备选触发条件

若 P0 验证发现 Compose 的 IME 不可接受（中文候选窗错位/无法上屏），按顺序降级：
1. **Swing interop 文本框**：Compose 里嵌 `JTextField`（`SwingPanel`），只替换输入框一处，其余不变（+3–5 人日，风险中）。
2. 若连这也失败 → 转 Tauri（前端重写，+6–8 周），并把 WebView2 作为“系统组件”单独说明。

---

## 4. 目标架构

### 4.1 模块划分（建议在**同一仓库**内演进）

```
HermesAndroid/
├─ app/                      # 现有 Android 模块（保持可发布，不冻结点）
├─ shared/                   # 新增：KMP，jvm + android
│   └─ src/commonMain/kotlin/dev/oscar/hermes/
│       ├─ ApiClient.kt      # 原样（org.json 依赖）
│       ├─ GatewayWs.kt      # 原样
│       ├─ model/            # Session, Msg, Step, Chat, Project, TaskInfo, Hotfix...
│       ├─ ChatEngine.kt     # 由 ChatViewModel 拆出的“无 UI 状态机”
│       ├─ Formats.kt        # 代码块切分/时间格式
│       ├─ Strings.kt        # 生成的中英文字符串表
│       └─ platform/         # expect: SettingsStore, SecretStore, Notifier, FilePicker, Opener, Clock
├─ desktop/                  # 新增：JVM 应用（Compose MP）
│   └─ src/jvmMain/kotlin/dev/oscar/hermes/desktop/
│       ├─ Main.kt           # application { Tray; Window; single-instance }
│       ├─ ui/               # 由 MainActivity.kt 拆出的页面（SessionsScreen/ChatScreen/...）
│       ├─ WinSecretStore.kt # DPAPI / Credential Manager
│       ├─ WinNotifier.kt    # 托盘气泡 / Toast
│       ├─ WinFiles.kt       # FileDialog / DropTarget / Desktop.open
│       └─ Updater.kt
├─ pc/                       # relay / tray / install（少量改动）
└─ scripts/release.py        # 加 --desktop 分支
```

**关键决策：不要一上来做“大重构”。**
1. 第 1 步只新建 `desktop/`，把 `ApiClient.kt`、`GatewayWs.kt`、`Formats.kt` **复制**过去（允许短暂重复）。
2. 等桌面端 MVP 跑通，再把两份收敛到 `shared/`（`git mv` + 改 import），Android 侧只动 `build.gradle.kts`。
3. 理由：Android 是当前唯一可用客户端，任何“先抽 shared 再开发桌面”的做法都会让两端同时不可发布。

### 4.2 网络层

| 关注点 | 方案 |
|---|---|
| HTTP | OkHttp 4.12（已是依赖）直接用在 JVM；无需改调用代码 |
| WebSocket | OkHttp `newWebSocket` 同上；`pingInterval` 30 s（桌面无省电模式，固定 30 s） |
| JSON | 继续用 `org.json`，JVM 加 `org.json:json`（Android 内置同名类，API 一致）；后续可选迁 `kotlinx.serialization` |
| 默认地址 | `http://127.0.0.1:8643`（客户端与 relay 同机时零配置、无需 Tailscale）；仍支持远端 `http://100.x.x.x:8643` / LAN |
| 明文校验 | 保留 `ApiClient.isTailscaleHost` 的私网逻辑；**loopback 始终允许**（本机回环不算明文风险） |
| 并发模型 | `Dispatchers.Main` 由 `kotlinx-coroutines-swing` 提供（Compose Desktop 的 EDT）；IO 仍 `Dispatchers.IO` |
| 重连 | 窗口 focus / 网络恢复时 `reconnect()`；指数退避 1→2→5→10→30 s，上限 30 s |
| 超时 | 沿用 8 s connect / 60 s read；`prompt.submit` 120 s；长轮询 `/outbox?wait=` 用独立 client |

> 桌面端与 Hermes 桌面 App 共用同一个 gateway，会同时 attach 同一 session。手机端 README 已有“不要两端同时输入”的告警，
> 桌面端需在 UI 上保留同样提示（或在 `session.active_list` 里检测到别的客户端时显示横幅）。

### 4.3 存储与密钥

替换 `SecureStore.kt`：

| Android | Windows |
|---|---|
| `SharedPreferences("hermes")` | `%APPDATA%\Hermes\desktop\settings.json`（UTF-8，原子写：临时文件 + `ATOMIC_MOVE`） |
| `AndroidKeyStore` AES-GCM 非导出密钥 | **DPAPI**：`Crypt32Util.cryptProtectData(bytes, entropy)`（JNA `jna-platform`，Apache-2.0），密文 base64 存 `secrets.bin` |
| — | 可选增强：Windows **Credential Manager**（`CredWriteW/CredReadW`，`CRED_TYPE_GENERIC`，Target=`Hermes/relay-key`），用户可在“控制面板→凭据管理器”里查看/删除 |
| `prefs.getStringSet("media_done")` | JSON 数组 + 上限 500，逻辑不变 |
| `snap_$profile` / `ctx_$profile` 缓存 | 同一个 `settings.json` 的 `cache` 段，或 `cache/<profile>.json` |

**为什么默认 DPAPI 而不是 Credential Manager**：DPAPI 一行 JNA 调用、无 COM、无需管理员、天然按用户隔离，安全边界与 Credential Manager 相同（后者本身也是 DPAPI 加密）。Credential Manager 作为“可管理性”加分项放 P5。
**迁移**：首次启动若发现旧 `settings.json` 无 `secrets`，提示重新输入 key（手机上的 key 无法导出，这是设计使然）。

### 4.4 配对（无摄像头）

保留同一个配对载荷格式：`hermes://pair?url=<relay>&lan=<lan>&key=<key>`（`handlePair` 已实现解析）。

四条入口：
1. **粘贴链接**：设置页一个输入框，支持整段 `hermes://pair?...`。
2. **粘贴 key**：URL + key 两个字段（现有 UI 已有）。
3. **二维码图片**：文件选择 → `BufferedImage` → `BufferedImageLuminanceSource` + `MultiFormatReader`（`com.google.zxing:core` + `javase`）解码。
4. **协议注册**：安装器写 `HKCU\Software\Classes\hermes`（`URL:hermes Protocol`，`shell\open\command = "...\HermesDesktop.exe" "%1"`），PC 托盘“配对桌面端”按钮直接唤起并填好。
5. （可选）剪贴板监听：启动时若剪贴板内容匹配 `hermes://pair`，弹一次性提示。

PC 侧配套：`pc/tray` 的配对页（`pc/pair_page.py`）增加“下载 Windows 客户端 / 配对桌面端”两个按钮；二维码仍由 PC 生成，桌面端可扫图。

### 4.5 通知

| 阶段 | 方案 | 能力 |
|---|---|---|
| MVP | `java.awt.TrayIcon.displayMessage(title, text, NIIF_INFO)` | 系统通知中心可见、点击回调、无按钮 |
| P5 | WinRT `ToastNotificationManager`（AUMID = 安装器写入的 `Hermes.Desktop`；快捷方式带 `System.AppUserModel.ID`） | 按钮（批准/拒绝）、输入框、Activation 回调 |
| 兜底 | `FlashWindowEx` + `SetForegroundWindow`（JNA `user32`） | 审批/提问必须“抢注意力”时 |

要点：
- 通知去重/清除逻辑复用 `Notify.shown` 的 `session|kind` 键。
- 点击通知 → 激活已有窗口并 `switchTo(chat)`；单实例转发参数（§4.8）。
- 未打包（portable）时 Toast 无 AUMID，只能退化为托盘气泡 —— 这是选择 Inno 安装版的理由之一。

### 4.6 文件、剪贴板、拖拽

| 场景 | Android | Windows |
|---|---|---|
| 选附件 | `OpenMultipleDocuments` | `java.awt.FileDialog`（多选）或 Compose 自绘 + `Files.list` |
| 拖拽入窗 | 无 | `java.awt.dnd.DropTarget` + `TransferHandler`（`DataFlavor.javaFileListFlavor`） |
| 图片缩放 | `ImageDecoder` → JPEG 85 | `ImageIO.read` → `Graphics2D` 缩放 ≤1600 px → `ImageIO.write("jpg")` |
| 文本附件 | ≤200 KB 内联 | 同逻辑，`Files.readString` |
| 大文件上传 | `/upload/init` 分片 | 复用；`FileChannel` 替代 `ParcelFileDescriptor` |
| 下载/收件箱 | MediaStore Downloads | `%USERPROFILE%\Downloads\Hermes\`，冲突加 `(1)` |
| 打开文件/目录 | Intent | `java.awt.Desktop.open` / `explorer.exe /select,` |
| 剪贴板 | `LocalClipboardManager` | 同 API（Compose Desktop 支持） |

### 4.7 UI 适配

**主题**：`trueBlack()` / `darkGray()` 两个函数可以原样用；`dynamicLightColorScheme` 在桌面不可用 → 改为固定 accent 调色板（Hermes 橙、蓝、绿、紫、灰 5 套 + 自定义 hex），由 `MaterialTheme(colorScheme = ...)` 注入。AMOLED 纯黑在桌面意义不大但保留（用户要求 parity）。

**字号 50–150%**：
```kotlin
val d = LocalDensity.current
CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale)) { App() }
```
比 Android 的 `attachBaseContext` 改 `fontScale` 更干净，且不需要重启 Activity。

**语言（关键坑）**：Compose Multiplatform 的资源按**系统 locale** 解析，没有官方“运行时切语言”API。
因此**不要**用 `composeResources` 做 in-app 切换，而是：
- 用脚本把 `res/values/strings.xml` + `values-zh-rCN/strings.xml`（306 键）生成 `Strings.kt`：
  ```kotlin
  object Str { val en = mapOf("pf_upload" to "Upload", ...); val zh = mapOf(...) }
  ```
- `val Lang = compositionLocalOf { "system" }`，`fun s(key: String, vararg args: Any)` 内部按 `Lang` + `Locale.getDefault()` 选择；
- 切换语言 = 改一个 `mutableStateOf`，整棵树重组，**不重启**（比 Android 体验更好）。
- 迁移脚本放 `scripts/gen_strings.py`，从 XML 生成，保证两边文案同源。

**标签页**：`LazyRow` + 现有拖拽逻辑换成 `detectDragGesturesAfterLongPress` 或直接鼠标拖动；快捷键 `Ctrl+Tab` / `Ctrl+Shift+Tab` / `Ctrl+W` / `Ctrl+T`。

**布局**：手机是单列 + 顶栏；桌面 ≥1200 px 时建议**左侧会话栏 + 右侧聊天**（`Row` 布局），窄窗口回落到单列。这是唯一“值得偏离手机 UI”的地方，能显著提升桌面可用性 —— 列为 P5 可选项，MVP 先保持单列以最大化复用。

### 4.8 进程、托盘、单实例、快捷键

- **单实例**：`FileLock` 锁 `%LOCALAPPDATA%\Hermes\desktop\app.lock`；第二个实例把 `hermes://` 参数写入 `%LOCALAPPDATA%\Hermes\desktop\ipc.json` 后退出，主实例轮询/监听并处理。
- **托盘**：`Tray(icon, menu)`；菜单：显示主窗口 / 新建聊天 / 检查更新 / 打开日志 / 退出；双击图标显隐。关闭按钮 → 最小化到托盘（设置可改“直接退出”）。
- **开机自启**：`HKCU\Software\Microsoft\Windows\CurrentVersion\Run`，值为 `"...\HermesDesktop.exe" --tray`；设置页开关。
- **全局快捷键**：JNA `user32.RegisterHotKey`（`Ctrl+Alt+H` 显隐），失败时静默降级。
- **多窗口（P5）**：`Window` 每实例一个；托盘“在新窗口打开当前会话”。需要把 `ChatEngine` 从“单例 + cur”改为按 window 注入，属于结构性改动，放最后。
- **KeepAlive 替代**：桌面无 Doze，删除 `KeepAliveService` 与 WakeLock；仅保留“窗口隐藏时不暂停 WS”。

---

## 5. 路线图

> 假设 1 名熟悉 Kotlin/Compose 的开发者，按 5 天/周计。含自测，不含大范围 UI 打磨。

| 阶段 | 内容 | 工期 | 产出 / 验收 |
|---|---|---:|---|
| **P0 技术验证（Gate）** | 空 Compose MP 窗口；OkHttp WS 连上 relay 并跑通 `session.resume`→`prompt.submit`→`message.delta`；渲染一段 Markdown；托盘图标；jpackage app-image；**微软拼音输入中文 + 候选窗 + 粘贴** | **3–5 天** | 一份 `spike/` 分支 + 结论：IME 是否可用。不可用则走 §3.3 |
| **P1 骨架** | `desktop/` 模块、`SettingsStore`（JSON+DPAPI）、`Strings` 生成、主题/字号、设置页（连接/外观/语言）、配对（粘贴/URL/图片）、单实例/托盘/日志 | 1.5 周 | 能配对、能改主题/语言、重启后设置还在 |
| **P2 聊天核心（MVP）** | 会话列表、聊天页、流式、Markdown/代码块、Steps、标签页、模型/思考 chip、斜杠、审批/Clarify、断线重连、托盘通知 | 2 周 | 与手机端同一 session 可正常对话、审批、切模型 |
| **P3 项目与文件** | 项目 CRUD + 目录选择、移动/批量删除、PC 文件浏览器、收件箱、`MEDIA:` 卡片、附件（选择+拖拽+分片上传）、任务卡片（子代理/后台进程） | 1.5–2 周 | 手机端能做的文件/项目操作桌面端都能做 |
| **P4 打包发布** | jpackage + Inno Setup、`latest.json`、relay 白名单补丁、`release.py --desktop`、热更新（hotfix）、自启/协议注册 | 1 周 | 双击安装 → 首次启动 → 检查更新 → 静默升级全链路通 |
| **P5 打磨 / 完整 parity** | 上下文/默认模型设置、屏幕页（vdisplay）、提权审批、token 统计、可操作 Toast、全局快捷键、多窗口、双栏布局、中文文案校对 | 1–1.5 周 | parity 表全绿（除 #36 明确不做） |

**合计 9–11 周。**

### MVP 切口（4–5 周，P0+P1+P2 + P4 的最小集）

**做**：连接/配对（粘贴+URL）、会话列表（按 profile 分组）、聊天流式、Markdown、Steps、标签页、模型/effort、斜杠、审批、Clarify、主题（含 true black）、字号、中英切换、托盘常驻、断线重连、托盘通知、便携 ZIP + 手动“检查更新”。
**不做（MVP 后）**：项目 CRUD（先只按 Home 平铺）、PC 文件浏览器、收件箱/`MEDIA:`、附件、任务卡片、屏幕页、提权、自动安装更新、多窗口。

> 这个切口能验证“桌面端是否真的比 Hermes 自带桌面 App 更好用”这一核心假设，再决定是否投剩余 5 周。

---

## 6. 打包与发布

### 6.1 产物形态

| 形态 | 工具 | 大小 | 用途 |
|---|---|---:|---|
| `app-image`（目录） | `jpackage --type app-image` | 60–80 MB | 开发/免安装调试 |
| `HermesDesktop-<ver>-<code>-setup.exe` | **Inno Setup 6**（BSD 类许可，开源）包装 app-image | 35–50 MB | **主发布物**，按用户安装到 `%LOCALAPPDATA%\Programs\Hermes\Desktop`，无需管理员 |
| `HermesDesktop-<ver>-<code>-portable.zip` | 7-Zip/zip | 30–45 MB | 便携版（无 Toast AUMID、无自启） |
| `.msix`（可选 P5） | MSIX Packaging Tool | 类似 | 需要签名；换来干净的 Toast 身份与 Store 分发 |

jlink 裁剪（去掉 `jdk.compiler`、`jdk.javadoc`、`javafx` 等）：
```bash
jlink --add-modules java.base,java.desktop,java.logging,java.net.http,java.prefs,jdk.crypto.ec,jdk.unsupported \
      --strip-debug --no-header-files --no-man-pages --compress=2 --output build/runtime
```
> 注意：**不要用 WiX**。jpackage 的 `--type msi` 依赖 WiX Toolset 3（MS-RL，非 OSI 认可），与“只用开源”要求冲突；Inno Setup 是更干净的选择。

### 6.2 版本与清单

`desktop/version.properties`（独立于手机，避免版本号耦合）：
```
VERSION_CODE=1
VERSION_NAME=0.1.0
```

`latest.json`（发布到 relay 的 `app-updates/hermes-desktop/`）：
```json
{
  "version_code": 7,
  "version_name": "0.1.6",
  "notes": "修复中文候选窗；新增文件页",
  "min_version_code": 3,
  "installer": "HermesDesktop-0.1.6-7-setup.exe",
  "portable": "HermesDesktop-0.1.6-7-portable.zip",
  "sha256": { "HermesDesktop-0.1.6-7-setup.exe": "…", "HermesDesktop-0.1.6-7-portable.zip": "…" }
}
```
> 与手机端 `latest.json` 的差异：手机是 `version_code/version_name/notes` + 固定 `apk` 文件名；桌面端需要**文件名 + 哈希**，因为安装包名带版本号、且必须校验完整性（避免半包/被替换）。

### 6.3 relay 改动（唯一服务端改动，约 10 行）

现状 `pc/relay/hermes-phone-relay.py` `app_files()`（约 168–184 行）只允许：
```python
if what in ("latest.json", "hotfix.json"): ...
if what == "apk": ...
```
改为：
```python
SAFE = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,79}")
if what in ("latest.json", "hotfix.json") or SAFE.fullmatch(what) and ".." not in what:
    f = d / what
    return web.FileResponse(f, headers={"Cache-Control": "no-store"}) if f.is_file() else 404
```
- 保持 `app_id` 的现有正则校验（防目录穿越）。
- 兼容手机端：`/app/hermes-android/apk` 仍指向 `app.apk`（保留特例），或发布时直接把文件命名为 `apk`。**建议保留特例，零回归。**
- 托盘/`install.ps1` 的 PC bundle 新增 `app-updates/hermes-desktop/` 目录。

### 6.4 `scripts/release.py` 扩展

新增 `--desktop`（与手机发布互不干扰）：

```bash
python scripts/release.py --desktop --notes "…"              # bump + 打包 + 发到 relay
python scripts/release.py --desktop --notes "…" --github     # 同时发 GitHub Release
python scripts/release.py --hotfix                            # 不变（hotfix.json 两端共用）
```

内部步骤：
1. 读/递增 `desktop/version.properties`。
2. `./gradlew :desktop:packageRelease`（自定义 task 串 jpackage + jlink）。
3. 生成 `latest.json`（含 sha256）。
4. `ISCC.exe installer/hermes-desktop.iss /DMyVersion=… /DMyCode=…` → `setup.exe`。
5. 复制 `setup.exe` + `portable.zip` + `latest.json` + `hotfix.json` 到
   `%LOCALAPPDATA%\hermes\launchers\app-updates\hermes-desktop\`。
6. `--github` 时 `gh release create v<ver>-desktop <setup> <zip>`。
7. 断言：`latest.json.version_code` 与产物文件名一致；sha256 与文件实际值一致（防止发错包）。

### 6.5 应用内更新流程

```
启动/每 6 h → GET {relay}/app/hermes-desktop/latest.json（Bearer）
           ↘ 失败则 GitHub releases/latest（找 *-setup.exe）
若 version_code > 当前：
  下载到 %LOCALAPPDATA%\Hermes\updates\<ver>\setup.exe（断点续传，复用 Downloader.kt 思路）
  校验 sha256 + 文件大小 + （可选）Authenticode 签名者
  提示“重启并更新” → 运行 setup.exe /VERYSILENT /SUPPRESSMSGBOXES /NORESTART /CLOSEAPPLICATIONS
  Inno 的 CloseApplications=yes 处理文件占用；[Run] 段重启应用
便携版：下载 zip → 解压到 <app>\new → 写 swap.cmd → 退出 → 批处理 robocopy 覆盖 → 重启
```
- **签名**：未签名会触发 SmartScreen。方案见 §7 R6；签名不影响开源合规（组件仍全开源）。
- **回滚**：保留上一版 `app-image` 目录（`app.prev`），更新失败时托盘菜单“回退上一版”。

### 6.6 hotfix（免安装配置）

`hotfix.json` 增加 `desktop` 段，**Android 的 `Hotfix.parse` 会忽略未知键**，向后兼容：
```json
{
  "banner": {"en": null, "zh": null},
  "min_version_code": 0,
  "relay_url": null,
  "features": {"attachments": true, "server_slash": true},
  "desktop": {
    "min_version_code": 3,
    "banner": {"en": "…", "zh": "…"},
    "features": {"attachments": true, "screens": false, "multi_window": false}
  }
}
```
桌面端 `Hotfix` 解析优先读 `desktop`，缺失时回落到顶层。

---

## 7. 风险

| # | 风险 | 影响 | 概率 | 缓解 |
|---|---|---|---|---|
| R1 | **中文 IME 在 Compose/Skia 文本框**候选窗错位、无法上屏、组合串丢失 | 高（目标用户中文为主） | 中 | P0 首日实测微软拼音 + 搜狗；失败→`SwingPanel(JTextField)` 只替换输入框；再失败→Tauri |
| R2 | Compose Desktop 的文本选择/复制/右键菜单与原生体验差距 | 中 | 中 | 自绘 `ContextMenu`；`SelectionContainer` 已有 |
| R3 | JVM 体积/冷启动（1.5–3 s） | 中 | 高 | jlink 裁剪 + CDS（`-XX:ArchiveClassesAtExit`）；接受 |
| R4 | 共用 gateway 会话：桌面客户端与 Hermes 桌面 App 同时 attach 同一 session | 中 | 中 | UI 横幅提示；检测 `session.active_list`；文档写明 |
| R5 | 从 `ChatViewModel`(2210 行) 拆 `ChatEngine` 时回归 | 高 | 中 | 先复制后收敛；以手机端为“黄金实现”，每步跑双端对比 |
| R6 | 无代码签名 → SmartScreen 警告 / 无法做 MSIX | 中 | 高 | 购买 OV 证书或 Azure Trusted Signing；否则文档说明“更多信息→仍要运行” |
| R7 | WiX（MS-RL）/ WebView2（闭源）污染开源合规 | 中 | 低 | 明确不用 jpackage MSI、不用 Tauri |
| R8 | Markwon → 新渲染器的表格/嵌套列表/长代码差异 | 低 | 中 | 保留 `Formats.split`；对 XML/JSON 走等宽块（手机端同样策略） |
| R9 | relay 的 `/app` 白名单改动引入路径穿越 | 高 | 低 | 严格正则 + `resolve()` 后校验父目录 + 单测 |
| R10 | 通知不可达导致审批超时 | 中 | 中 | 审批强制置前 + 任务栏闪烁 + 托盘红点，不只依赖 Toast |
| R11 | 桌面端“文件页”在本机其实可直接访问，走 relay 反而慢 | 低 | 高 | 保留 relay 路径（远端可用）；本机可选直连（P5） |
| R12 | 用户机器无 JRE / 杀软误报 | 中 | 中 | 自带裁剪 JRE；提交 Microsoft 误报；提供 portable |

---

## 8. 已确认的决定（用户 2026-11-25 回答）

1. **签名**：先不买证书，发布未签名安装包（SmartScreen 会提示，文档里写明“更多信息 → 仍要运行”）；Toast 走托盘气泡降级方案，MSIX 不做。
2. **定位**：**替代** Hermes 自带桌面 App，不再需要“双端同时输入”的提示文案；需要覆盖自带 App 的常用能力。
3. **连接范围**：本机 `127.0.0.1:8643` 与远程 relay 都支持。
4. **PC 文件页**：本机访问**直接读磁盘**（`java.nio.file`，更快、大目录更好）；连远程 relay 时仍走 `/fs/*`。需抽象 `FileBackend` 接口（Local / Relay 两个实现）。
5. **多窗口**：不需要，单窗口 + tab；`ChatEngine` 单实例即可。
6. **最低系统**：Win10 1809+。
7. **`/tabs` 跨端标签同步**：**做**（桌面读取手机上报的标签），列入 P2。
8. **中文文案**：按桌面习惯改写（长按→右键、点击→单击等），因此 `gen_strings.py` 需支持桌面覆盖表 `strings-desktop.xml`。

---

## 9. 与 Hermes 自带桌面 App 的差距清单

> 依据：`C:\Users\oscar\AppData\Local\hermes\hermes-agent`（`apps/desktop` 源码、`tui_gateway/contracts/*.py` 协议契约、`gateway/platforms/api_server.py`）、官方文档 `website/docs/user-guide/desktop.md`、本仓库 `ApiClient.kt` / `GatewayWs.kt` / `ChatViewModel.kt`、`pc/relay/hermes-phone-relay.py`。
> **没有启动自带 App 做人工点测**（不动进程/窗口），结论来自源码与文档；无法验证的项集中在 §9.6。

### 9.1 三个决定性发现

1. **协议不是瓶颈，UI 才是。** relay 的 `/ws` 是**透明双向 pump**（不解析 method）；`* /api/{tail}` 会把**任意** `/api/*` 请求带一次性 ticket 转发给 gateway。自带 App 的管理面 100% 走 `/api/*`（`/api/skills*`、`/api/mcp/*`、`/api/cron/jobs`、`/api/providers/oauth`、`/api/audio/*`、`/api/local-models/*`、`/api/plugins/*`、`/api/messaging/*`、`/api/webhooks`、`/api/env`、`/api/config*`、`/api/hermes/update`、`/api/gateway/restart`、`/api/memory`、`/api/learning/*`）。→ 除 §6.3 的更新包白名单外，**服务端仍然零改动**；差距是页面、状态机和数据绑定。
2. **server→client 请求是一份硬性契约。** 契约里共 16 个：`approval`、`clarify`、`sudo`、`secret`、`vault.code`、`vault.save_login`、`vault.unlock_prompt`、`preview.read`、`preview.act`、`terminal.read`、`window.read`、`theme.apply`、`theme.pick`、`tour`、`setup_beat`、`display.install.sudo`。客户端目前只处理 `approval` / `clarify`，其余一律回 `-32601 "not supported on the phone"`。→ 凡是要 sudo 的命令、vault 取密码、`drive_preview`/`annotate_preview`、agent 改主题，在手机端**现在就是坏的**；桌面端若照搬会同样坏。
3. **两个"看像功能、实为客户端基建"的大件**：内置终端（Electron `node-pty` + `xterm`）和预览面板/内置浏览器（webview + `drive_preview`）。gateway 只提供 `terminal.read` / `preview.*` 这种"向客户端要内容/让客户端动作"的请求，**不提供终端与浏览器本身**。移植等于从零写 PTY + 终端模拟器（或嵌 JCEF/WebView2）。

### 9.2 差距主表

工作量口径同 §2：**S** ≤2 人日，**M** 3–7，**L** 8–15，**XL** >15。`替代需要`：must = 不补就不算"替代"；should = 高频、强烈建议；could = 可选；skip = 明确不做。

#### A. 聊天与流式

| # | 能力 | 自带桌面 | Android / 本计划 | 替代需要 | 协议 / RPC | 工作量 |
|---|---|---|---|---|---|---|
| A1 | 流式、思考、工具步骤折叠 | 有 | 有（#1/#3） | 已覆盖 | `message.*`/`tool.*` | — |
| A2 | 队列**编辑**（暂停、改、删单条） | 有 | 只有"排队"（#1） | should | 队列在 gateway；`prompt.submit` + `prompt.cancel`/`prompt.receipt`（具体 admission 字段未逐条验证） | M |
| A3 | 断线事件回放（不丢 delta） | 有（`session.events.since` + `gateway.ready` epoch） | 无（重连重拉全量 `messages`） | **must** | `session.events.since`、`gateway.ready` | M |
| A4 | 长会话历史分页 | 有（`session.history`/窗口化） | 无（一次拉全量，长会话卡） | **must** | `session.history` 或 `/api/sessions/{id}/messages?offset=&limit=` | M |
| A5 | 上下文用量**分类明细**（system/tools/skills/memory/MCP/对话） | 有 | 只有百分比 chip（#15） | could | `session.context_breakdown` | S |
| A6 | 每会话 YOLO 开关 | 有 | 无 | should | `session.mutate{yolo}`（含 `scope`） | S |
| A7 | 审批增强（内联 diff、命令详情、always 作用域说明） | 有 | 基础四选项（#12） | should | `approval` 请求自带 `choices`/`allow_*` | S |
| A8 | `sudo` / `secret` 交互 | 有 | **无（-32601）** | **must** | server requests `sudo`、`secret` | S |
| A9 | 语音：听写、朗读、唤醒词、实时语音 | 有 | 无 | could | `voice.toggle/record/tts` 或 `/api/audio/transcribe\|speak` | L |
| A10 | 图片生成 | 有 | 无 | could | `image.generate` / 相关 `/api/*` | M |
| A11 | 大粘贴折叠为文件 | 有 | 无 | could | `paste.collapse` | S |
| A12 | `@` 路径补全（不只是 `/` 命令） | 有 | 无（#11 只有 slash 补全） | should | `complete.path` | S |

#### B. 会话与项目

| # | 能力 | 自带桌面 | Android / 本计划 | 替代需要 | 协议 / RPC | 工作量 |
|---|---|---|---|---|---|---|
| B1 | 会话搜索（标题/id）、归档、隐藏、置顶 | 有 | 无（只有列表 + 分组 #5） | **must** | `/api/sessions?title=&offset=&include_hidden=`、`/api/sessions/search`（**已可用，无需改 relay**） | S |
| B2 | 会话分支/分叉 | 有 | 无 | should | `session.branch`/`branch_stored`/`branch_whole`、`POST /api/sessions/{id}/fork` | M |
| B3 | 撤销/回退到上一轮（rewind） | 有 | 无 | should | `session.undo`、`rollback.*` | M |
| B4 | 压缩会话 / 导出 | 有 | 无 | could | `session.compress`、`session.export` | S |
| B5 | 导入外部转录（Claude/Codex 等） | 有（`/session-import`） | 无 | could | `session.foreign.list/preview/import` | M |
| B6 | 跨 profile 会话（并发 + `@session` 引用） | 有 | 部分（多 profile 可连 #6，但 WS 一次一个 profile） | could | 多 WS / `session.foreign` | L |

#### C. 工作区（终端 / 文件 / 预览 / Git）

| # | 能力 | 自带桌面 | Android / 本计划 | 替代需要 | 协议 / RPC | 工作量 |
|---|---|---|---|---|---|---|
| C1 | 内置终端（多标签、隐藏保活、输出"加入聊天"） | 有（`node-pty`+`xterm`） | 无 | should | 客户端 PTY + 回应 `terminal.read`、`agent.terminal.output` | L |
| C2 | 会话工作目录**文件树 + 预览**（图片/文本，reveal in filetree） | 有 | 部分（#22 是盘符浏览，不是会话工作目录树） | should | 本地 FS（§8-4 直读）或 `/api/*` 目录端点（端点名未验证） | M |
| C3 | 预览面板 + 内置浏览器（`drive_preview`/`annotate_preview`、注释模式） | 有 | 无 | **skip**（需 WebView2——闭源，与附录 B 冲突；JCEF 体积/内存不可接受） | 回应 `preview.read`/`preview.act`，`preview.open/close` 事件 | XL |
| C4 | Git Review 面板（diff/stage/commit/PR/切分支） | 有 | 无 | should（开发者向用户） | `review.summary` 事件 + 本地 `git` CLI | L |
| C5 | Git worktree 管理 | 有 | 无 | could | 本地 `git worktree` | M |
| C6 | Artifacts 画廊（产出物索引/预览/下载） | 有 | 无 | could | 客户端索引（未见专用 RPC） | M |
| C7 | 时间线导航、页内查找、阅读位置记忆 | 有 | 无 | could | 纯客户端 | M |

#### D. 管理面（自带 App 的主战场，本计划几乎是空白）

| # | 能力 | 自带桌面 | Android / 本计划 | 替代需要 | 协议 / RPC | 工作量 |
|---|---|---|---|---|---|---|
| D1 | 定时任务 UI（列表/暂停/改/运行历史/跨 profile） | 有 | 无（#18 只有后台进程进度轮询） | **must** | `cron.manage/status/submit/cancel`、`/api/cron/jobs` | M |
| D2 | Skills：列表、启停、内容编辑、可选技能目录、Hub 搜索安装 | 有 | 无（只显示技能名） | **must** | `/api/skills*`（hub search/install/toggle/lock） | M |
| D3 | Toolsets / 单工具开关与详情 | 有 | 无 | should | `tools.list/show/configure`、`toolsets.list`、`/api/tools/toolsets` | M |
| D4 | MCP 服务器管理（增删/测活/OAuth/密钥/作用域） | 有 | 无 | should | `mcp.servers.*`、`/api/mcp/*` | L |
| D5 | Connectors 账户 | 有 | 无 | could | `connectors.*` | L |
| D6 | Plugins 市场/启用/设置表单 | 有 | 无 | could | `plugins.*`、`/api/plugins/*` | L |
| D7 | Provider 管理：加 API key、OAuth 登录、断开、自定义端点、测活 | 有 | 部分（#16 只能在**已配置 provider** 的模型里选，不能加 key） | **must** | `model.save_key`/`model.disconnect`、`/api/providers/oauth*`、`/api/providers/custom-endpoints` | L |
| D8 | 本地模型（llama.cpp 安装/下载/启停） | 有 | 无 | skip（用户已有本地/远端 provider） | `/api/local-models/*` | XL |
| D9 | 通用 Settings（`config.get/set`、Safety/Memory/Voice/Chat/Advanced 各页、per-profile "Applies to"） | 有 | 部分（#24 只有连接/外观/上下文/模型/屏幕/更新） | **must** | `config.get/set/show`、`/api/config*` | L |
| D10 | Vault（保存登录、取码、解锁提示） | 有 | 无（-32601） | could | `vault.*` + 3 个 server request | L |
| D11 | Memory：MEMORY.md 查看/编辑 + 记忆图（Star Map）/Learning Journey | 有 | 无 | should | `/api/memory`、`learning.frames/detail/edit/delete`、`insights.get` | M |
| D12 | 主题：VS Code 主题导入、每 profile 主题、agent 切主题 | 有 | 部分（#25 只 Light/Dark/True black + 字号，无主题导入） | could | server requests `theme.apply`/`theme.pick` | M |
| D13 | i18n 六语言（zh-Hant / ja / ar RTL / ru） | 有 | 只 en/zh（#26） | could | 客户端文案表 | M |
| D14 | 命令面板 + 可重绑定快捷键 + 全局 Quick Entry + HUD | 有 | 部分（Ctrl+Tab/W #4、全局显隐 #38） | should（面板/键位）；could（HUD/Quick Entry） | 纯客户端 | M |
| D15 | 首次运行 onboarding（选 provider/布局/导入配置） | 有 | 无 | **must**（替代后首启必须自解释） | 客户端 + `/api/model/*`、`/api/config` | M |
| D16 | 更新后端、重启 gateway、卸载入口 | 有 | 部分（只更新自己 #32） | should | `/api/hermes/update`、`/api/gateway/restart` | M |
| D17 | 多连接注册表（多 gateway / SSH / cloud） | 有 | 部分（本机 + 一个远端 relay，#3） | could | 多 WS + SSH 隧道 | L |
| D18 | Messaging 渠道 / Webhooks 面板 | 有 | 无 | skip（用 CLI/dashboard 配） | `/api/messaging/*`、`/api/webhooks` | XL |

#### E. 平台级 / 花活

| # | 能力 | 自带桌面 | Android / 本计划 | 替代需要 | 协议 / RPC | 工作量 |
|---|---|---|---|---|---|---|
| E1 | Bot Mode（bot 花名册、群聊、Routines） | 有（内置） | 无 | skip | `groups.*`、`bot_relay.*`、多 session | XL |
| E2 | Agents / Command Center 编排 | 有 | 无（只有只读子代理卡片 #17） | skip | `subagent.*`、`spawn_tree.*` | XL |
| E3 | Pets / Kanban / Star Map 插件 | 有（插件） | 无 | skip | — | — |
| E4 | 多窗口/弹出窗口 | 有 | 无（§8-5 已定不做） | skip | — | — |
| E5 | `window.read`、截图快捷键、computer-use 面板 | 有 | 无 | skip | server request `window.read` | — |
| E6 | Billing / 免费额度 / 共享指标 | 有 | 无 | skip | `billing.*`、`shared_metrics.*` | — |
| E7 | 审批/提问的系统级通知与托盘 | 有 | 有（#31，桌面端换托盘/Toast） | 已覆盖 | 客户端 | — |

### 9.3 结论分层

- **必须补（不补不能叫"替代"）**：A3 事件回放、A4 历史分页、A8 sudo/secret、B1 会话搜索/归档、D1 Cron、D2 Skills、D7 Provider 加 key/OAuth、D9 通用 Settings、D15 Onboarding。共 9 项 ≈ **35–50 人日**。
- **应该补（决定"好不好用"）**：A2 队列编辑、A6 YOLO、A7 审批增强、A12 路径补全、B2 分支、B3 undo、C2 工作目录文件树、C4 Git Review、D3 Toolsets、D4 MCP、D11 Memory、D14 命令面板、D16 后端更新。≈ **60–85 人日**（C4/终端是大头）。
- **可以做**：A5 上下文明细、A9 语音、A10 生图、B4/B5/B6、C5/C6/C7、D5/D6/D10/D12/D13/D17、Quick Entry/HUD。
- **明确不做**：C3 预览/内置浏览器（与"纯开源"冲突）、D8 本地模型、D18 Messaging/Webhooks、E1–E6。这些在文档里写清"用 CLI/dashboard 完成"，并保证客户端对相应 server request **优雅拒绝**而不是超时。

### 9.4 关键实现约束（改代码前必须先定）

1. **`client.capabilities` 要如实申报。** 现在固定发 `server_requests: true`，但只实现 2/16 → gateway 会把 `preview.act`、`terminal.read`、`sudo` 发给一个不会处理的客户端。桌面端应改为按实际能力列表申报，并对未实现项**立即**回 `-32601`（不要静默）。
2. **`declines_not_shown` 与 `4404` 语义。** 契约里 `declines_not_shown` 决定后端是在"所有客户端都拒绝"后才落定请求；桌面端若带 `terminal`/`preview` 面板，需要按 §9.1-3 的方式真正应答，否则一律走"无此窗口"路径。
3. **终端/预览缺位会影响 agent 行为**，不只是 UI：`drive_preview`、`terminal.read` 类工具会失败或降级。文档要给用户一句"桌面端不支持浏览器预览"的说明。
4. **`/api/*` 已全通**，但都要求 `?profile=` 且每次请求重新换 ticket —— 频繁轮询（cron 状态、进程进度）要合并，别一秒一个 ticket。

### 9.5 修订后的工期

| 口径 | 原估 | 修订 | 说明 |
|---|---|---|---|
| MVP（能日常替代） | 4–5 周 | **7–8 周** | +A3/A4/A8/B1/D7-lite/D9-lite/D15 共 18–26 人日 |
| 替代级（must + should） | 9–11 周 | **15–19 周** | +must 35–50 人日、+should 60–85 人日，并扣除与 P2/P3 重叠的会话/文件工作 |
| 全 parity（含终端/预览/Git/插件/Bot Mode） | — | **28–34 周** | 不建议；等于重做一个 Electron App |

**建议切口**：维持"替代"定位但**不做全 parity**——把 §9.3 的 must + should 里除 C4（Git Review）与 C1（终端）之外的全部做完，落 **15–17 周**；终端与 Git Review 留作 P6，用"外部终端 + 资源管理器"过渡。这样文档、FAQ、发布说明都只需解释两件事：没有内置浏览器、没有内置终端。

### 9.6 未能验证的项

- **自带 App 的 UI 实机行为**：只读了源码与官方文档，没有启动 GUI 点测（按任务约束）。
- **`/api/fs*` 是否存在**：桌面文件树的数据来源未在 `hermes.ts` 的端点清单中出现，可能走 `projects.tree` 或专用宿主端点，未逐条追。
- **Git Review 的数据来源**：本地 `git` CLI 还是 gateway 侧执行，未确认（影响 C4 工作量 ±30%）。
- **语音链路**：`voice.*` RPC 与 `/api/audio/*` 两条都在，客户端直连还是 gateway 代理未确认。
- **Bot Mode / Agents / Command Center** 的 RPC 组合未逐条核对（结论是 skip，不影响工期）。
- **provider OAuth 的具体轮询协议**（`/api/providers/oauth/sessions/*`）只看到端点，未读实现。
- **`prompt.cancel`/队列编辑**的确切 admission 字段未核对；A2 的 M 可能偏乐观。

---

## 附录 A：文件级映射

| Android 文件 | 桌面归属 | 处理方式 |
|---|---|---|
| `ApiClient.kt` | `shared` | 直接复用（加 `org.json` 依赖） |
| `GatewayWs.kt` | `shared` | 直接复用（`android.util.Log` → `java.util.logging`） |
| `Formats.kt` | `shared` | `android.text.format.DateFormat` → `java.time` / `DateFormat.is24HourFormat` 等价实现 |
| `ChatViewModel.kt` | `shared/ChatEngine.kt` + `desktop/ui` | 拆状态机与 UI；`Uri`→`Path`，`Application`→`AppDirs`，`Toast`→`Notifier`，`SystemClock`→`System.nanoTime` |
| `MainActivity.kt` | `desktop/ui/*.kt` | 按页面拆分；`AndroidView(Markwon)` → markdown renderer；`BackHandler` → `Esc`/`Alt+←` |
| `SecureStore.kt` | `desktop/WinSettings.kt` | 全量重写（JSON + DPAPI），字段一一对应 |
| `UpdateManager.kt` | `desktop/Updater.kt` | 重写安装段；`Hotfix` 解析复用 |
| `Inbox.kt` / `Downloader.kt` | `shared` + `desktop` | 网络部分复用；落盘换 `Files` |
| `PcFiles.kt` | `desktop/ui/FilesScreen.kt` | 复用；`Toast`→snackbar |
| `SettingsGroups.kt` / `ModelPicker.kt` | `desktop/ui` | 基本原样 |
| `FileBubble.kt` | `desktop/ui` | `Uri`→`Path`；视频缩略图降级为图标 |
| `KeepAlive.kt` | 删除 | 托盘 + 单实例替代 |
| `ScanActivity.kt` | 删除 | 粘贴/图片/协议注册替代 |
| `L10n.kt` | `shared/Strings.kt` | 改为 `CompositionLocal` + 生成的 map |
| `res/values*/strings.xml` | `scripts/gen_strings.py` → `Strings.kt` | 脚本生成，保持 306 键同源 |

## 附录 B：依赖与许可

| 组件 | 许可 | 用途 |
|---|---|---|
| Kotlin / kotlinx.coroutines | Apache-2.0 | 语言/协程 |
| Compose Multiplatform (JetBrains) + Skiko | Apache-2.0 | UI/渲染 |
| OpenJDK (Temurin) | GPLv2 + Classpath Exception | 运行时（jlink 裁剪） |
| OkHttp / Okio | Apache-2.0 | HTTP/WS |
| `org.json:json` | Public Domain | JSON |
| JNA / jna-platform | Apache-2.0 / LGPL-2.1（双许可，选 Apache） | DPAPI、RegisterHotKey、FlashWindow |
| zxing core + javase | Apache-2.0 | 二维码解码 |
| multiplatform-markdown-renderer | Apache-2.0 | Markdown |
| Inno Setup | BSD 类（自由使用/分发） | 安装器 |
| JUnit / kotlin-test | EPL-1.0 / Apache-2.0 | 测试 |

**明确回避**：WiX Toolset（MS-RL）、WebView2（闭源）、任何商业 UI 组件库。

## 附录 C：MVP 验收清单

- [ ] 首次启动 → 粘贴 `hermes://pair?...` → 连通 `/health`，显示 profile 列表
- [ ] 打开一个桌面端创建的会话，发送消息，流式输出 + Markdown + 工具步骤折叠正常
- [ ] 停止 / steer / 排队 三个动作与手机端行为一致
- [ ] 审批弹窗能收到、能选 once/session/always/deny，PC 端立即继续
- [ ] Clarify 单选/多选/自由文本提交成功
- [ ] 模型 chip 切换后，`session.info` 回读确认（`adoptServerModel`）
- [ ] 三个标签页同时流式，互不串台；关闭标签不删 PC 会话
- [ ] True black 主题、字号 50%/100%/150%、中英切换即时生效且不重启
- [ ] 拔网/关 relay → 红色横幅 + 重连按钮；恢复后自动重连并重拉历史
- [ ] 关闭窗口 → 托盘常驻；托盘“退出”才真正退出；开机自启生效
- [ ] 微软拼音输入中文（含候选窗、组合中英文混排、粘贴）**无异常**
- [ ] `--desktop` 发布 → 旧版本检查到更新 → 静默安装 → 新版本号可见
