"""hermes-vdisplay: an agent screen for Hermes on top of the open-source Virtual Display Driver (MttVDD).

The agent opens and drives windows on a virtual monitor so the user's focus on the main screen is never stolen.

  status                         JSON: installed / attached / mode / display bounds
  install | uninstall            install-vdd.ps1 (one UAC prompt)
  on | off  (agent on|off)       agent screen (1st virtual monitor) on / off; independent of screen-off
  screen off | screen on         physical screens go dark and the user's windows move to the user screen
                                 (2nd virtual monitor); real keyboard/mouse input turns them back on.
                                 Independent of the agent screen. Legacy: mode second-only|extend
  windows                        visible top-level windows: hwnd, pid, display, title
  launch -- <cmd> [args]         start a program and put its windows on the virtual display, without focus
  move <win> virtual|main        move a window (no activation for virtual)
  show <win>                     bring a window to the main screen for the user (wakes it if needed)
  bg-click <win> X Y [right]     background click via window messages, X/Y in screen coordinates
  bg-type <win> <text>           background typing via WM_CHAR
  bg-key <win> <VK>              background key press (virtual-key code or name: enter, tab, esc ...)
  click X Y [right]              fallback: real mouse click, then the user's foreground window + cursor are restored
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
VDD_NAMES = ("virtual display driver",)  # MttVDD adapter string; anything else (e.g. Parsec) is never touched


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


def displays():
    out, i = [], 0
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
                    "virtual": d.DeviceString.strip().lower() in VDD_NAMES,
                    "x": m.dmPositionX if ok else 0, "y": m.dmPositionY if ok else 0,
                    "w": m.dmPelsWidth if ok and att else 0, "h": m.dmPelsHeight if ok and att else 0,
                    "hz": m.dmDisplayFrequency if ok else 0})


def vdds():
    import re
    return sorted((d for d in displays() if d["virtual"]),
                  key=lambda d: int((re.findall(r"\d+", d["name"]) or [0])[-1]))


def vdd():
    """The agent's screen (first virtual monitor)."""
    return next(iter(vdds()), None)


def vuser():
    """Second virtual monitor (user screen): takes the user's windows while the physical screens are off."""
    v = vdds()
    return v[1] if len(v) > 1 else None


def physical():
    # Other virtual adapters (Parsec etc.) are left exactly as they are.
    return [d for d in displays() if not d["virtual"] and "parsec" not in d["adapter"].lower()]


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


def _best_mode(name, want_w, want_h):
    best, i = None, 0
    while True:
        m = DEVMODEW(); m.dmSize = ctypes.sizeof(m)
        if not u32.EnumDisplaySettingsW(name, i, ctypes.byref(m)):
            break
        i += 1
        w, h, hz = m.dmPelsWidth, m.dmPelsHeight, m.dmDisplayFrequency
        # exact size wins; otherwise the largest mode that fits; ties -> refresh closest to 60..165, highest
        key = ((w, h) == (want_w, want_h), w <= want_w and h <= want_h, w * h if w <= want_w and h <= want_h else -w * h,
               min(hz, 165))
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


# IddCx: Windows' own topology switch only activates one virtual monitor, so the second one is added via SetDisplayConfig
class _DCLUID(ctypes.Structure):
    _fields_ = [("lo", W.DWORD), ("hi", W.LONG)]


class _DCSRC(ctypes.Structure):
    _fields_ = [("adapter", _DCLUID), ("id", W.UINT), ("modeIdx", W.UINT), ("status", W.UINT)]


class _DCTGT(ctypes.Structure):
    _fields_ = [("adapter", _DCLUID), ("id", W.UINT), ("modeIdx", W.UINT), ("tech", W.UINT), ("rot", W.UINT),
                ("scal", W.UINT), ("rn", W.UINT), ("rd", W.UINT), ("scan", W.UINT), ("avail", W.BOOL),
                ("status", W.UINT)]


