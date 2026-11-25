# Hermes Android client

A native Kotlin + Jetpack Compose chat app for the Hermes Agent on the PC, reached over Tailscale.
The text field is a native Android one, so Gboard and 微信输入法 work.

The app speaks the **same protocol as the Hermes desktop app**, through a small relay on the PC.
So desktop sessions, real Hermes slash commands, approvals and questions all work. Hermes itself is unmodified.

```
phone ──Tailscale──> PC relay 100.92.121.115:8643 ──loopback──> Hermes gateway
                     (checks API key, mints single-use        /api/ws   (JSON-RPC, hermes-gateway-v1)
                      gateway tickets)                         /api/*    (native HTTP: sessions, messages)
```

## PC side
- Relay: `%LOCALAPPDATA%\hermes\launchers\hermes-phone-relay.py`
  - Run it with the Hermes venv python.
  - Log: `launchers\phone-relay.log`. Keys and tickets are never logged.
  - Auth: `Authorization: Bearer <API_SERVER_KEY>`. The key is read from the default profile's `.env`, and the same key is in every profile's `.env`.
  - Routes:
    - `GET /health`
    - `GET /ws?profile=<p>`: WebSocket. The relay runs `hermes -p <p> gateway attach --json` and pipes the frames through.
    - `/api/*?profile=<p>`: HTTP forward with a fresh `x-hermes-gateway-ticket` per request, minted by one long-lived `hermes -p default gateway tickets --serve`.
  - If the Tailscale IP can't be bound yet (boot, or resume from sleep), it retries every 30 s.
- Startup: the Start menu **Hermes** shortcut runs `launchers\hermes-with-gateway.vbs`, which runs `hermes-with-gateway.ps1`. That opens the desktop app, runs `hermes gateway ensure`, and starts the relay if :8643 isn't listening.
- The api_server platform is **off** (`API_SERVER_ENABLED=false` in the default `.env`; backup `.env.bak-relayonly`). The `API_SERVER_KEY` lines stay; they are now just the relay password.
- Check: `curl http://100.92.121.115:8643/health`

## Build
```bash
cd C:/Users/oscar/Projects/hermes-client/HermesAndroid
timeout 1200 ./gradlew assembleDebug --console=plain > build.log 2>&1
```
- Toolchain: JDK `C:\Java\jdk21` (set in gradle.properties) and SDK `C:\Android\Sdk` (set in local.properties).
- Versions: AGP 9.5.1, Gradle 9.9.1, Kotlin compose plugin 2.4.21, Compose BOM 2026.11.01.
- APK: `app/build/outputs/apk/debug/app-debug.apk` (debug-signed, minSdk 29, about 65 MB).
- Install: `adb install -r app/build/outputs/apk/debug/app-debug.apk`, or copy the APK to the phone and sideload it.

## One-click setup (PC)
Requirements: a Windows PC with Hermes Agent installed, and Tailscale on both the PC and the phone (or both on the same Wi-Fi).

Double-click **`pc\install.cmd`**. It:
1. reuses or creates `API_SERVER_KEY` and writes it to every profile's `.env` (backups: `.env.bak-android`);
2. installs the phone relay and the `Hermes_PhoneRelay` scheduled task (logon, wake, every 5 min), starts it and checks it;
3. sets up the in-app update folder with the latest APK;
4. installs a **tray icon** (green/yellow/red for relay, gateway and Tailscale): it restarts the relay if it dies, warns with a notification, and has menu items for restart, log, pairing QR and start-with-Windows;
5. installs the `hermes-android` skill into every Hermes profile, so your local Hermes knows how to use, fix and improve this setup;
6. pairs the phone:
   - **USB / adb connected:** installs the app and pairs it. Nothing to type on the phone.
   - **Otherwise:** opens a page with two QR codes. ① Download the app. ② Scan to pair (opens in Hermes and fills in the address and key).

Other modes: `install.cmd -PairOnly` (pair another phone), `-NoAdb` (always show the QR page), `-Uninstall` (removes everything it installed; the keys in `.env` stay).

