"""hermes-vdisplay: per-session agent screens for Hermes on the open-source SudoVDA driver (SudoMaker, MIT).

The agent opens and drives windows on its own virtual monitor so the user's focus on the main screen is never
stolen. Virtual monitors exist only while needed: an agent screen from `agent on` until `agent off` (or until its
session is open on neither the phone nor the PC), the screen-off monitors only while the screen is off.

  status                         JSON: installed / attached / mode / display bounds
  install | uninstall            install-vdd.ps1 (one UAC prompt)
  on | off  (agent on|off)       this session's agent screen on / off (session = $HERMES_SESSION_ID, or
                                 $HERMES_VDISPLAY_SESSION, else "manual"); every session gets its own screen
  agents                         JSON: open agent screens {session: display}
  screen off | screen on         physical screens go dark; one screen-off monitor per physical screen takes its
                                 windows; real keyboard/mouse input turns them back on. Legacy: mode ...
  windows                        visible top-level windows: hwnd, pid, display, title
  launch -- <cmd> [args]         start a program and put its windows on the virtual display, without focus
  move <win> virtual|main        move a window (no activation for virtual)
  show <win>                     bring a window to the main screen for the user (wakes it if needed)
  bg-click <win> X Y [right]     background click via window messages, X/Y in screen coordinates
  bg-type <win> <text>           background typing via WM_CHAR
  bg-key <win> <VK>              background key press (virtual-key code or name: enter, tab, esc ...)
  click X Y [right]              fallback: real mouse click, then the user's foreground window + cursor are restored
                                 (real input is serialized across sessions by a global lock)
  type <win> <text>              fallback: real keyboard input to the window, then focus is restored
  shot [out.png]                 screenshot of the virtual display (prints the path)

<win> = hwnd (decimal or 0x...), pid:<n>, or a case-insensitive title substring.
Stdlib + ctypes only (Pillow only for `shot`). Windows 10/11.
"""
import ctypes
import json
import os
import subprocess
import sys
import time
from ctypes import wintypes as W
from pathlib import Path

if sys.platform != "win32":
    sys.exit("hermes-vdisplay runs on Windows only")

u32 = ctypes.WinDLL("user32", use_last_error=True)
k32 = ctypes.WinDLL("kernel32", use_last_error=True)
try:
    u32.SetProcessDpiAwarenessContext(ctypes.c_void_p(-4))   # per-monitor v2: real pixel coordinates
except Exception:
    pass

HERE = Path(__file__).resolve().parent
STATE = Path(os.environ.get("LOCALAPPDATA", str(Path.home()))) / "hermes" / "vdisplay"
STATE.mkdir(parents=True, exist_ok=True)
LAYOUT = STATE / "layout.json"       # physical layout saved before second-only
WATCH_PID = STATE / "watch.pid"
GUARD_PID = STATE / "guard.pid"
GUARD_TASK = "HermesVDisplayGuard"
AGENT_WINS = STATE / "agent-windows.json"   # windows the agent put on the virtual screen; everything else is moved off
SVDA_NAMES = ("sudomaker virtual display adapter",)   # SudoVDA: every Hermes monitor (agent + screen-off)
AGENT_REQ = STATE / "agent-requests.json"   # {session: {"home": HERMES_HOME, "t": time}} wanted agent screens
AGENT_SCREENS = STATE / "agent-screens.json"   # {session: display name}, written by the host that owns them
HOST_PID = STATE / "host.pid"
INPUT_LOCK = STATE / "real-input.lock"   # one session at a time may drive the real mouse/keyboard
SCREEN_OFF = STATE / "screen-off.json"    # written by the watcher once the screen-off monitors are up (or failed)


# ---------------------------------------------------------------- display configuration
class DISPLAY_DEVICEW(ctypes.Structure):
    _fields_ = [("cb", W.DWORD), ("DeviceName", W.WCHAR * 32), ("DeviceString", W.WCHAR * 128),
                ("StateFlags", W.DWORD), ("DeviceID", W.WCHAR * 128), ("DeviceKey", W.WCHAR * 128)]


class DEVMODEW(ctypes.Structure):
    _fields_ = [("dmDeviceName", W.WCHAR * 32), ("dmSpecVersion", W.WORD), ("dmDriverVersion", W.WORD),
                ("dmSize", W.WORD), ("dmDriverExtra", W.WORD), ("dmFields", W.DWORD),
                ("dmPositionX", W.LONG), ("dmPositionY", W.LONG), ("dmDisplayOrientation", W.DWORD),
                ("dmDisplayFixedOutput", W.DWORD), ("dmColor", W.SHORT), ("dmDuplex", W.SHORT),
                ("dmYResolution", W.SHORT), ("dmTTOption", W.SHORT), ("dmCollate", W.SHORT),
                ("dmFormName", W.WCHAR * 32), ("dmLogPixels", W.WORD), ("dmBitsPerPel", W.DWORD),
                ("dmPelsWidth", W.DWORD), ("dmPelsHeight", W.DWORD), ("dmDisplayFlags", W.DWORD),
                ("dmDisplayFrequency", W.DWORD), ("dmICMMethod", W.DWORD), ("dmICMIntent", W.DWORD),
                ("dmMediaType", W.DWORD), ("dmDitherType", W.DWORD), ("dmReserved1", W.DWORD),
                ("dmReserved2", W.DWORD), ("dmPanningWidth", W.DWORD), ("dmPanningHeight", W.DWORD)]


ATTACHED, PRIMARY = 0x1, 0x4
DM_POSITION, DM_PELSWIDTH, DM_PELSHEIGHT, DM_FREQ = 0x20, 0x80000, 0x100000, 0x400000
CDS_UPDATEREGISTRY, CDS_SET_PRIMARY, CDS_NORESET = 0x1, 0x10, 0x10000000
ENUM_CURRENT, ENUM_REGISTRY = 0xFFFFFFFF, 0xFFFFFFFE
u32.EnumDisplaySettingsW.argtypes = [W.LPCWSTR, W.DWORD, ctypes.POINTER(DEVMODEW)]
u32.ChangeDisplaySettingsExW.argtypes = [W.LPCWSTR, ctypes.POINTER(DEVMODEW), W.HWND, W.DWORD, W.LPVOID]


def _jload(f, default):
    try:
        return json.loads(f.read_text(encoding="utf-8"))
    except Exception:
        return default


def _sid():
    return os.environ.get("HERMES_VDISPLAY_SESSION") or os.environ.get("HERMES_SESSION_ID") or "manual"


def displays():
    out, i = [], 0
    agents = set(_jload(AGENT_SCREENS, {}).values())
    while True:
        d = DISPLAY_DEVICEW(); d.cb = ctypes.sizeof(d)
        if not u32.EnumDisplayDevicesW(None, i, ctypes.byref(d), 0):
            return out
        i += 1
        if "mirror" in d.DeviceString.lower():
            continue
        m = DEVMODEW(); m.dmSize = ctypes.sizeof(m)
        att = bool(d.StateFlags & ATTACHED)
        ok = u32.EnumDisplaySettingsW(d.DeviceName, ENUM_CURRENT if att else ENUM_REGISTRY, ctypes.byref(m))
        out.append({"name": d.DeviceName, "adapter": d.DeviceString, "attached": att,
                    "primary": bool(d.StateFlags & PRIMARY),
                    "virtual": d.DeviceName in agents,
                    "x": m.dmPositionX if ok else 0, "y": m.dmPositionY if ok else 0,
                    "w": m.dmPelsWidth if ok and att else 0, "h": m.dmPelsHeight if ok and att else 0,
                    "hz": m.dmDisplayFrequency if ok else 0})