class _DCPATH(ctypes.Structure):
    _fields_ = [("src", _DCSRC), ("tgt", _DCTGT), ("flags", W.UINT)]


class _DCMODE(ctypes.Structure):
    _fields_ = [("type", W.UINT), ("id", W.UINT), ("adapter", _DCLUID), ("u", ctypes.c_byte * 48)]


class _DCHDR(ctypes.Structure):
    _fields_ = [("type", W.UINT), ("size", W.UINT), ("adapter", _DCLUID), ("id", W.UINT)]


class _DCTNAME(ctypes.Structure):
    _fields_ = [("h", _DCHDR), ("flags", W.UINT), ("tech", W.UINT), ("manu", W.WORD), ("prod", W.WORD),
                ("conn", W.UINT), ("friendly", W.WCHAR * 64), ("path", W.WCHAR * 128)]


assert ctypes.sizeof(_DCPATH) == 72 and ctypes.sizeof(_DCMODE) == 64, (ctypes.sizeof(_DCPATH), ctypes.sizeof(_DCMODE))


def _dc_query(flags):
    np_, nm = W.UINT(), W.UINT()
    assert u32.GetDisplayConfigBufferSizes(flags, ctypes.byref(np_), ctypes.byref(nm)) == 0
    paths, modes = (_DCPATH * np_.value)(), (_DCMODE * nm.value)()
    assert u32.QueryDisplayConfig(flags, ctypes.byref(np_), paths, ctypes.byref(nm), modes, None) == 0
    return list(paths)[:np_.value], list(modes)[:nm.value]


def _dc_tpath(p):
    n = _DCTNAME(); n.h.type, n.h.size, n.h.adapter, n.h.id = 2, ctypes.sizeof(n), p.tgt.adapter, p.tgt.id
    return n.path if u32.DisplayConfigGetDeviceInfo(ctypes.byref(n)) == 0 else ""


class _DCSNAME(ctypes.Structure):
    _fields_ = [("h", _DCHDR), ("name", W.WCHAR * 32)]


def _dc_sname(p):
    n = _DCSNAME(); n.h.type, n.h.size, n.h.adapter, n.h.id = 1, ctypes.sizeof(n), p.src.adapter, p.src.id
    return n.name if u32.DisplayConfigGetDeviceInfo(ctypes.byref(n)) == 0 else ""


def _attach(role):
    """Activate one virtual monitor (0 = agent screen, 1 = user screen) on its own GDI source, leaving the other
    one alone. Windows' DisplaySwitch only knows 'all on', so this goes through SetDisplayConfig."""
    vs = vdds()
    if len(vs) <= role:
        return "no such virtual monitor"
    name = vs[role]["name"]
    ap, am = _dc_query(1)    # all paths
    act, _ = _dc_query(2)    # only active
    mtt = [p for p in ap if "mtt1337" in _dc_tpath(p).lower()]
    tids = sorted({p.tgt.id for p in mtt})
    if len(tids) <= role:
        return "no MTT target"
    tid = tids[role]
    busy_t = {(p.tgt.adapter.lo, p.tgt.adapter.hi, p.tgt.id) for p in act}
    busy_s = {(p.src.adapter.lo, p.src.adapter.hi, p.src.id) for p in act}
    pick = None
    for p in mtt:
        if p.tgt.id != tid or not p.tgt.avail or (p.tgt.adapter.lo, p.tgt.adapter.hi, p.tgt.id) in busy_t \
                or (p.src.adapter.lo, p.src.adapter.hi, p.src.id) in busy_s:
            continue
        if _dc_sname(p) == name:
            pick = p; break
        pick = pick or p
    if pick is None:
        return "no inactive path"
    sel = [p for p in ap if p.flags & 1]
    pick.flags |= 1; pick.src.modeIdx = 0xFFFFFFFF; pick.tgt.modeIdx = 0xFFFFFFFF
    sel.append(pick)
    arr = (_DCPATH * len(sel))(*sel)
    marr = (_DCMODE * len(am))(*am)
    for fl in (0x40, 0x80):   # validate, then apply
        r = u32.SetDisplayConfig(len(sel), arr, len(am), marr, 0x20 | 0x400 | fl)
        if r != 0:
            return r
    return 0


