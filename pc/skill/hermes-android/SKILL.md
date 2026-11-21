---
name: hermes-android
description: Use when the Hermes Android app or phone relay misbehaves. Diagnose, fix, improve.
---

# Hermes Android app + phone relay

The phone app (`dev.oscar.hermes`, source: https://github.com/o12r/HermesAndroid) talks to this PC through a small
**phone relay**. The relay forwards to the Hermes gateway's desktop protocol, so phone chats are real Hermes
sessions: the same ones the desktop app shows, with streaming, slash commands, approvals and clarify questions.

```
phone app ──HTTP/WS :8643 (Bearer API_SERVER_KEY)──> hermes-phone-relay.py ──> Hermes gateway (desktop protocol, loopback)
```

## Where things are (this PC)
| What | Path |
|---|---|
| Relay | `%LOCALAPPDATA%\hermes\launchers\hermes-phone-relay.py` (run with the Hermes venv `pythonw.exe -I`) |
| Relay log | `%LOCALAPPDATA%\hermes\launchers\phone-relay.log` (never contains keys or tickets) |
| Tray app | `launchers\hermes-tray.ps1` (started by `hermes-tray.vbs`, HKCU Run `HermesAndroidTray`): green/yellow/red icon for relay / gateway / Tailscale, restarts the relay ~30 s after it dies, Windows notification on failure, menu items for restart, log, pairing QR and autostart |
| Keep-alive (fallback) | Scheduled task `Hermes_PhoneRelay` → `ensure-phone-relay.vbs` → `ensure-phone-relay.ps1` (at logon, on wake, every 5 min) |
| Key | `API_SERVER_KEY=` in `%LOCALAPPDATA%\hermes\.env`, the same value in every profile's `.env` |
| App updates | `%LOCALAPPDATA%\hermes\launchers\app-updates\hermes-android\` (`latest.json`, `app.apk`, `hotfix.json`) |
| Installer | `pc\install.cmd` in the repo (`-Uninstall` removes everything it installed; keys are kept) |

## Relay endpoints
- `GET /health`: no auth. Returns `{"status":"ok","relay":"hermes-phone-relay"}`.
- `GET /ws?profile=<p>`: desktop JSON-RPC WebSocket for that profile. The relay mints a ticket with `hermes -p <p> gateway attach --json`.
- `/api/*?profile=<p>`: the gateway's native HTTP API (sessions list, messages, rename, delete).
- `GET /app/<app-id>/{latest.json,apk,hotfix.json}`: in-app updates. The APK supports `Range`, so the app resumes broken downloads.
- `POST /fs/stat|mkdir {"path": "<absolute>"}`: the app checks a new project's folder, and creates it after the user confirms.
- Everything except `/health` needs `Authorization: Bearer <API_SERVER_KEY>`. A wrong key returns 401.

## Diagnose (in this order)
1. **Is the relay up?** `curl http://127.0.0.1:8643/health`. If not: `Start-ScheduledTask Hermes_PhoneRelay`, wait 10 s, and check again. Still down: read the end of `phone-relay.log`.
2. **Is the gateway up?** `hermes gateway status`. If the relay returns 503 or the WebSocket closes at once, run `hermes gateway ensure`.
3. **Can the phone reach the PC?** Check `tailscale status` (both devices online), or that both are on the same Wi-Fi with the LAN address set in the app. Test from the PC on the Tailscale address: `curl http://<tailscale-ip>:8643/health`.
4. **Auth:** `curl -H "Authorization: Bearer $KEY" "http://127.0.0.1:8643/api/sessions?profile=default&limit=1"` should return 200. A 401 means the app's key differs from the default `.env` key. Re-pair with `pc\install.cmd -PairOnly`.
5. **A profile fails, the others work:** that profile's `.env` lacks `API_SERVER_KEY`, or the profile folder name differs from what the app shows. The app reads `profiles.list`, so pull down to refresh.
6. **App-side logs:** `adb logcat -s HermesRpc`. It logs only RPC method names and error reasons, never message content.

## Known behaviours / pitfalls
- RPC calls that change a turn need fresh fencing values: `session.interrupt`, `session.steer` and `*.respond` need the running `execution_generation`, and `session.mutate` needs `expected_revision` and `expected_generation`. A `stale_generation` or `revision_conflict` error means refresh with `session.resume {omit_messages:true}` and retry once.
- `/compress` and `/branch` are `session.mutate` operations, not `slash.exec`. Moving a session to another project is `operation: "workspace"` with a `cwd`.
- A multi-select clarify answer is a JSON array string of the chosen labels.
- Hermes stores gateway notices as user rows (`[IMPORTANT: Background process…`, `[INTERNAL NOTIFICATION…`). The app hides them.
- `hermes update` can change the desktop protocol. After an update, if the app breaks, compare against `apps/shared/src/gateway-contract.openrpc.json` and `apps/desktop/src/api/canonical-protocol.ts` in the hermes-agent checkout.
- Windows hibernate kills sockets. The task restarts the relay on wake, and the app reconnects on its own (backoff up to 60 s).

## Improve / release the app
- Build: `cd <repo> && set JAVA_HOME=<jdk21> && gradlew assembleDebug`. The app is Kotlin + Jetpack Compose, mostly in `app/src/main/java/dev/oscar/hermes/`:
  - `ChatViewModel.kt`: per-chat state and event routing.
  - `GatewayWs.kt`: the JSON-RPC client.
  - `ApiClient.kt`: HTTP calls, plus history parsing into steps.
  - `MainActivity.kt`: the UI.
- Every user-facing string goes in both `res/values/strings.xml` and `res/values-zh-rCN/strings.xml`.
- Release: `python scripts/release.py --notes "..." [--github]` bumps `version.properties`, builds, and publishes to the PC update folder (and GitHub). Phones update from Settings → Check for updates.
- Hotfix without a release: edit `hotfix.json` (banner text, `min_version_code`, feature flags), then `python scripts/release.py --hotfix`.
- Updates must be signed with the same key as the installed app: the debug keystore of the PC that built it.

## Sending a file to the phone

When the user asks you to send, share or "put on my phone" a file (a report, image, export, log…), put it in the phone's Inbox:

1. Run the sender (it is in the launchers folder; stdlib only, no key needed):
   `python -I "%LOCALAPPDATA%\hermes\launchers\send-to-phone.py" "<absolute path to file>" [--name "nice name.ext"]`
2. It prints the stored name. Tell the user: "Sent to your phone's Inbox: <name>. Open Hermes → Inbox icon (top bar) → Save; it lands in Downloads."
3. Limits: files up to 500 MB; a file that doesn't exist makes the command exit with an error. Re-run after fixing the path.

Rules:
- Send a copy, never the original: the sender copies the file, so the original stays where it is.
- Only send files the user asked for. Don't send keys, `.env` files or anything under `%LOCALAPPDATA%\hermes` unless the user explicitly asks.
- The Inbox entry is removed from the PC once the phone saves it. Files never sent stay in `launchers\phone-outbox` until saved.
- If the phone isn't connected, the file just waits in the outbox; nothing is lost.

Verify it works (optional): `curl -H "Authorization: Bearer <key>" http://127.0.0.1:8643/outbox` lists the waiting files (the key is in `%LOCALAPPDATA%\hermes\.env`; never print it).