def vdds():
    """Every attached agent screen (all sessions)."""
    return [d for d in displays() if d["virtual"] and d["attached"]]


def vdd(sid=None):
    """This session's agent screen (attached), or None."""
    name = _jload(AGENT_SCREENS, {}).get(sid or _sid())
    return next((d for d in displays() if d["name"] == name and d["attached"]), None) if name else None


def svdas():
    """Screen-off monitors (SudoVDA): exist only while the screen is off, one per physical screen."""
    return [d for d in displays() if d["adapter"].strip().lower() in SVDA_NAMES and d["attached"]
            and not d["virtual"]]


def vuser():
    """Primary screen-off monitor (takes the user's main-screen windows), or None when the screen is on."""
    s = svdas()
    return next((d for d in s if d["primary"]), s[0] if s else None)


def physical():
    # Other virtual adapters (Parsec, SudoVDA screen-off monitors) are left out.
    return [d for d in displays() if not d["virtual"] and "parsec" not in d["adapter"].lower()
            and d["adapter"].strip().lower() not in SVDA_NAMES]


# ---------------------------------------------------------------- SudoVDA (on-demand screen-off monitors)
class _GUID(ctypes.Structure):
    _fields_ = [("b", ctypes.c_ubyte * 16)]


class _SPDID(ctypes.Structure):
    _fields_ = [("cbSize", W.DWORD), ("guid", _GUID), ("flags", W.DWORD), ("res", ctypes.c_size_t)]


_sa = ctypes.WinDLL("setupapi", use_last_error=True)
_sa.SetupDiGetClassDevsW.restype = ctypes.c_void_p
_sa.SetupDiGetClassDevsW.argtypes = [ctypes.POINTER(_GUID), W.LPCWSTR, W.HWND, W.DWORD]
_sa.SetupDiEnumDeviceInterfaces.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.POINTER(_GUID), W.DWORD,
                                            ctypes.POINTER(_SPDID)]
_sa.SetupDiGetDeviceInterfaceDetailW.argtypes = [ctypes.c_void_p, ctypes.POINTER(_SPDID), ctypes.c_void_p, W.DWORD,
                                                 ctypes.POINTER(W.DWORD), ctypes.c_void_p]
_sa.SetupDiDestroyDeviceInfoList.argtypes = [ctypes.c_void_p]
k32.CreateFileW.restype = ctypes.c_void_p
k32.CreateFileW.argtypes = [W.LPCWSTR, W.DWORD, W.DWORD, ctypes.c_void_p, W.DWORD, W.DWORD, ctypes.c_void_p]
k32.DeviceIoControl.argtypes = [ctypes.c_void_p, W.DWORD, ctypes.c_void_p, W.DWORD, ctypes.c_void_p, W.DWORD,
                                ctypes.POINTER(W.DWORD), ctypes.c_void_p]
k32.CloseHandle.argtypes = [ctypes.c_void_p]
SVDA_IFACE = "e5bcc234-1e0c-418a-a0d4-ef8b7501414d"
SVDA_ADD, SVDA_REMOVE, SVDA_PING = (0x22 << 16) | (0x800 << 2), (0x22 << 16) | (0x801 << 2), (0x22 << 16) | (0x888 << 2)


def _svda_guid(i):
    import uuid   # stable per index, so Windows remembers each monitor's settings
    return uuid.uuid5(uuid.NAMESPACE_URL, f"hermes-vdisplay/screen-off/{i}")


def _svda_open():
    """Handle to the SudoVDA control interface, or None if the driver is not installed."""
    import uuid
    g = _GUID(); ctypes.memmove(g.b, uuid.UUID(SVDA_IFACE).bytes_le, 16)
    hs = _sa.SetupDiGetClassDevsW(ctypes.byref(g), None, None, 0x12)   # DIGCF_PRESENT | DIGCF_DEVICEINTERFACE
    if not hs or hs == ctypes.c_void_p(-1).value:
        return None
    try:
        d = _SPDID(); d.cbSize = ctypes.sizeof(d)
        if not _sa.SetupDiEnumDeviceInterfaces(hs, None, ctypes.byref(g), 0, ctypes.byref(d)):
            return None
        need = W.DWORD()
        _sa.SetupDiGetDeviceInterfaceDetailW(hs, ctypes.byref(d), None, 0, ctypes.byref(need), None)
        buf = ctypes.create_string_buffer(need.value)
        ctypes.c_uint32.from_buffer(buf).value = 8   # sizeof(SP_DEVICE_INTERFACE_DETAIL_DATA_W) on x64
        if not _sa.SetupDiGetDeviceInterfaceDetailW(hs, ctypes.byref(d), buf, need, None, None):
            return None
        path = ctypes.wstring_at(ctypes.addressof(buf) + 4)
    finally:
        _sa.SetupDiDestroyDeviceInfoList(hs)
    h = k32.CreateFileW(path, 0xC0000000, 3, None, 3, 0, None)
    return None if not h or h == ctypes.c_void_p(-1).value else h


def _svda_io(h, code, inb=b"", outn=0):
    ib = ctypes.create_string_buffer(inb, len(inb)) if inb else None
    ob = ctypes.create_string_buffer(outn) if outn else None
    n = W.DWORD()
    return bool(k32.DeviceIoControl(h, code, ib, len(inb), ob, outn, ctypes.byref(n), None))


def svda_installed():
    h = _svda_open()
    if h:
        k32.CloseHandle(h)
    return bool(h)


def _svda_add(h, i, w, hh, hz, guid=None, label=None, serial=None):
    import struct
    inb = struct.pack("<III", w, hh, hz) + (guid or _svda_guid(i)).bytes_le \
        + (label or f"Hermes Off {i + 1}").encode()[:13].ljust(14, b"\0") \
        + (serial or f"HRMSOFF{i + 1:04d}").encode()[:13].ljust(14, b"\0")
    return _svda_io(h, SVDA_ADD, inb, 12)


def _agent_guid(sid):
    import uuid   # stable per session, so Windows remembers the monitor
    return uuid.uuid5(uuid.NAMESPACE_URL, f"hermes-vdisplay/agent/{sid}")


def _svda_remove_all(h, n=8):
    for i in range(n):
        _svda_io(h, SVDA_REMOVE, _svda_guid(i).bytes_le)