def _attached_role(role):
    vs = vdds()
    return vs[role] if len(vs) > role and vs[role]["attached"] else None


def _ensure(role):
    """Attach virtual monitor `role` and return it (or None)."""
    d = _attached_role(role)
    if d:
        return d
    r = _attach(role)
    for _ in range(12):
        time.sleep(0.5)
        d = _attached_role(role)
        if d:
            return d
    if r != 0 and role == 0 and not any(_attached_role(i) for i in range(len(vdds()))):
        # last resort: Windows' own topology switch (turns every virtual monitor on); drop the user screen again
        subprocess.run(["DisplaySwitch.exe", "/extend"], timeout=30)
        time.sleep(3)
        u = _attached_role(1)
        if u and not screen_off():
            _stage(u["name"]); _apply()
        return _attached_role(0)
    return None


def _right_edge():
    """x right of every attached non-agent display (physical screens, or the user screen when they are off)."""
    a = vdd()
    ds = [d for d in displays() if d["attached"] and not (a and d["name"] == a["name"])
          and "parsec" not in d["adapter"].lower()]
    return (max(d["x"] + d["w"] for d in ds), min(d["y"] for d in ds)) if ds else (0, 0)


def turn_on():
    """Agent screen on (independent of the screen-off feature)."""
    v = vdd()
    if not v:
        raise RuntimeError("virtual display driver not installed (run: hermes-vdisplay install)")
    if not v["attached"]:
        if not _ensure(0):
            raise RuntimeError("agent screen did not attach")
        v = vdd()
        prim = next((d for d in displays() if d["attached"] and d["primary"]), None) or {"w": 1920, "h": 1080}
        x, y = _right_edge()
        w, h, hz = _best_mode(v["name"], prim["w"], prim["h"])
        _stage(v["name"], x, y, w, h, hz)
        _apply()
    _spawn_guard()


def turn_off():
    """Agent screen off; the user screen / screen-off state is left as it is."""
    v = vdd()
    if v and v["attached"]:
        if not any(d["attached"] for d in displays() if d["name"] != v["name"]):
            raise RuntimeError("the agent screen is the only screen on; turn the screen back on first")
        _stage(v["name"])
        _apply()
    _allow()   # prune dead entries


def to_second_only():
    """Screen off: physical screens go dark, the user's windows move to the user screen (2nd virtual monitor).
    Works with the agent screen on or off; the agent screen is never used for the user's windows."""
    if screen_off():
        return
    if not vuser():
        raise RuntimeError("screen-off needs the second virtual monitor (reinstall the driver: hermes-vdisplay install)")
    phys = [d for d in physical() if d["attached"]]
    prim = next((d for d in phys if d["primary"]), phys[0])
    names = {d["name"] for d in phys}
    wins = [{"hwnd": w["hwnd"], "pid": w["pid"], "title": w["title"], "pl": _get_placement(w["hwnd"])}
            for w in windows() if w["display"] in names]
    LAYOUT.write_text(json.dumps({"physical": phys, "windows": wins}), encoding="utf-8")
    u = _ensure(1)
    if not u:
        raise RuntimeError("user screen did not attach")
    w, h, hz = _best_mode(u["name"], prim["w"], prim["h"])
    _stage(u["name"], 0, 0, w, h, hz, primary=True)
    a = _attached_role(0)
    if a:
        _stage(a["name"], w, 0, a["w"], a["h"], a["hz"])
    for d in phys:
        _stage(d["name"])
    _apply()
    _to_user_screen()
    _spawn_watcher()
    _spawn_guard()


