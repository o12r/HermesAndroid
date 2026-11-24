# Hermes agent virtual screen — CLI

Open-source IddCx virtual monitor ([VirtualDrivers/Virtual-Display-Driver](https://github.com/VirtualDrivers/Virtual-Display-Driver), MIT). Every command prints JSON.

```powershell
$VD = "$env:LOCALAPPDATA\hermes\launchers\hermes-vdisplay.py"
$PY = "$env:LOCALAPPDATA\hermes\hermes-agent\venv\Scripts\python.exe"
& $PY -I $VD status
```

## Driver

| Command | Effect |
|---|---|
| `powershell -ExecutionPolicy Bypass -File install-vdd.ps1` | Install the driver and register the scheduled task `HermesVDisplayGuard` that starts the daemon elevated (one UAC prompt) |
| `powershell -ExecutionPolicy Bypass -File install-vdd.ps1 -Uninstall` | Remove the daemon task; remove the SudoVDA driver only if Hermes installed it (marker `sudovda-owned.json`, written on install when no SudoVDA device existed). Without the marker the driver is kept and a warning is printed; `-Uninstall -Force` removes it anyway. The old MttVDD cleanup runs only when Hermes-legacy evidence exists |
| `install-vdd.ps1 -Width 2560 -Height 1440` | Install with a fixed resolution (the default is the best mode available) |

## Two independent switches

| Command | Effect |
|---|---|
| `status` | `agent` (agent screen on?), `screen_off`, geometry; legacy `mode` = off / extend / second-only |
| `on` / `off` | Agent screen (1st virtual monitor) on, to the right of the other screens (the daemon starts on demand) / off |
| `screen off` | Main screen goes dark; your windows move to the user screen (2nd virtual monitor). Any real key or mouse input turns it back on |
| `screen on` | Main screen back, windows restored to where they were, user screen detached |

Modes are picked to keep the GPU cost low (nobody watches these screens): the agent screen is 1280×800 at 30 Hz,
the user screen uses the main screen's real size at 30 Hz (`AGENT_MODE` / `USER_HZ` in `hermes-vdisplay.py`).
`install-vdd.ps1` adds those modes to `vdd_settings.xml` and restarts the device; if a mode is missing, the
closest size and refresh are used.

The two switches don't depend on each other. `screen off` works with the agent screen on or off, and never puts your
windows on the agent screen. `on`/`off` don't change the screen-off state (`off` is refused when the agent screen is
the only screen on). Each monitor is attached on its own through SetDisplayConfig. `mode second-only` / `mode extend`
still work as aliases for `screen off` / `screen on`. If an install only has one virtual monitor, `screen off`
needs `install-vdd.ps1` to be run again.

`agent on` also works while the screen is off (the new agent monitor is staged next to the screen-off stand-in). When
an agent screen is turned off or removed, its windows move to the main screen (minimized ones stranded at -32000 are
rescued too). `status` / `agent on` / `shot` first clean up stale screen-off state (stand-ins left over after the
screen was lit by hand, dead watcher).

**Auto-cleanup and session ids:** the host closes an agent screen once its session is missing from the gateway's
live list on two checks in a row (60 s apart). A made-up `HERMES_SESSION_ID` / `HERMES_VDISPLAY_SESSION` is never
live, so its screen disappears after ~2–3 minutes — that is expected. Use a real session id (or the default
`manual`, which is never auto-closed) when testing.

## Windows and input

| Command | Effect |
|---|---|
| `windows` | List top-level windows and the display each one is on (`""` = on no screen, e.g. minimized at -32000) |
| `bg-type <win> <text>` / `bg-key <win> <key>` | Return `{ok, sent, failed, chars, target}` / `{ok, key, target}` (exit 1 if a message was not posted) |
| `launch -- <cmd> [args...]` | Start an app on the virtual screen and add its windows to the agent list |
| `move <hwnd\|title> virtual` | Move a window to the virtual screen and add it to the agent list |
| `move <hwnd\|title> main` | Move a window to the main screen (to show it to the user) and remove it from the agent list |
| `allow <hwnd\|title>` / `disallow <hwnd\|title>` | Add a window to, or remove it from, the agent list without moving it |
| `bg-click <hwnd> <x> <y>` | Click inside a window without taking focus or the cursor (PostMessage/UIA; real input only as a fallback) |

## Daemon duties (while a virtual screen is on)

- Moves any window that isn't on the agent list, including ones the user drags over, back to the main screen without activating it. Minimized windows stay minimized.
- Keeps the user's mouse off the virtual screen. It lets the mouse through only while the agent is using real-input fallback clicks.
- Runs elevated through the `HermesVDisplayGuard` task (command `_daemon`), so it can also move admin windows.
- State files: `%LOCALAPPDATA%\hermes\vdisplay\` (`daemon.json`, `state.json`, `agent-windows.json`, `agent-input`, `layout.json`).

## Other ways to switch modes

- **Tray:** right-click → Agent virtual screen → Agent screen on / off · Screen off / on · Install driver…
- **Phone relay (needs the API key):** `POST http://<pc>:8643/vdisplay/{status|on|off|screen-off|screen-on}` with the header `Authorization: Bearer <API_SERVER_KEY>`
- **Phone app:** Settings → PC agent screen

## Examples

```powershell
& $PY -I $VD on                           # agent screen on
& $PY -I $VD launch -- notepad.exe        # agent works here
& $PY -I $VD move "Notepad" main          # show the result to the user
& $PY -I $VD screen off                   # main screen off, your windows on the user screen
& $PY -I $VD screen on                    # main screen back, windows restored
& $PY -I $VD off                          # agent screen off
```

## Architecture (API 2: single resident daemon)

One daemon (`hermes-vdisplay.py _daemon`, started on demand by the first `on` / `screen off`, exits when nothing is wanted) owns every SudoVDA monitor: screen-off stand-ins and per-session agent screens. The CLI is a thin client over a 127.0.0.1 socket (port + token in `%LOCALAPPDATA%\hermes\vdisplay\daemon.json`); the daemon writes `state.json` after every change and is the single source of truth (status is never inferred from the display topology).

- **Screen off and agent screen are independent**: toggling one never changes the other. After every monitor add/remove the daemon re-asserts screen-off.
- `_daemon` is the only internal command (the tray starts it with `python -I hermes-vdisplay.py _daemon` when `state.json` says an agent screen or screen-off is wanted but no daemon runs). **Upgrading from an older version: re-run `install-vdd.ps1` once** (re-registers the task with `_daemon`; the old `_guard` command line is still accepted until then).
- Compatible callers: relay `/vdisplay/{status,on,off,screen-off,screen-on,shot}` and `screens` use only commands unchanged in API 2.
- A session's agent screen is reclaimed once it is missing from the gateway live list on two consecutive 60 s polls.
- Logs: `timing.log` (daemon steps), `error.log` (all tracebacks).
- **Elevation:** `install-vdd.ps1` (run in an elevated shell, one UAC prompt) registers the scheduled task `HermesVDisplayGuard` (RunLevel Highest, interactive logon). Afterwards the CLI starts the daemon with `schtasks /Run`, so there is no UAC prompt per start and the daemon can move windows of elevated apps (UIPI). `hermes-vdisplay install-task` re-registers only the task. If the task is missing (or fails to start), the CLI falls back to an unelevated daemon and prints a warning to stderr. `status` reports `elevated` (true when the running daemon has admin rights). `HERMES_VDISPLAY_NO_TASK=1` forces an unelevated daemon (debugging).
- **Independence rule:** screen off and agent screen never change each other's state; `agent on/off` never alters screen-off and `screen off` never puts user windows on the agent screen.
- **API version 2** (`API = 2`, reported as `api` in `status` / `state.json`): single resident daemon, CLI as thin client.
- **Detach fallback:** when detaching a physical screen via `ChangeDisplaySettingsEx` is refused (typical while an agent screen exists), the daemon uses `_ccd_detach`, i.e. `SetDisplayConfig` with the active paths minus the target (saved to the database).
- **Restore fallback:** if re-attaching the physical screens in `to_extend` fails or they do not attach after staging, the daemon runs `DisplaySwitch.exe /extend` (also used by `_stage_new` when Windows refuses to attach a monitor in a single-screen topology).
- **Kick monitor:** the first SudoVDA monitor added while the screen is off can stay hidden (swallowed) until another monitor is added; if a new monitor is not visible after 3 s the daemon adds a second "Hermes Kick" monitor to flush it, then removes it with the rest (about 3 s extra).
- **Tray:** the tray's taskbar auto-hide repair asks `SHAppBarMessage(ABM_GETSTATE)` first and does nothing when auto-hide is already set; it also never acts while the daemon is busy (avoids the "A toolbar is already hidden on this side of your screen" dialog).
- Known limitation: while the screen is off the first SudoVDA add stays hidden until a second ("kick") monitor is added; the daemon does this automatically (about 3 s extra).