def _stage(name, x=0, y=0, w=0, h=0, hz=0, primary=False):
    m = DEVMODEW(); m.dmSize = ctypes.sizeof(m)
    m.dmFields = DM_POSITION | DM_PELSWIDTH | DM_PELSHEIGHT | (DM_FREQ if hz else 0)
    m.dmPositionX, m.dmPositionY, m.dmPelsWidth, m.dmPelsHeight, m.dmDisplayFrequency = x, y, w, h, hz
    flags = CDS_UPDATEREGISTRY | CDS_NORESET | (CDS_SET_PRIMARY if primary else 0)
    r = u32.ChangeDisplaySettingsExW(name, ctypes.byref(m), None, flags, None)
    if r != 0:
        raise RuntimeError(f"ChangeDisplaySettingsEx({name}) failed: {r}")


def _apply():
    r = u32.ChangeDisplaySettingsExW(None, None, None, 0, None)
    if r != 0:
        raise RuntimeError(f"applying display settings failed: {r}")
    time.sleep(1.0)


def _stage_new(name, *a, **kw):
    """Stage a freshly added monitor. With a single-screen topology ("PC screen only") Windows refuses to attach
    another one (ChangeDisplaySettingsEx -1) until the topology is Extend; switch it, then retry."""
    try:
        _stage(name, *a, **kw)
    except RuntimeError:
        subprocess.run(["DisplaySwitch.exe", "/extend"], timeout=20)
        for _ in range(20):
            time.sleep(0.5)
            try:
                _stage(name, *a, **kw); return
            except RuntimeError:
                pass
        raise


# Nobody looks at the virtual screens directly, so keep them cheap on the GPU: agent screens are small and 30 Hz
# (smaller screenshots too); screen-off monitors keep their physical screen's size at 30 Hz. 30 Hz is SudoVDA's floor:
# lower rates (5-24) are refused and the monitor never attaches.
AGENT_MODE = (1280, 800, 30)
USER_HZ = 30


def _best_mode(name, want_w, want_h, want_hz=USER_HZ):
    best, i = None, 0
    while True:
        m = DEVMODEW(); m.dmSize = ctypes.sizeof(m)
        if not u32.EnumDisplaySettingsW(name, i, ctypes.byref(m)):
            break
        i += 1
        w, h, hz = m.dmPelsWidth, m.dmPelsHeight, m.dmDisplayFrequency
        # exact size wins; otherwise the largest mode that fits; ties -> refresh closest to want_hz, then the lower one
        key = ((w, h) == (want_w, want_h), w <= want_w and h <= want_h, w * h if w <= want_w and h <= want_h else -w * h,
               -abs(hz - want_hz), -hz)
        if best is None or key > best[0]:
            best = (key, (w, h, hz))
    return best[1] if best else (1920, 1080, 60)


def agent_on():
    v = vdd()
    return bool(v and v["attached"])


def screen_off():
    """Physical screens are off and the user works on the user screen (or nothing is on at all)."""
    return not any(d["attached"] for d in physical())


def mode_now():
    """Legacy summary: off | extend | second-only (screen-off wins)."""
    if screen_off():
        return "second-only"
    return "extend" if agent_on() else "off"


def _right_edge():
    """x right of every attached display except this session's agent screen (other sessions' screens included)."""
    a = vdd()
    ds = [d for d in displays() if d["attached"] and not (a and d["name"] == a["name"])
          and "parsec" not in d["adapter"].lower()]
    return (max(d["x"] + d["w"] for d in ds), min(d["y"] for d in ds)) if ds else (0, 0)


def _spawn_host():
    if _alive(HOST_PID):
        return
    exe = Path(sys.executable)
    pyw = exe.with_name("pythonw.exe") if exe.with_name("pythonw.exe").exists() else exe
    subprocess.Popen([str(pyw), "-I", str(Path(__file__).resolve()), "_host"], close_fds=True,
                     creationflags=0x00000008 | 0x00000200)
    for _ in range(20):
        time.sleep(0.25)
        if _alive(HOST_PID):
            return


def _request(sid, on):
    r = _jload(AGENT_REQ, {})
    if on:
        r[sid] = {"home": os.environ.get("HERMES_HOME", ""), "t": time.time()}
    else:
        r.pop(sid, None)
    AGENT_REQ.write_text(json.dumps(r), encoding="utf-8")


def _park_agents(right, top, skip=()):
    """Stage every agent screen in a row right of x=right (no apply)."""
    x = right
    for a in vdds():
        if a["name"] in skip:
            continue
        _stage(a["name"], x, top, a["w"], a["h"], a["hz"]); x += a["w"]


def turn_on():
    """This session's agent screen on (independent of the screen-off feature and of other sessions)."""
    sid = _sid()
    if not svda_installed():
        raise RuntimeError("SudoVDA driver not installed (run: hermes-vdisplay install)")
    if not vdd(sid):
        _request(sid, True)
        _spawn_host()
        name = None
        for _ in range(160):   # first time: host start + Windows sets the new monitor up
            time.sleep(0.25)
            name = _jload(AGENT_SCREENS, {}).get(sid)
            if name:
                break
        if not name:
            raise RuntimeError("agent screen was not created")
        # Windows may leave a new monitor detached (or cloned): place + attach it ourselves, right of the rest
        time.sleep(1.0)
        x, y = _right_edge()
        w, h, hz = _best_mode(name, *AGENT_MODE)
        _stage_new(name, x, y, w, h, hz)
        _apply()
        if not vdd(sid):
            raise RuntimeError("agent screen did not attach")
    else:
        _request(sid, True)
    _spawn_guard()


def turn_off():
    """This session's agent screen off; other sessions and the screen-off state are left as they are."""
    sid = _sid()
    _request(sid, False)
    for _ in range(40):
        if not vdd(sid):
            break
        time.sleep(0.25)
    _allow()   # prune dead entries


def _live_sessions(home):
    """Session ids live on the gateway for this HERMES_HOME, or None when it can't be asked."""
    py = Path(os.environ.get("LOCALAPPDATA", "")) / "hermes" / "hermes-agent" / "venv" / "Scripts" / "python.exe"
    if not py.exists():
        py = Path(sys.executable).with_name("python.exe")
    env = dict(os.environ, HERMES_HOME=home) if home else dict(os.environ)
    try:
        r = subprocess.run([str(py), "-I", str(HERE / "live_sessions.py"), home], capture_output=True, text=True,
                           timeout=60, env=env, creationflags=0x08000000)
        return set(json.loads(r.stdout.strip().splitlines()[-1])["sessions"]) if r.returncode == 0 else None
    except Exception:
        return None


