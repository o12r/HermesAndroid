---
name: hermes-agent-screen
description: Use when GUI work should not take the user's screen. Agent virtual screen, screen off.
---

# Agent virtual screen (work without taking the user's screen)

An open-source virtual monitor driver (SudoVDA, MIT; never Parsec) gives the agent its own screen. CLI (JSON output):
`python -I "%LOCALAPPDATA%\hermes\launchers\hermes-vdisplay.py" <cmd>`

**Design:** one resident daemon (`_daemon`, started on demand, exits when nothing is wanted) owns every virtual monitor.
It runs elevated through the scheduled task `HermesVDisplayGuard` (so it can also move admin windows), and the CLI is a
thin client over a localhost socket. It writes `%LOCALAPPDATA%\hermes\vdisplay\state.json` after every change, and
`status` comes from that file. Driver install/uninstall: done by `install.cmd` / `install.cmd -Uninstall`, or run `install-vdd.ps1 [-Uninstall]`
(one UAC prompt; it also registers/removes the task). Phone elevation cannot run it: driver changes need a UAC click.

**Two independent switches:** `agent screen` (on/off) and `screen off` (on/off). Toggling one never changes the other.

| Command | Effect |
|---|---|
| `status` | installed / `agent` / `screen_off` / `agents` / geometry / daemon (`elevated`) |
| `on` / `off` | this session's agent screen on (right of the other screens) / off. Screen-off state is not touched |
| `screen off` / `screen on` | physical screens dark + user windows moved to stand-in user screens / restored. Works with the agent screen on or off; real input also turns it back on |
| `launch -- <cmd...>` | start an app on the agent screen (registered as an agent window), without focus |
| `move <hwnd\|title> virtual\|main` | move a window; `virtual` registers it, `main` unregisters it and shows it to the user |
| `windows` | list top-level windows with their display |
| `shot [out.png]` | screenshot of the agent screen (prints the path) |
| `screens` | every screen (what the phone screen manager shows) |
| `bg-click` / `bg-type` / `bg-key` | background input to a window without focus or cursor (real input only as fallback `click` / `type`) |

Phone app: Settings -> PC agent screen (relay `POST /vdisplay/status|on|off|screen-off|screen-on`).

Rules for the agent:
- Do GUI work on the agent screen: `on`, then `launch` the app there. Never steal the user's focus or cursor; use
  `bg-click` / `bg-type` / UIA first.
- Only windows on the agent list may stay there. The daemon moves every other window (including ones the user drags
  over) back to the main screen without activating it, and keeps the user's mouse off the agent screen.
- **When the user needs to see something, bring it to them:** `move <window> main`.
- Turn it `off` when the task is done, unless the user asked to keep it.
- `screen off` blanks the user's screens: only use it when the user asked for it. Their windows go to the user screen
  (never the agent screen); `screen on` or any real input puts them back.
- Screens of sessions missing from the gateway's live list are reclaimed after ~2 minutes; a made-up session id is
  never live. Details: `launchers\hermes-vdisplay.py` docstring, repo `pc/vdisplay/README.md`.