def to_extend():
    """Screen on again: physical layout and the user's windows come back, the user screen is detached.
    The agent screen keeps its on/off state."""
    _kill_watcher()
    if not screen_off():
        return
    try:
        lay = json.loads(LAYOUT.read_text(encoding="utf-8"))
    except Exception:
        lay = None
    try:
        if not lay:
            raise RuntimeError("no saved layout")
        for d in lay["physical"]:
            _stage(d["name"], d["x"], d["y"], d["w"], d["h"], d["hz"], primary=d["primary"])
        right = max(d["x"] + d["w"] for d in lay["physical"])
        top = min(d["y"] for d in lay["physical"])
        a = _attached_role(0)
        if a:
            _stage(a["name"], right, top, a["w"], a["h"], a["hz"])
        u = _attached_role(1)
        if u:
            _stage(u["name"])
        _apply()
    except Exception:
        agent = agent_on()
        subprocess.run(["DisplaySwitch.exe", "/extend"], timeout=20)
        time.sleep(3)
        for i, keep in ((1, False), (0, agent)):
            d = _attached_role(i)
            if d and not keep and not screen_off():
                _stage(d["name"])
        _apply()
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
    """Screen off: every non-agent window on the agent screen goes to the user screen, the agent screen stays clean."""
    u, v = _attached_role(1), _attached_role(0)
    if not (u and v):
        return
    a = _allowed(); hw, pids = set(a.get("hwnds", [])), set(a.get("pids", []))
    me = os.getpid()
    for w in windows():
        if w["display"] == v["name"] and w["hwnd"] not in hw and w["pid"] not in pids and w["pid"] != me \
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
        d = _attached_role(1)
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
        a = _attached_role(0)
        _vb["r"] = [(a["x"], a["y"], a["x"] + a["w"], a["y"] + a["h"])] if a else None
        _vb["phys"] = [(d["x"], d["y"], d["x"] + d["w"], d["y"] + d["h"])
                       for d in displays() if d["attached"] and not (a and d["name"] == a["name"])]
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
        v = _attached_role(0)
        if not v:
            if not _attached_role(1):
                GUARD_PID.unlink(missing_ok=True); return
            continue   # only the user screen is on: nothing to guard
        if u32.GetAsyncKeyState(0x01) & 0x8000:   # mid-drag
            continue
        if screen_off():
            _to_user_screen()
            continue
        a = _allowed(); hw, pids = set(a.get("hwnds", [])), set(a.get("pids", []))
        vnames = {v["name"]}
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
        AGENT_INPUT.touch()   # let the agent's own cursor onto the virtual screen
        self.fg = u32.GetForegroundWindow(); self.pt = W.POINT(); u32.GetCursorPos(ctypes.byref(self.pt)); return self

    def __exit__(self, *a):
        u32.SetCursorPos(self.pt.x, self.pt.y)
        AGENT_INPUT.unlink(missing_ok=True)
        if self.fg:
            u32.keybd_event(0x12, 0, 0, 0); u32.keybd_event(0x12, 0, 2, 0); u32.SetForegroundWindow(self.fg)


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
        raise RuntimeError("virtual display is off")
    out = Path(out or STATE / f"shot-{int(time.time())}.png")
    ImageGrab.grab(bbox=(v["x"], v["y"], v["x"] + v["w"], v["y"] + v["h"]), all_screens=True).save(out)
    return str(out)


# ---------------------------------------------------------------- CLI
def status():
    v = vdd()
    return {"installed": bool(v), "agent": agent_on(), "screen_off": bool(v) and screen_off(),
            "attached": agent_on(), "mode": mode_now() if v else "off", "user_screen_available": bool(vuser()),
            "virtual": v, "user_screen": vuser(), "physical": [d for d in physical() if d["attached"]]}


def main(a):
    if not a or a[0] in ("-h", "--help", "help"):
        print(__doc__); return 0
    c, r = a[0], a[1:]
    if c == "_watch":
        _watch(); return 0
    if c == "_guard":
        _guard(); return 0
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