def _host():
    """Owns the agent screens: adds/removes them to match agent-requests.json and keeps SudoVDA pinged. A session's
    screen is dropped once the session is open on neither the phone nor the PC (gone from the gateway's live list
    twice in a row). Exits, and its monitors disappear, when no agent screen is wanted any more."""
    import threading
    me = os.getpid()
    if _alive(HOST_PID) and int(HOST_PID.read_text()) != me:
        return
    HOST_PID.write_text(str(me))
    h = _svda_open()
    if not h:
        HOST_PID.unlink(missing_ok=True); return

    def keepalive():
        while True:
            _svda_io(h, SVDA_PING); time.sleep(0.5)
    threading.Thread(target=keepalive, daemon=True).start()
    have, missing, last_live = {}, {}, time.time()
    AGENT_SCREENS.write_text("{}", encoding="utf-8")
    while True:
        req = _jload(AGENT_REQ, {})
        for sid in [x for x in have if x not in req]:
            _svda_io(h, SVDA_REMOVE, _agent_guid(sid).bytes_le)
            del have[sid]; AGENT_SCREENS.write_text(json.dumps(have), encoding="utf-8")
        for sid in [x for x in req if x not in have]:
            before = {d["name"] for d in displays() if d["adapter"].strip().lower() in SVDA_NAMES and d["attached"]}
            n = len(have) + 1
            if _svda_add(h, 0, *AGENT_MODE, guid=_agent_guid(sid), label=f"Hermes Agent{n}"[:13],
                         serial=f"HRMSAGT{n:04d}"):
                for _ in range(40):
                    time.sleep(0.25)
                    new = [d["name"] for d in displays() if d["adapter"].strip().lower() in SVDA_NAMES
                           and d["attached"] and d["name"] not in before]
                    if new:
                        have[sid] = new[0]; break
            if sid not in have:   # could not create it: give up on this request instead of retrying forever
                _request(sid, False)
            AGENT_SCREENS.write_text(json.dumps(have), encoding="utf-8")
        if not req and not have:
            AGENT_SCREENS.write_text("{}", encoding="utf-8")
            HOST_PID.unlink(missing_ok=True)
            k32.CloseHandle(h)
            return
        if time.time() - last_live > 60:
            last_live = time.time()
            homes = {}
            for sid, r in req.items():
                if sid != "manual" and r.get("home"):
                    homes.setdefault(r["home"], []).append(sid)
            for home, sids in homes.items():
                live = _live_sessions(home)
                if live is None:
                    continue
                for sid in sids:
                    missing[sid] = missing.get(sid, 0) + 1 if sid not in live else 0
                    if missing[sid] >= 2:
                        _request(sid, False); missing.pop(sid, None)
        time.sleep(1)


def to_second_only():
    """Screen off: physical screens go dark. The wake watcher creates one screen-off monitor (SudoVDA) per physical
    screen, same size and position, so every window stays on its counterpart; they exist only while the screen is
    off. Independent of the agent screen, which is never used for the user's windows."""
    if screen_off():
        return
    if not svda_installed():
        raise RuntimeError("screen-off needs the SudoVDA driver (reinstall: hermes-vdisplay install)")
    phys = [d for d in physical() if d["attached"]]
    names = {d["name"] for d in phys}
    wins = [{"hwnd": w["hwnd"], "pid": w["pid"], "title": w["title"], "pl": _get_placement(w["hwnd"])}
            for w in windows() if w["display"] in names]
    LAYOUT.write_text(json.dumps({"physical": phys, "windows": wins}), encoding="utf-8")
    SCREEN_OFF.unlink(missing_ok=True)
    _spawn_watcher()   # it owns the monitors (SudoVDA drops them ~3 s after its pings stop)
    for _ in range(160):
        time.sleep(0.25)
        try:
            r = json.loads(SCREEN_OFF.read_text(encoding="utf-8"))
        except Exception:
            continue
        if not r.get("ok"):
            raise RuntimeError(r.get("error") or "screen off failed")
        _spawn_guard()
        return
    _kill_watcher()
    raise RuntimeError("screen-off monitors did not come up")


def _screen_off_setup(h):
    """In the watcher: add the screen-off monitors, map them 1:1 onto the physical screens, turn those off."""
    lay = json.loads(LAYOUT.read_text(encoding="utf-8"))
    phys = lay["physical"]
    pairs = []
    for i, p in enumerate(phys):
        before = {d["name"] for d in svdas()}
        if not _svda_add(h, i, p["w"], p["h"], USER_HZ):
            raise RuntimeError(f"SudoVDA add failed ({ctypes.get_last_error()})")
        for _ in range(40):
            time.sleep(0.25)
            new = [d for d in svdas() if d["name"] not in before]
            if new:
                pairs.append((p, new[0]["name"])); break
        else:
            raise RuntimeError("screen-off monitor did not attach")
    for p, name in pairs:
        w, hh, hz = _best_mode(name, p["w"], p["h"])
        _stage_new(name, p["x"], p["y"], w, hh, hz, primary=p["primary"])
    _park_agents(max(p["x"] + p["w"] for p in phys), min(p["y"] for p in phys))   # agent screens stay right
    for p in phys:
        _stage(p["name"])
    _apply()
    time.sleep(1.0)
    # same coordinates as before, so the saved placements put each window on its physical screen's counterpart
    for w in lay.get("windows", []):
        if u32.IsWindow(w["hwnd"]) and _pid(w["hwnd"]) == w["pid"]:
            try:
                _set_placement(w["hwnd"], w["pl"])
            except Exception:
                pass
    _to_user_screen()
    return [name for _, name in pairs]


def to_extend():
    """Screen on again: physical layout and the user's windows come back, the screen-off monitors are removed.
    The agent screen keeps its on/off state."""
    try:
        lay = json.loads(LAYOUT.read_text(encoding="utf-8"))
    except Exception:
        lay = None
    if screen_off() or svdas():
        try:
            if not lay:
                raise RuntimeError("no saved layout")
            for d in lay["physical"]:
                _stage(d["name"], d["x"], d["y"], d["w"], d["h"], d["hz"], primary=d["primary"])
            right = max(d["x"] + d["w"] for d in lay["physical"])
            top = min(d["y"] for d in lay["physical"])
            _park_agents(right, top)
            for s in svdas():
                _stage(s["name"])
            _apply()
        except Exception:
            subprocess.run(["DisplaySwitch.exe", "/extend"], timeout=20)
            time.sleep(3)
    h = _svda_open()
    if h:
        _svda_remove_all(h); k32.CloseHandle(h)
    _kill_watcher()
    SCREEN_OFF.unlink(missing_ok=True)
    _restore_windows(lay)


def _shift_to(h, d):
    """Move a window to display d keeping its size, relative position and minimized/maximized state, no activation."""
    mon = _monitor(h).rcMonitor
    wp = _get_placement(h)
    dx, dy = d["x"] - mon.l, d["y"] - mon.t
    l, t, r, b = wp["rc"]
    wp["rc"] = [l + dx, t + dy, r + dx, b + dy]
    _set_placement(h, wp)


def _to_user_screen():
    """Screen off: every non-agent window on an agent screen goes to the main screen-off monitor."""
    u, vn = vuser(), {d["name"] for d in vdds()}
    if not (u and vn):
        return
    a = _allowed(); hw, pids = set(a.get("hwnds", [])), set(a.get("pids", []))
    me = os.getpid()
    for w in windows():
        if w["display"] in vn and w["hwnd"] not in hw and w["pid"] not in pids and w["pid"] != me \
                and not _cloaked(w["hwnd"]):
            try:
                _shift_to(w["hwnd"], u)
            except Exception:
                pass