## Releasing an update
The version lives in `version.properties`. `scripts/release.py` bumps it, builds, and publishes.
```bash
python scripts/release.py --notes "What changed"           # bump + build + publish to the PC relay
python scripts/release.py --notes "What changed" --github  # also create GitHub release v<name> with the APK
python scripts/release.py --hotfix                         # publish hotfix.json to the PC (commit + push it for GitHub)
```
- PC files go to `%LOCALAPPDATA%\hermes\launchers\app-updates\hermes-android\` (`latest.json`, `app.apk`, `hotfix.json`).
- The relay serves them to any authorised app at `/app/<app-id>/{latest.json,apk,hotfix.json}`.
- Updates must be signed with the same key as the installed app. Debug builds use this PC's `~/.android/debug.keystore`.

## First run
In the Settings screen:
- **Server URL (PC relay)**: `http://100.92.121.115:8643`
- **API key**: copy it to the PC clipboard without displaying it:
  `grep '^API_SERVER_KEY=' %LOCALAPPDATA%/hermes/.env | cut -d= -f2- | tr -d '\r\n' | clip.exe`

The key is encrypted with a non-exportable Android Keystore AES-GCM key and is never logged.

## Features
- **Sessions per profile** (profile chip): every session, including ones started on the desktop or in the TUI.
  - Open/resume, rename, delete (fenced via `mutation-snapshot`), new chat.
- **Streaming replies** over WebSocket (`prompt.submit` → `message.delta` / `message.complete`), with markdown and a tool/thinking status line. **Stop** sends `session.interrupt`.
- **Model / Thinking chips**: the list comes from `model.options` (authenticated providers only). Both are saved per profile and sent with each prompt as `model_override`.
- **Slash commands**, with autocomplete when you type `/`:
  - Phone-side: `/new`, `/model`, `/think`, `/retry`, `/stop`, `/profile`, `/sessions`, `/delete confirm`, `/help`.
  - Everything else runs on Hermes via `slash.exec`, including `/usage`, `/title`, `/compress` and `/<skill>`. The list comes from `commands.catalog`.
- **Approvals**: dialog with the choices Hermes offers (once / session / always / deny).
- **Clarify**: Hermes's questions appear as a dialog with choices or free text.
- **Attachments**:
  - Images are downscaled to ≤1600 px JPEG and sent with `image.attach_bytes`.
  - Text files ≤200 KB are inlined as text.
- **Theme**: System / Light / Dark / **True black** (AMOLED: pure #000000 backgrounds). Accents come from Material You.
- **Language**: System / English / 中文, as an in-app switch. All UI text is in `res/values` and `res/values-zh-rCN`.
- **In-app updates**: checked on launch/resume (at most every 6 h) and from Settings → Check for updates.
  1. The app looks on the PC relay first (`/app/hermes-android/latest.json`), then GitHub Releases of `o12r/HermesAndroid`.
  2. It downloads the APK and hands it to Android's installer.
  - The first update asks once for "Install unknown apps" for Hermes.
- **Hotfix config** (`hotfix.json`, no reinstall): fetched on every launch/resume, PC first, then GitHub `main`.
  - Fields: `banner.en` / `banner.zh` (info banner), `min_version_code` (forces an update below it), `relay_url` (default-relay override), `features.attachments` / `features.server_slash`.
- Reconnects on network regain and app resume. If the PC is unreachable, a red banner says so, with a Retry button.
- Cleartext HTTP is allowed only to `100.92.121.115` / `*.ts.net` (network security config). The code also refuses `http://` outside 100.64.0.0/10.

## Caveats
- The desktop protocol is internal to Hermes. A `hermes update` can change it; if the app breaks after an update, check the relay log first.
- Don't type into the same session on the desktop and the phone at the same time.
- Chats started on the phone are stored as desktop (`local`) sessions.
- A reply interrupted by a network drop is lost on the phone; after reconnecting, the app reloads the history from the PC.
