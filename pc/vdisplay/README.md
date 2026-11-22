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
| `powershell -ExecutionPolicy Bypass -File install-vdd.ps1` | Install the driver and register the elevated guard task `HermesVDisplayGuard` (one UAC prompt) |
| `powershell -ExecutionPolicy Bypass -File install-vdd.ps1 -Uninstall` | Remove the driver and the guard task |
| `install-vdd.ps1 -Width 2560 -Height 1440` | Install with a fixed resolution (the default is the best mode available) |

## Modes

| Command | Mode | Effect |
|---|---|---|
| `status` | — | Shows installed / attached / mode / geometry |
| `on` | extend | Same as `mode extend` |
| `mode extend` | extend | The virtual screen sits to the right of the main screen; starts the guard |
| `mode second-only` | second display only | Only the virtual screen stays on; the main screen goes dark |
| `off` | off | Detaches the virtual screen and puts the main-screen windows back where they were |

Switching from `second-only` back to `extend` or `off` also restores every window that was on the main screen.

## Windows and input

| Command | Effect |
|---|---|
| `windows` | List top-level windows and the display each one is on |
| `launch -- <cmd> [args...]` | Start an app on the virtual screen and add its windows to the agent list |
| `move <hwnd\|title> virtual` | Move a window to the virtual screen and add it to the agent list |
| `move <hwnd\|title> main` | Move a window to the main screen (to show it to the user) and remove it from the agent list |
| `allow <hwnd\|title>` / `disallow <hwnd\|title>` | Add a window to, or remove it from, the agent list without moving it |
| `bg-click <hwnd> <x> <y>` | Click inside a window without taking focus or the cursor (PostMessage/UIA; real input only as a fallback) |

## Guard (starts automatically while the virtual screen is on)

- Moves any window that isn't on the agent list, including ones the user drags over, back to the main screen without activating it. Minimized windows stay minimized.
- Keeps the user's mouse off the virtual screen. It lets the mouse through only while the agent is using real-input fallback clicks.
- Runs elevated through the `HermesVDisplayGuard` task, so it can also move admin windows.
- State files: `%LOCALAPPDATA%\hermes\vdisplay\` (`guard.pid`, `agent-windows.json`, `agent-input`, layout).

## Other ways to switch modes

- **Tray:** right-click → Agent virtual screen → On (extend) / Second screen only / Off / Install driver…
- **Phone relay (needs the API key):** `POST http://<pc>:8643/vdisplay/{status|on|off|extend|second-only}` with the header `Authorization: Bearer <API_SERVER_KEY>`
- **Phone app:** Settings → PC agent screen

## Examples

```powershell
& $PY -I $VD mode extend                  # turn on, extend mode
& $PY -I $VD launch -- notepad.exe        # agent works here
& $PY -I $VD move "Notepad" main          # show the result to the user
& $PY -I $VD mode second-only             # main screen off
& $PY -I $VD off                          # done; windows restored
```