def _restore_windows(lay):
    """Put every window that was on the physical screens before screen-off back where it was (no activation)."""
    if not lay:
        return
    time.sleep(1.0)   # let Windows finish its own monitor-change shuffling first
    for w in lay.get("windows", []):
        h = w["hwnd"]
        if u32.IsWindow(h) and _pid(h) == w["pid"]:
            _set_placement(h, w["pl"])
    try:
        LAYOUT.unlink()
    except OSError:
        pass


# ---------------------------------------------------------------- wake watcher (second-only)
def _spawn_watcher():
    _kill_watcher()
    exe = Path(sys.executable)
    pyw = exe.with_name("pythonw.exe") if exe.with_name("pythonw.exe").exists() else exe
    p = subprocess.Popen([str(pyw), str(Path(__file__).resolve()), "_watch"], close_fds=True,
                         creationflags=0x00000008 | 0x00000200)  # DETACHED_PROCESS | NEW_PROCESS_GROUP
    WATCH_PID.write_text(str(p.pid))


def _kill_watcher():
    try:
        pid = int(WATCH_PID.read_text())
    except Exception:
        return
    WATCH_PID.unlink(missing_ok=True)
    if pid != os.getpid():
        h = k32.OpenProcess(0x0001, False, pid)
        if h:
            k32.TerminateProcess(h, 0); k32.CloseHandle(h)


def _watch():
    """Low-level hooks; injected input (the agent's own SendInput) is ignored, real input restores extend."""
    LRESULT = ctypes.c_ssize_t
    HOOK = ctypes.WINFUNCTYPE(LRESULT, ctypes.c_int, W.WPARAM, W.LPARAM)
    u32.CallNextHookEx.argtypes = [W.HHOOK, ctypes.c_int, W.WPARAM, W.LPARAM]
    u32.CallNextHookEx.restype = LRESULT
    u32.SetWindowsHookExW.argtypes = [ctypes.c_int, HOOK, W.HINSTANCE, W.DWORD]
    u32.SetWindowsHookExW.restype = W.HHOOK
    start, woke = time.time() + 1.5, []
    wlog = STATE / "watch.log"

    def note(s):
        try:
            with open(wlog, "a", encoding="utf-8") as f:
                f.write(time.strftime("%H:%M:%S ") + s + "\n")
        except OSError:
            pass
    note("watch start")
    WATCH_PID.write_text(str(os.getpid()))   # the venv pythonw launcher's pid is not ours
    svh = _svda_open()
    import threading

    def keepalive():   # SudoVDA's watchdog removes its monitors once pings stop (e.g. this watcher dies)
        while True:
            _svda_io(svh, SVDA_PING); time.sleep(0.5)
    if svh:
        threading.Thread(target=keepalive, daemon=True).start()
    try:
        if not svh:
            raise RuntimeError("SudoVDA driver not available")
        names = _screen_off_setup(svh)
        note(f"screen-off monitors {names}")
        SCREEN_OFF.write_text(json.dumps({"ok": True, "monitors": names}), encoding="utf-8")
    except Exception as e:
        import traceback
        note(traceback.format_exc())
        SCREEN_OFF.write_text(json.dumps({"ok": False, "error": str(e)}), encoding="utf-8")
        WATCH_PID.unlink(missing_ok=True)
        try:
            to_extend()
        finally:
            if svh:
                k32.CloseHandle(svh)
        return
    start = time.time() + 1.5

    def fire():
        if not woke and time.time() > start:
            woke.append(1); note("real input -> extend"); u32.PostThreadMessageW(k32.GetCurrentThreadId(), 0x0012, 0, 0)  # WM_QUIT

    @HOOK
    def kb(n, wp, lp):
        if n == 0 and not (ctypes.cast(lp, ctypes.POINTER(W.DWORD))[2] & 0x10):   # KBDLLHOOKSTRUCT.flags, LLKHF_INJECTED
            fire()
        return u32.CallNextHookEx(None, n, wp, lp)

    @HOOK
    def ms(n, wp, lp):
        if n == 0 and not (ctypes.cast(lp, ctypes.POINTER(W.DWORD))[3] & 0x1):    # MSLLHOOKSTRUCT.flags, LLMHF_INJECTED
            fire()
        return u32.CallNextHookEx(None, n, wp, lp)

    k32.GetModuleHandleW.restype = ctypes.c_void_p   # default c_int truncates the 64-bit handle -> hook error 126
    k32.GetModuleHandleW.argtypes = [W.LPCWSTR]
    mod = k32.GetModuleHandleW(None)
    h1, h2 = u32.SetWindowsHookExW(13, kb, mod, 0), u32.SetWindowsHookExW(14, ms, mod, 0)
    note(f"hooks kb={bool(h1)} ms={bool(h2)} err={ctypes.get_last_error()}")
    msg = W.MSG()
    while u32.GetMessageW(ctypes.byref(msg), None, 0, 0) > 0:
        pass
    u32.UnhookWindowsHookEx(h1); u32.UnhookWindowsHookEx(h2)
    if woke:
        WATCH_PID.unlink(missing_ok=True)
        try:
            to_extend()
            note("extend done")
        except Exception:
            import traceback
            note(traceback.format_exc())


# ---------------------------------------------------------------- windows
class RECT(ctypes.Structure):
    _fields_ = [("l", W.LONG), ("t", W.LONG), ("r", W.LONG), ("b", W.LONG)]


class MONITORINFOEXW(ctypes.Structure):
    _fields_ = [("cbSize", W.DWORD), ("rcMonitor", RECT), ("rcWork", RECT), ("dwFlags", W.DWORD),
                ("szDevice", W.WCHAR * 32)]


u32.MonitorFromWindow.restype = W.HMONITOR
u32.MonitorFromWindow.argtypes = [W.HWND, W.DWORD]
u32.GetMonitorInfoW.argtypes = [W.HMONITOR, ctypes.POINTER(MONITORINFOEXW)]
u32.SetWindowPos.argtypes = [W.HWND, W.HWND, ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_int, W.UINT]
u32.PostMessageW.argtypes = [W.HWND, W.UINT, W.WPARAM, W.LPARAM]
u32.GetForegroundWindow.restype = W.HWND
u32.WindowFromPoint.argtypes = [W.POINT]
u32.WindowFromPoint.restype = W.HWND
SWP_NOZORDER, SWP_NOACTIVATE, SWP_NOSIZE = 0x4, 0x10, 0x1


class WINDOWPLACEMENT(ctypes.Structure):
    _fields_ = [("length", W.UINT), ("flags", W.UINT), ("showCmd", W.UINT), ("ptMin", W.POINT),
                ("ptMax", W.POINT), ("rc", RECT)]


def _get_placement(h):
    wp = WINDOWPLACEMENT(); wp.length = ctypes.sizeof(wp)
    u32.GetWindowPlacement(h, ctypes.byref(wp))
    return {"show": wp.showCmd, "min": [wp.ptMin.x, wp.ptMin.y], "max": [wp.ptMax.x, wp.ptMax.y],
            "rc": [wp.rc.l, wp.rc.t, wp.rc.r, wp.rc.b]}


def _set_placement(h, pl):
    wp = WINDOWPLACEMENT(); wp.length = ctypes.sizeof(wp)
    # SW_SHOWMAXIMIZED stays maximized; minimized stays minimized; everything else restored without activation
    wp.showCmd = {3: 3, 2: 7, 6: 7}.get(pl["show"], 4)   # 7 = SW_SHOWMINNOACTIVE, 4 = SW_SHOWNOACTIVATE
    wp.ptMin, wp.ptMax = W.POINT(*pl["min"]), W.POINT(*pl["max"])
    wp.rc = RECT(*pl["rc"])
    fg = u32.GetForegroundWindow()
    u32.SetWindowPlacement(h, ctypes.byref(wp))
    if fg and u32.GetForegroundWindow() != fg:
        u32.SetForegroundWindow(fg)


def _title(h):
    b = ctypes.create_unicode_buffer(512); u32.GetWindowTextW(h, b, 512); return b.value


def _pid(h):
    p = W.DWORD(); u32.GetWindowThreadProcessId(h, ctypes.byref(p)); return p.value


u32.MonitorFromRect.restype = W.HMONITOR
u32.MonitorFromRect.argtypes = [ctypes.POINTER(RECT), W.DWORD]


def _monitor(h):
    mi = MONITORINFOEXW(); mi.cbSize = ctypes.sizeof(mi)
    if u32.IsIconic(h):   # minimized windows sit at -32000; use where they will restore to
        wp = WINDOWPLACEMENT(); wp.length = ctypes.sizeof(wp)
        u32.GetWindowPlacement(h, ctypes.byref(wp))
        hm = u32.MonitorFromRect(ctypes.byref(wp.rc), 2)
    else:
        hm = u32.MonitorFromWindow(h, 2)
    u32.GetMonitorInfoW(hm, ctypes.byref(mi))
    return mi


def _started(pid):
    """Process creation time (FILETIME as int), 0 if unknown."""
    h = k32.OpenProcess(0x1000, False, pid)
    if not h:
        return 0
    t = [ctypes.c_ulonglong() for _ in range(4)]
    ok = k32.GetProcessTimes(h, *[ctypes.byref(x) for x in t]); k32.CloseHandle(h)
    return t[0].value if ok else 0


def windows():
    res = []
    CB = ctypes.WINFUNCTYPE(W.BOOL, W.HWND, W.LPARAM)

    def cb(h, _):
        if u32.IsWindowVisible(h) and _title(h) and not u32.GetWindow(h, 4):   # GW_OWNER: skip owned popups
            r = RECT(); u32.GetWindowRect(h, ctypes.byref(r))
            res.append({"hwnd": h, "pid": _pid(h), "display": _monitor(h).szDevice, "title": _title(h),
                        "rect": [r.l, r.t, r.r, r.b]})
        return True
    u32.EnumWindows(CB(cb), 0)
    return res


def find(sel):
    s = str(sel).strip()
    if s.lower().startswith("pid:"):
        ws = [w for w in windows() if w["pid"] == int(s[4:])]
    elif s.lower().startswith("0x") or s.isdigit():
        h = int(s, 0)
        if not u32.IsWindow(h):
            raise RuntimeError(f"no window {s}")
        return h
    else:
        ws = [w for w in windows() if s.lower() in w["title"].lower()]
    if not ws:
        raise RuntimeError(f"no window matches {s!r}")
    return ws[0]["hwnd"]


def _target_bounds(where):
    if where != "virtual" and screen_off():
        d = vuser()
        if d:
            return d["x"], d["y"], d["w"], d["h"]
    d = vdd() if where == "virtual" else next((d for d in physical() if d["attached"] and d["primary"]), None) \
        or next((d for d in physical() if d["attached"]), None)
    if not d or not d["attached"]:
        raise RuntimeError(f"{where} display is not active")
    return d["x"], d["y"], d["w"], d["h"]


def _allowed():
    try:
        return json.loads(AGENT_WINS.read_text(encoding="utf-8"))
    except Exception:
        return {"hwnds": [], "pids": []}


def _allow(hwnds=(), pids=(), remove=False):
    a = _allowed()
    for k, vals in (("hwnds", hwnds), ("pids", pids)):
        s = set(a.get(k, []))
        s = s - set(vals) if remove else s | set(vals)
        if k == "hwnds":
            s = {h for h in s if u32.IsWindow(h)}
        a[k] = sorted(s)
    if a["pids"]:   # drop pids that no longer own a window
        live = {w["pid"] for w in windows()}
        a["pids"] = [p for p in a["pids"] if p in live]
    AGENT_WINS.write_text(json.dumps(a), encoding="utf-8")


def _cloaked(h):
    v = ctypes.c_int(0)
    ctypes.windll.dwmapi.DwmGetWindowAttribute(W.HWND(h), 14, ctypes.byref(v), 4)   # DWMWA_CLOAKED
    return v.value != 0


def _spawn_guard():
    if _alive(GUARD_PID):
        return
    # Elevated guard (scheduled task made by install-vdd.ps1) can also move admin windows; UIPI blocks a normal one.
    if subprocess.run(["schtasks.exe", "/run", "/tn", GUARD_TASK], capture_output=True, timeout=20,
                      creationflags=0x08000000).returncode == 0:
        for _ in range(20):
            time.sleep(0.25)
            if _alive(GUARD_PID):
                return
    exe = Path(sys.executable)
    pyw = exe.with_name("pythonw.exe") if exe.with_name("pythonw.exe").exists() else exe
    p = subprocess.Popen([str(pyw), "-I", str(Path(__file__).resolve()), "_guard"], close_fds=True,
                         creationflags=0x00000008 | 0x00000200)
    # the guard writes guard.pid itself: the venv pythonw launcher's pid is not the real interpreter's
    for _ in range(20):
        time.sleep(0.25)
        if _alive(GUARD_PID):
            return


def _alive(pidfile):
    try:
        pid = int(pidfile.read_text())
    except Exception:
        return False
    h = k32.OpenProcess(0x1000, False, pid)   # PROCESS_QUERY_LIMITED_INFORMATION
    if not h:
        return False
    code = W.DWORD(); k32.GetExitCodeProcess(h, ctypes.byref(code)); k32.CloseHandle(h)
    return code.value == 259   # STILL_ACTIVE


def _kill_guard():
    try:
        pid = int(GUARD_PID.read_text())
    except Exception:
        return
    GUARD_PID.unlink(missing_ok=True)
    h = k32.OpenProcess(0x0001, False, pid)
    if h:
        k32.TerminateProcess(h, 0); k32.CloseHandle(h)


AGENT_INPUT = STATE / "agent-input"   # exists while the agent drives real mouse input on the virtual screen
_vb = {"t": 0, "r": None}


def _fence():
    """Keep the user's mouse off the virtual screen: clamp the cursor back to the nearest edge."""
    if time.time() - _vb["t"] > 2:
        _vb["t"] = time.time()
        ds = displays()
        _vb["r"] = [(a["x"], a["y"], a["x"] + a["w"], a["y"] + a["h"]) for a in ds if a["virtual"] and a["attached"]]
        _vb["phys"] = [(d["x"], d["y"], d["x"] + d["w"], d["y"] + d["h"])
                       for d in ds if d["attached"] and not d["virtual"]]
    r = _vb["r"]
    if not r or AGENT_INPUT.exists():
        return
    pt = W.POINT(); u32.GetCursorPos(ctypes.byref(pt))
    if any(l <= pt.x < rr and t <= pt.y < b for l, t, rr, b in r):
        # clamp to the nearest point on a physical screen
        best = None
        for l, t, rr, b in _vb.get("phys") or []:
            x, y = min(max(pt.x, l), rr - 1), min(max(pt.y, t), b - 1)
            d = (x - pt.x) ** 2 + (y - pt.y) ** 2
            if best is None or d < best[0]:
                best = (d, x, y)
        if best:
            u32.SetCursorPos(best[1], best[2])


def _guard():
    """Keep the virtual screen for the agent: any window not in agent-windows.json is moved to the main screen
    (no activation). Runs while the virtual display is on; exits when it goes away."""
    me = os.getpid()
    if _alive(GUARD_PID) and int(GUARD_PID.read_text()) != me:
        return   # another guard is already running
    GUARD_PID.write_text(str(me))
    last = 0.0
    while True:
        time.sleep(0.02)
        _fence()
        if time.time() - last < 0.5:
            continue
        last = time.time()
        vs = vdds()
        if not vs:
            if not svdas():
                GUARD_PID.unlink(missing_ok=True); return
            continue   # only the screen-off monitors are on: nothing to guard
        if u32.GetAsyncKeyState(0x01) & 0x8000:   # mid-drag
            continue
        if screen_off():
            _to_user_screen()
            continue
        a = _allowed(); hw, pids = set(a.get("hwnds", [])), set(a.get("pids", []))
        vnames = {v["name"] for v in vs}
        for w in windows():
            if w["display"] in vnames and w["hwnd"] not in hw and w["pid"] not in pids and w["pid"] != me \
                    and not _cloaked(w["hwnd"]):
                try:
                    _place(w["hwnd"], "main")
                except Exception:
                    pass


def move(h, where):
    _allow([h], remove=(where != "virtual"))
    if where == "virtual":
        turn_on()
    _place(h, where)


def _place(h, where):
    tx, ty, tw, th = _target_bounds(where)
    if u32.IsIconic(h):   # keep it minimized; just retarget where it restores to
        wp = WINDOWPLACEMENT(); wp.length = ctypes.sizeof(wp)
        u32.GetWindowPlacement(h, ctypes.byref(wp))
        w, hh = min(wp.rc.r - wp.rc.l, tw), min(wp.rc.b - wp.rc.t, th)
        wp.rc = RECT(tx + 40, ty + 40, tx + 40 + w, ty + 40 + hh)
        wp.showCmd = 7   # SW_SHOWMINNOACTIVE
        u32.SetWindowPlacement(h, ctypes.byref(wp))
        return
    if u32.IsIconic(h) or u32.IsZoomed(h):
        u32.ShowWindow(h, 4)   # SW_SHOWNOACTIVATE (restore without focus)
    r, mon = RECT(), _monitor(h).rcMonitor
    u32.GetWindowRect(h, ctypes.byref(r))
    w, hh = min(r.r - r.l, tw), min(r.b - r.t, th)
    x = tx + max(0, min(r.l - mon.l, tw - w)); y = ty + max(0, min(r.t - mon.t, th - hh))
    u32.SetWindowPos(h, None, x, y, w, hh, SWP_NOZORDER | SWP_NOACTIVATE)


def show(h):
    to_extend()   # the user wants to see it: wake the physical screen
    move(h, "main")
    if u32.IsIconic(h):
        u32.ShowWindow(h, 9)   # SW_RESTORE
    u32.keybd_event(0x12, 0, 0, 0); u32.keybd_event(0x12, 0, 2, 0)   # ALT tap lets SetForegroundWindow succeed
    u32.SetForegroundWindow(h)


def launch(cmd):
    fg = u32.GetForegroundWindow()
    turn_on()
    before = {w["hwnd"] for w in windows()}
    try:
        p = subprocess.Popen(cmd, creationflags=0x00000200, close_fds=True)
    except FileNotFoundError:   # app-execution aliases (mspaint, Store apps) only resolve through the shell
        p = subprocess.Popen(["cmd.exe", "/c", "start", "", *cmd], creationflags=0x08000000, close_fds=True)
    _allow(pids=[p.pid])
    t0 = _started(p.pid) - 20_000_000   # 2 s slack (FILETIME is 100 ns units)
    moved, t_end = set(), time.time() + 15
    while time.time() < t_end:
        time.sleep(0.4)
        for w in windows():
            # only windows of processes started by this launch (skips e.g. a tray app restoring itself meanwhile)
            if w["hwnd"] not in before and w["hwnd"] not in moved and w["pid"] in (p.pid,) or \
                    (w["hwnd"] not in before and w["hwnd"] not in moved and _started(w["pid"]) >= t0 > 0):
                move(w["hwnd"], "virtual"); moved.add(w["hwnd"])
        if fg and u32.GetForegroundWindow() != fg and u32.GetForegroundWindow() in moved:
            u32.SetForegroundWindow(fg)
        if moved and time.time() > t_end - 12:
            break
    return {"pid": p.pid, "windows": sorted(moved)}


# ---------------------------------------------------------------- input
VK = {"enter": 0x0D, "tab": 0x09, "esc": 0x1B, "escape": 0x1B, "back": 0x08, "backspace": 0x08, "space": 0x20,
      "left": 0x25, "up": 0x26, "right": 0x27, "down": 0x28, "delete": 0x2E, "home": 0x24, "end": 0x23,
      "pgup": 0x21, "pgdn": 0x22, **{f"f{i}": 0x6F + i for i in range(1, 13)}}


def _deepest(top, sx, sy):
    pt = W.POINT(sx, sy)
    h = u32.WindowFromPoint(pt)
    if not h or (h != top and not u32.IsChild(top, h)):
        h = top
    return h


def _lparam_client(h, sx, sy):
    pt = W.POINT(sx, sy); u32.ScreenToClient(h, ctypes.byref(pt))
    return (pt.y & 0xFFFF) << 16 | (pt.x & 0xFFFF)


def bg_click(top, sx, sy, right=False):
    h = _deepest(top, sx, sy)
    lp = _lparam_client(h, sx, sy)
    down, up, mk = (0x0204, 0x0205, 0x2) if right else (0x0201, 0x0202, 0x1)
    u32.PostMessageW(h, 0x0200, 0, lp)   # WM_MOUSEMOVE
    u32.PostMessageW(h, down, mk, lp); time.sleep(0.03); u32.PostMessageW(h, up, 0, lp)


def _focus_child(top):
    gti = (ctypes.c_byte * 72)(); ctypes.cast(gti, ctypes.POINTER(W.DWORD))[0] = 72   # GUITHREADINFO
    tid = u32.GetWindowThreadProcessId(top, None)
    if u32.GetGUIThreadInfo(tid, gti):
        f = ctypes.cast(ctypes.addressof(gti) + 16, ctypes.POINTER(ctypes.c_void_p))[0]   # hwndFocus
        if f:
            return f
    return top


def bg_type(top, text):
    h = _focus_child(top)
    for ch in text:
        u32.PostMessageW(h, 0x0102, ord(ch), 1)   # WM_CHAR
        time.sleep(0.005)


def bg_key(top, key):
    vk = VK.get(str(key).lower()) or int(str(key), 0)
    h = _focus_child(top)
    u32.PostMessageW(h, 0x0100, vk, 1); u32.PostMessageW(h, 0x0101, vk, 0xC0000001)


class _Restore:
    """Real-input fallback: remember the user's foreground window + cursor and put them back afterwards."""
    def __enter__(self):
        import msvcrt
        self.lk = open(INPUT_LOCK, "a+b")   # global: another session's real input finishes first
        end = time.time() + 300
        while True:
            try:
                self.lk.seek(0); msvcrt.locking(self.lk.fileno(), msvcrt.LK_NBLCK, 1); break
            except OSError:
                if time.time() > end:
                    self.lk.close(); raise RuntimeError("another session is using the real mouse/keyboard")
                time.sleep(0.2)
        AGENT_INPUT.touch()   # let the agent's own cursor onto the virtual screen
        self.fg = u32.GetForegroundWindow(); self.pt = W.POINT(); u32.GetCursorPos(ctypes.byref(self.pt)); return self

    def __exit__(self, *a):
        u32.SetCursorPos(self.pt.x, self.pt.y)
        AGENT_INPUT.unlink(missing_ok=True)
        if self.fg:
            u32.keybd_event(0x12, 0, 0, 0); u32.keybd_event(0x12, 0, 2, 0); u32.SetForegroundWindow(self.fg)
        import msvcrt
        try:
            self.lk.seek(0); msvcrt.locking(self.lk.fileno(), msvcrt.LK_UNLCK, 1)
        finally:
            self.lk.close()


def real_click(sx, sy, right=False):
    with _Restore():
        u32.SetCursorPos(sx, sy); time.sleep(0.03)
        d, u = (0x8, 0x10) if right else (0x2, 0x4)
        u32.mouse_event(d, 0, 0, 0, 0); time.sleep(0.03); u32.mouse_event(u, 0, 0, 0, 0); time.sleep(0.05)


def real_type(top, text):
    class KI(ctypes.Structure):
        _fields_ = [("wVk", W.WORD), ("wScan", W.WORD), ("dwFlags", W.DWORD), ("time", W.DWORD),
                    ("dwExtraInfo", ctypes.c_size_t)]

    class INPUT(ctypes.Structure):
        _fields_ = [("type", W.DWORD), ("ki", KI), ("pad", ctypes.c_byte * 8)]
    with _Restore():
        u32.keybd_event(0x12, 0, 0, 0); u32.keybd_event(0x12, 0, 2, 0); u32.SetForegroundWindow(top); time.sleep(0.1)
        for ch in text:
            for fl in (0x4, 0x6):   # KEYEVENTF_UNICODE, | KEYUP
                i = INPUT(type=1, ki=KI(0, ord(ch), fl, 0, 0))
                u32.SendInput(1, ctypes.byref(i), ctypes.sizeof(i))
        time.sleep(0.05)


def shot(out=None):
    from PIL import ImageGrab
    v = vdd()
    if not v or not v["attached"]:
        raise RuntimeError("this session's agent screen is off (run: hermes-vdisplay on)")
    out = Path(out or STATE / f"shot-{int(time.time())}.png")
    ImageGrab.grab(bbox=(v["x"], v["y"], v["x"] + v["w"], v["y"] + v["h"]), all_screens=True).save(out)
    return str(out)


# ---------------------------------------------------------------- CLI
def status():
    v, ok = vdd(), svda_installed()
    return {"installed": ok, "session": _sid(), "agent": bool(v), "screen_off": screen_off(),
            "attached": bool(v), "mode": mode_now(), "user_screen_available": ok,
            "virtual": v, "agents": _jload(AGENT_SCREENS, {}) if _alive(HOST_PID) else {},
            "user_screen": vuser(), "physical": [d for d in physical() if d["attached"]]}


def main(a):
    if not a or a[0] in ("-h", "--help", "help"):
        print(__doc__); return 0
    c, r = a[0], a[1:]
    if c == "_watch":
        _watch(); return 0
    if c == "_guard":
        _guard(); return 0
    if c == "_host":
        _host(); return 0
    if c == "agents":
        print(json.dumps(_jload(AGENT_SCREENS, {}) if _alive(HOST_PID) else {})); return 0
    if c == "allow":
        _allow([find(x) for x in r]); print(json.dumps(_allowed())); return 0
    if c == "disallow":
        _allow([find(x) for x in r], remove=True); print(json.dumps(_allowed())); return 0
    if c in ("install", "uninstall"):
        args = ["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(HERE / "install-vdd.ps1")]
        subprocess.run(args + (["-Uninstall"] if c == "uninstall" else []), timeout=900)
        print(json.dumps(status())); return 0
    if c == "status":
        print(json.dumps(status(), indent=1)); return 0
    if c in ("on", "agent-on") or (c == "agent" and r[:1] == ["on"]):
        turn_on()
    elif c in ("off", "agent-off") or (c == "agent" and r[:1] == ["off"]):
        turn_off()
    elif c in ("screen-off", "screen-on") or c == "screen":
        off = c == "screen-off" or (c == "screen" and r[:1] == ["off"])
        (to_second_only if off else to_extend)()
    elif c == "mode":
        {"extend": to_extend, "second-only": to_second_only}[r[0]]()
    elif c in ("extend", "second-only"):
        (to_extend if c == "extend" else to_second_only)()
    elif c == "windows":
        print(json.dumps(windows(), ensure_ascii=False, indent=1)); return 0
    elif c == "launch":
        print(json.dumps(launch(r[1:] if r and r[0] == "--" else r))); return 0
    elif c == "move":
        move(find(r[0]), r[1])
    elif c == "show":
        show(find(r[0]))
    elif c == "bg-click":
        bg_click(find(r[0]), int(r[1]), int(r[2]), len(r) > 3 and r[3] == "right")
    elif c == "bg-type":
        bg_type(find(r[0]), " ".join(r[1:]))
    elif c == "bg-key":
        bg_key(find(r[0]), r[1])
    elif c == "click":
        real_click(int(r[0]), int(r[1]), len(r) > 2 and r[2] == "right")
    elif c == "type":
        real_type(find(r[0]), " ".join(r[1:]))
    elif c == "shot":
        print(shot(r[0] if r else None)); return 0
    else:
        print(f"unknown command {c!r}\n\n{__doc__}", file=sys.stderr); return 2
    print(json.dumps({"ok": True, "mode": mode_now(), "agent": agent_on(), "screen_off": screen_off()}))
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv[1:]))
    except (RuntimeError, IndexError, KeyError, ValueError) as e:
        print(json.dumps({"ok": False, "error": str(e) or type(e).__name__}))
        sys.exit(1)
