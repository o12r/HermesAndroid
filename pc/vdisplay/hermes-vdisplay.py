"""hermes-vdisplay: per-session agent screens + screen-off for Hermes on the open-source SudoVDA driver (SudoMaker, MIT).

ONE resident daemon (`_daemon`, started on demand, exits when nothing is wanted) owns every virtual monitor: the
screen-off stand-ins and the agents' screens. The CLI is a thin client that sends commands to it over a localhost
socket (127.0.0.1, random port + token in daemon.json). The daemon is the single source of truth: it writes
state.json after every change, and every status/screens answer comes from it, never from guessing at the display
topology. "screen off" and "agent screen" are fully independent: toggling one never changes the other.

  status                         JSON: installed / agent / screen_off / agents / geometry / daemon
  install | uninstall            install-vdd.ps1 (one UAC prompt); also registers the elevated daemon task
  install-task                   only (re)register the elevated task HermesVDisplayGuard (one UAC prompt)
  on | off  (agent on|off)       this session's agent screen on / off (session = $HERMES_VDISPLAY_SESSION or
                                 $HERMES_SESSION_ID, else "manual"); never touches the screen-off state
  agents                         JSON: open agent screens {session: display}
  screens                        JSON: every screen (phone screen manager)
  screen off | screen on [disp]  physical screens go dark; one stand-in per physical screen takes its windows; real
                                 keyboard/mouse input turns them back on. Legacy: mode extend|second-only
  recover                        after a daemon crash: bring the physical screens back, re-create the agent screens
  daemon-stop                    screen on, close every agent screen, stop the daemon
  windows                        visible top-level windows: hwnd, pid, display, title
  launch -- <cmd> [args]         start a program and put its windows on the agent screen, without focus
  move <win> virtual|main        move a window (no activation for virtual)
  show <win>                     bring a window to the main screen for the user (wakes it if needed)
  bg-click <win> X Y [right]     background click via window messages, X/Y in screen coordinates
  bg-type <win> <text>           background typing via WM_CHAR            -> {ok, sent, failed, chars, target}
  bg-key <win> <VK>              background key press (VK code or name)  -> {ok, key, target}
  click X Y [right]              fallback: real mouse click, then the user's foreground window + cursor are restored
  type <win> <text>              fallback: real keyboard input to the window, then focus is restored
  shot [out.png]                 screenshot of the agent screen (prints the path)

<win> = hwnd (decimal or 0x...), pid:<n>, or a case-insensitive title substring.
Stdlib + ctypes only (Pillow only for `shot`). Windows 10/11.
"""
import ctypes
import json
import os
import re
import socket
import subprocess
import sys
import threading
import time
from ctypes import wintypes as W
from pathlib import Path

if sys.platform != "win32":
    sys.exit("hermes-vdisplay runs on Windows only")

API = 2   # CLI/daemon protocol version (see README: relay >= this version expects it)
u32 = ctypes.WinDLL("user32", use_last_error=True)
k32 = ctypes.WinDLL("kernel32", use_last_error=True)
try:
    u32.SetProcessDpiAwarenessContext(ctypes.c_void_p(-4))   # per-monitor v2: real pixel coordinates
except Exception:
    pass

HERE = Path(__file__).resolve().parent
STATE = Path(os.environ.get("LOCALAPPDATA", str(Path.home()))) / "hermes" / "vdisplay"
STATE.mkdir(parents=True, exist_ok=True)
LAYOUT = STATE / "layout.json"       # physical layout + window placements saved before screen off (daemon-written)
DAEMON_F = STATE / "daemon.json"     # {pid, port, token, api}: how the CLI reaches the daemon
DSTATE = STATE / "state.json"        # the daemon's truth: {pid, screen_off: {off, standins}, agents: {sid: {...}}}
AGENT_WINS = STATE / "agent-windows.json"   # windows the agent put on the virtual screen; everything else is moved off
SVDA_NAMES = ("sudomaker virtual display adapter",)   # SudoVDA: every Hermes monitor (agent + screen-off)
INPUT_LOCK = STATE / "real-input.lock"   # one session at a time may drive the real mouse/keyboard


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


def _pid_alive(pid):
    try:
        pid = int(pid)
    except Exception:
        return False
    h = k32.OpenProcess(0x1000, False, pid)   # PROCESS_QUERY_LIMITED_INFORMATION
    if not h:
        return False
    code = W.DWORD(); k32.GetExitCodeProcess(h, ctypes.byref(code)); k32.CloseHandle(h)
    return code.value == 259   # STILL_ACTIVE


def dstate():
    """The daemon's published state ({} when no daemon is running: nothing is wanted then)."""
    s = _jload(DSTATE, {})
    return s if s.get("pid") and _pid_alive(s["pid"]) else {}


def _agents_state():
    return (_D.agents if _D is not None else dstate().get("agents")) or {}


def displays():
    out, i = [], 0
    agents = {a.get("name") for a in _agents_state().values()}
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


def _has_monitor(name):
    """A monitor is plugged into this display slot (SudoVDA keeps ~10 permanent, mostly empty DISPLAYn slots)."""
    d = DISPLAY_DEVICEW(); d.cb = ctypes.sizeof(d)
    return bool(u32.EnumDisplayDevicesW(name, 0, ctypes.byref(d), 0))


def vdds():
    """Every attached agent screen (all sessions)."""
    return [d for d in displays() if d["virtual"] and d["attached"]]


def vdd(sid=None):
    """This session's agent screen (attached), or None."""
    name = (_agents_state().get(sid or _sid()) or {}).get("name")
    return next((d for d in displays() if d["name"] == name and d["attached"]), None) if name else None


def svdas():
    """Screen-off stand-ins (SudoVDA monitors that are not agent screens): exist only while the screen is off."""
    return [d for d in displays() if d["adapter"].strip().lower() in SVDA_NAMES and d["attached"]
            and not d["virtual"]]


def vuser():
    """Main screen while some screens are off: the primary among the lit physical screens and the stand-ins (a
    turned-off main screen hands its role to its stand-in), or None when no screen is off."""
    s = svdas()
    if not s:
        return None
    lit = s + [d for d in physical() if d["attached"]]
    return next((d for d in lit if d["primary"]), s[0])


def physical():
    # Other virtual adapters (Parsec, SudoVDA monitors) are left out.
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


def _stage(name, x=0, y=0, w=0, h=0, hz=0, primary=False):
    m = DEVMODEW(); m.dmSize = ctypes.sizeof(m)
    m.dmFields = DM_POSITION | DM_PELSWIDTH | DM_PELSHEIGHT | (DM_FREQ if hz else 0)
    m.dmPositionX, m.dmPositionY, m.dmPelsWidth, m.dmPelsHeight, m.dmDisplayFrequency = x, y, w, h, hz
    flags = CDS_UPDATEREGISTRY | CDS_NORESET | (CDS_SET_PRIMARY if primary else 0)
    r = u32.ChangeDisplaySettingsExW(name, ctypes.byref(m), None, flags, None)
    if r != 0:
        raise RuntimeError(f"ChangeDisplaySettingsEx({name}) failed: {r}")


def _ccd_detach(names):
    """Detach GDI display(s) with SetDisplayConfig (active paths minus the ones of `names`, saved to the database)."""
    names = {n.upper() for n in names}
    n_p, n_m = ctypes.c_uint32(), ctypes.c_uint32()
    if u32.GetDisplayConfigBufferSizes(2, ctypes.byref(n_p), ctypes.byref(n_m)):   # QDC_ONLY_ACTIVE_PATHS
        raise RuntimeError("GetDisplayConfigBufferSizes failed")
    paths = (ctypes.c_ubyte * (72 * n_p.value))(); modes = (ctypes.c_ubyte * (64 * n_m.value))()
    r = u32.QueryDisplayConfig(2, ctypes.byref(n_p), paths, ctypes.byref(n_m), modes, None)
    if r:
        raise RuntimeError(f"QueryDisplayConfig failed: {r}")
    keep = []
    for i in range(n_p.value):
        p = bytes(paths[i * 72:(i + 1) * 72])
        hdr = (ctypes.c_ubyte * 84)()   # DISPLAYCONFIG_SOURCE_DEVICE_NAME: header(20) + WCHAR[32]
        hdr[0:4] = (1).to_bytes(4, "little"); hdr[4:8] = (84).to_bytes(4, "little"); hdr[8:20] = p[0:12]
        gdi = ""
        if u32.DisplayConfigGetDeviceInfo(hdr) == 0:
            gdi = bytes(hdr[20:84]).decode("utf-16-le").split(chr(0))[0]
        if gdi.upper() not in names:
            keep.append(p)
    buf = (ctypes.c_ubyte * (72 * len(keep)))()
    for i, p in enumerate(keep):
        buf[i * 72:(i + 1) * 72] = p
    # SDC_APPLY | SDC_USE_SUPPLIED_DISPLAY_CONFIG | SDC_SAVE_TO_DATABASE | SDC_ALLOW_CHANGES
    r = u32.SetDisplayConfig(len(keep), buf, n_m.value, modes, 0x80 | 0x20 | 0x200 | 0x400)
    if r:
        raise RuntimeError(f"SetDisplayConfig failed: {r}")
    time.sleep(1.0)


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
        _tlog("stage refused -> DisplaySwitch /extend")
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


# ---------------------------------------------------------------- the daemon
_D = None   # the Daemon (set only inside the daemon process)
_T0 = time.time()


def _tlog(msg):
    """Step log (state/timing.log): every daemon decision lands here."""
    try:
        with open(STATE / "timing.log", "a", encoding="utf-8") as f:
            f.write(f"{time.strftime('%H:%M:%S')} pid{os.getpid()} +{time.time() - _T0:6.2f}s {msg}\n")
    except OSError:
        pass


GUARD_TASK = "HermesVDisplayGuard"   # scheduled task (RunLevel Highest) that starts the daemon elevated: `_daemon`
_WARNINGS = []


def _is_admin():
    try:
        return bool(ctypes.windll.shell32.IsUserAnAdmin())
    except Exception:
        return False


def _norm(names):
    return {str(n).upper() for n in names}


def _so():
    return ((_D.so if _D is not None else dstate().get("screen_off")) or {})


def off_names():
    """Physical screens that are off right now (as the daemon recorded them, never guessed from the topology)."""
    return set(_so().get("off") or [])


def screen_off():
    return bool(off_names())


def agent_on():
    v = vdd()
    return bool(v and v["attached"])


def mode_now():
    """Legacy summary: off | extend | second-only (screen-off wins)."""
    if screen_off():
        return "second-only"
    return "extend" if agent_on() else "off"


def _right_edge(skip=()):
    """x right of every attached display except the screens in `skip`."""
    ds = [d for d in displays() if d["attached"] and d["name"] not in skip and "parsec" not in d["adapter"].lower()]
    return (max(d["x"] + d["w"] for d in ds), min(d["y"] for d in ds)) if ds else (0, 0)


def _park_agents(right, top, skip=()):
    """Stage every attached agent screen in a row right of x=right (no apply)."""
    x = right
    for a in vdds():
        if a["name"] in skip:
            continue
        _stage(a["name"], x, top, a["w"], a["h"], a["hz"]); x += a["w"]


def to_extend():
    """Physical screens back (saved layout), stand-ins removed, user windows restored. Agent screens are not
    touched except for being parked right of the physical screens. Usable inside the daemon and (recovery after a
    daemon crash) from the CLI."""
    try:
        lay = json.loads(LAYOUT.read_text(encoding="utf-8"))
    except Exception:
        lay = None
    sv = svdas()
    phys_att = [d for d in physical() if d["attached"]]
    if sv or lay or not phys_att:
        try:
            if not lay:
                raise RuntimeError("no saved layout")
            for d in lay["physical"]:
                _stage_new(d["name"], d["x"], d["y"], d["w"], d["h"], d["hz"], primary=d["primary"])
            _park_agents(max(d["x"] + d["w"] for d in lay["physical"]), min(d["y"] for d in lay["physical"]))
            for s_ in sv:
                _stage(s_["name"])
            _apply()
            for _ in range(6):   # staging can return 0 without the screen attaching (single-screen topology)
                if all(any(p_["name"] == d["name"] and p_["attached"] for p_ in physical()) for d in lay["physical"]):
                    break
                time.sleep(0.5)
            else:
                raise RuntimeError("physical screen did not attach after staging")
        except Exception as e:
            _tlog(f"to_extend staged path failed ({e}) -> DisplaySwitch /extend")
            subprocess.run(["DisplaySwitch.exe", "/extend"], timeout=20)
            time.sleep(3)
    own = _D.h if _D is not None else _svda_open()
    if own:
        for i in list(range(8)) + list(range(110, 118)):   # stand-ins + their kick monitors; agent GUIDs untouched
            _svda_io(own, SVDA_REMOVE, _svda_guid(i).bytes_le)
        if _D is None:
            k32.CloseHandle(own)
    _restore_windows(lay)


class Daemon:
    """Owns every SudoVDA monitor. All mutations happen here, serialized by self.lock; the CLI only asks."""

    def __init__(self):
        self.lock = threading.RLock()
        self.agents = {}   # sid -> {name, guids, home, cwd, t}
        self.so = {}       # {off: [phys names], standins: {phys: name}, t}
        self.h = None
        self.token = os.urandom(16).hex()
        self.stop = threading.Event()
        self.srv = None
        self.busy = False   # a monitor change is in progress (the tray must not touch the taskbar meanwhile)
        self.base = []      # physical screens that are supposed to be on (name + geometry), independent of screen-off

    # -- state ------------------------------------------------------------------------------------------
    def publish(self):
        st = {"api": API, "pid": os.getpid(), "t": time.time(), "screen_off": self.so, "agents": self.agents,
              "elevated": _is_admin(), "busy": self.busy}
        tmp = DSTATE.with_name("state.tmp")
        tmp.write_text(json.dumps(st), encoding="utf-8")
        os.replace(tmp, DSTATE)

    def summary(self):
        return {"ok": True, "mode": mode_now(), "agent": agent_on(), "screen_off": screen_off(),
                "off": sorted(off_names()), "agents": {k: v["name"] for k, v in self.agents.items()},
                "elevated": _is_admin()}

    # -- monitors ---------------------------------------------------------------------------------------
    @staticmethod
    def _slots():
        return {d["name"] for d in displays() if d["adapter"].strip().lower() in SVDA_NAMES
                and (d["attached"] or _has_monitor(d["name"]))}

    def add_visible(self, guid, idx, mode, label, serial, kick_guid, kick_idx, taken=()):
        """Add a monitor and return (display name, [guids]). SudoVDA may keep the newest monitor hidden until one
        more is added after it (observed, esp. in the screen-off topology): then add a 'kick' monitor and take
        whichever new one shows up; both GUIDs are returned so they are removed together."""
        before = self._slots() | set(taken)
        _pa = lambda: [d["name"] for d in physical() if d["attached"]]
        _tlog(f"add {label}: before phys_att={_pa()}")

        def newest():
            sv = [d for d in displays() if d["adapter"].strip().lower() in SVDA_NAMES and d["name"] not in before
                  and (d["attached"] or _has_monitor(d["name"]))]
            att = [d for d in sv if d["attached"]]
            pick = att or sv
            return max(pick, key=lambda d: int(re.sub(r"\D", "", d["name"]) or 0))["name"] if pick else None

        if not _svda_add(self.h, idx, *mode, guid=guid, label=label[:13], serial=serial):
            raise RuntimeError(f"SudoVDA add failed ({ctypes.get_last_error()})")
        guids = [guid]
        name = None
        for _ in range(12):
            time.sleep(0.25)
            name = newest()
            if name:
                break
        if not name:
            _tlog(f"add {label}: hidden after 3 s -> kick")
            kick_label = f"Hermes Kick{kick_idx}"
            _svda_add(self.h, kick_idx, *mode, guid=kick_guid, label=kick_label[:13], serial=f"HRMSKCK{kick_idx:04d}")
            guids.append(kick_guid)
            for _ in range(24):
                time.sleep(0.25)
                name = newest()
                if name:
                    break
        if not name:
            for g in guids:
                _svda_io(self.h, SVDA_REMOVE, g.bytes_le)
            raise RuntimeError("SudoVDA monitor did not appear")
        _tlog(f"add {label} -> {name} guids={len(guids)} phys_att={_pa()}")
        return name, guids

    def reassert_off(self):
        """After every monitor add/remove the daemon re-asserts the desired physical state, per screen: a screen that
        is supposed to be off must be detached, one that is supposed to be on must be attached (Windows' stored
        topology for a new monitor set sometimes drops a physical screen)."""
        off = set(self.so.get("off") or [])
        phys = {d["name"]: d for d in physical()}
        att_off = [d for n, d in phys.items() if n in off and d["attached"]]
        det_on = [b for b in self.base if b["name"] not in off and not (phys.get(b["name"]) or {}).get("attached")]
        _tlog(f"reassert check: off={sorted(off)} phys_att={[n for n, d in phys.items() if d['attached']]} "
              f"detached_but_wanted={[b['name'] for b in det_on]}")
        lost = {}
        if off and not getattr(self, "_healing", False):   # a stand-in that vanished (Windows dropped it) leaves no main screen
            have = {d["name"] for d in svdas()}
            lost = {p_: n_ for p_, n_ in (self.so.get("standins") or {}).items() if n_ not in have}
            if not lost and not self.so.get("standins") and not have:
                lost = {n_: "" for n_ in off}
        if lost:
            _tlog(f"reassert: stand-in lost {lost} -> rebuilding the screen-off layout")
            self._healing = True
            try:
                self.extend()
                self.setup_off(set(off))
            finally:
                self._healing = False
            return
        if not att_off and not det_on:
            return
        for b in det_on:   # physical screen that lost its attachment -> bring it back where it was
            _tlog(f"reassert: {b['name']} detached but wanted on -> re-attaching")
            _stage_new(b["name"], b["x"], b["y"], b["w"], b["h"], b["hz"], primary=b["primary"])
        for s_ in svdas():   # keep the stand-ins / agent screens (and the main role) in the same commit
            if s_["attached"]:
                _stage(s_["name"], s_["x"], s_["y"], s_["w"], s_["h"], s_["hz"], primary=s_["primary"])
        for d in att_off:
            _stage(d["name"])
        _apply()
        phys = {d["name"]: d for d in physical()}
        still = [d["name"] for d in att_off if phys.get(d["name"], {}).get("attached")]
        miss = [b["name"] for b in det_on if not phys.get(b["name"], {}).get("attached")]
        _tlog(f"reassert: still attached={still} still detached={miss}")
        if still or miss:
            raise RuntimeError(f"could not keep {still} off / {miss} on")

    # -- agent screens ----------------------------------------------------------------------------------
    def agent_on(self, sid, home="", cwd=""):
        if not self.h:
            raise RuntimeError("SudoVDA driver not installed (run: hermes-vdisplay install)")
        a = self.agents.get(sid)
        if a and vdd(sid):
            a.update(home=home or a.get("home", ""), cwd=cwd or a.get("cwd", ""))
            return
        if a:
            self.drop_agent(sid, evacuate=False)
        used = {int(re.sub(r"\D", "", v["label"]) or 0) for v in self.agents.values()}
        n = next(i for i in range(1, 99) if i not in used)
        _tlog(f"agent_on {sid} n={n} off={sorted(off_names())} phys_att={[d['name'] for d in physical() if d['attached']]}")
        name, guids = self.add_visible(_agent_guid(sid), 0, AGENT_MODE, f"Hermes Agent{n}", f"HRMSAGT{n:04d}",
                                       _svda_guid(100 + n), 100 + n)
        self.agents[sid] = {"name": name, "guids": [str(g) for g in guids], "label": f"Hermes Agent{n}",
                            "home": home, "cwd": cwd, "t": time.time()}
        self.publish()
        try:
            time.sleep(0.5)
            self.reassert_off()   # the add alone may already have dropped a physical screen
            x, y = _right_edge(skip={name})
            w, h, hz = _best_mode(name, *AGENT_MODE)
            cur = next((d for d in displays() if d["name"] == name), None)
            if not (cur and cur["attached"] and (cur["x"], cur["y"], cur["w"], cur["h"]) == (x, y, w, h)):
                # Windows may leave a new monitor detached (or cloned): place + attach it ourselves, right of the rest
                _stage_new(name, x, y, w, h, hz)
                for d in physical():   # the commit re-attaches off screens whose registry mode remains: detach again
                    if d["name"] in off_names():
                        try:
                            _stage(d["name"])
                        except RuntimeError:
                            pass
                _apply()
            self.reassert_off()
            if not vdd(sid):
                raise RuntimeError("agent screen did not attach")
        except Exception:
            self.drop_agent(sid, evacuate=False)
            raise
        _tlog(f"agent_on done {sid} -> {name} off={sorted(off_names())} phys_att={[d['name'] for d in physical() if d['attached']]}")

    def drop_agent(self, sid, evacuate=True):
        a = self.agents.get(sid)
        if not a:
            return
        moved = []
        if evacuate:
            try:
                moved = _evacuate({a["name"]})   # windows on the dying screen go back to the main screen first
            except Exception:
                pass
        import uuid
        for g in a.get("guids", []):
            _svda_io(self.h, SVDA_REMOVE, uuid.UUID(g).bytes_le)
        del self.agents[sid]
        self.publish()
        time.sleep(1.0)
        try:
            _rescue_orphans()
        except Exception:
            pass
        self.reassert_off()
        try:
            _keep_sizes(moved)   # the monitor removal / topology change rescales windows again
        except Exception:
            pass
        _tlog(f"agent_off {sid} done phys_att={[d['name'] for d in physical() if d['attached']]} off={sorted(off_names())}")

    def agent_off(self, sid):
        self.drop_agent(sid)

    # -- screen off / on --------------------------------------------------------------------------------
    def screen_off(self, targets=None):
        if not self.h:
            raise RuntimeError("screen-off needs the SudoVDA driver (reinstall: hermes-vdisplay install)")
        cur = off_names()
        allp = _jload(LAYOUT, {}).get("physical", []) if cur else [d for d in physical() if d["attached"]]
        want = {d["name"] for d in allp} if targets is None else (_norm(targets) & {d["name"].upper() for d in allp})
        want = {d["name"] for d in allp if d["name"].upper() in want} | cur
        if not want:
            raise RuntimeError("no such physical screen")
        if want == cur:
            return
        if cur:
            self.extend()   # rebuild from the full physical layout
        self.setup_off(want)

    def screen_on(self, targets=None):
        cur = off_names()
        rest = set() if targets is None else {n for n in cur if n.upper() not in _norm(targets)}
        if rest == cur:
            return
        self.extend()
        if rest:
            self.setup_off(rest)

    def extend(self):
        snap = _agent_rects()   # Windows rescales/reshuffles windows when a physical screen re-attaches
        try:
            snap2 = _standin_snap()
        except Exception:
            snap2 = []
        to_extend()
        try:
            _agent_rects_restore(snap)
        except Exception:
            pass
        try:
            _standin_restore(snap2)
        except Exception as e:
            _tlog(f"standin_restore failed: {e}")
        self.so = {}
        self.publish()
        _tlog(f"extend done phys_att={[d['name'] for d in physical() if d['attached']]} agents={[a['name'] for a in self.agents.values()]}")

    def setup_off(self, want):
        _tlog(f"screen off start want={sorted(want)}")
        asnap = _agent_rects()
        phys = [d for d in physical() if d["attached"]]
        names = {d["name"] for d in phys} & set(want)
        wins = [{"hwnd": w["hwnd"], "pid": w["pid"], "title": w["title"], "pl": _get_placement(w["hwnd"]),
                 "rect": w["rect"], "zoomed": bool(u32.IsZoomed(w["hwnd"])), "iconic": bool(u32.IsIconic(w["hwnd"])),
                 "dpi": _dpi(w["hwnd"])}
                for w in windows() if w["display"] in names]
        for p in phys:
            try:
                p["dpi"] = _eff_dpi(p["name"])   # read only; the physical screen's own scaling is never written
            except Exception:
                p["dpi"] = 0
        LAYOUT.write_text(json.dumps({"physical": phys, "off": sorted(names), "windows": wins}), encoding="utf-8")
        pairs = []
        try:
            for i, p in enumerate(phys):
                if p["name"] not in names:
                    continue
                for hz_try in dict.fromkeys([p["hz"] or USER_HZ, USER_HZ]):   # physical refresh first, 30 Hz floor after
                    try:
                        got, _g = self.add_visible(_svda_guid(i), i, (p["w"], p["h"], hz_try), f"Hermes Off {i + 1}",
                                                   f"HRMSOFF{i + 1:04d}", _svda_guid(110 + i), 110 + i,
                                                   taken={n for _, n in pairs})
                        break
                    except RuntimeError as e:
                        _tlog(f"stand-in {p['w']}x{p['h']}@{hz_try} failed ({e})")
                        if hz_try == USER_HZ:
                            raise
                pairs.append((p, got))
            for p, name in pairs:
                w, hh, hz = _best_mode(name, p["w"], p["h"], p["hz"] or USER_HZ)
                _stage_new(name, p["x"], p["y"], w, hh, hz, primary=p["primary"])
            _park_agents(max(p["x"] + p["w"] for p in phys), min(p["y"] for p in phys))   # agent screens stay right
            # two commits: stand-ins attach first, then the physical screens detach (Windows refuses -1 when it
            # would leave the committed topology without an active screen: the stand-in only exists after _apply)
            _apply()
            for _ in range(10):
                if all(any(d["name"] == n and d["attached"] for d in displays()) for _p, n in pairs):
                    break
                time.sleep(0.5)
            _tlog(f"standins committed: {[(n, any(d['name'] == n and d['attached'] for d in displays())) for _p, n in pairs]}"
                  f" phys_att={[d['name'] for d in physical() if d['attached']]}")
            for p, n in pairs:   # same scaling as the physical screen, so windows keep their size on screen on
                try:
                    _match_scale(n, p.get("dpi", 0))
                except Exception as e:
                    _tlog(f"match_scale error {e}")
            _tlog("before detach: " + str([(d["name"][4:], d["x"], d["y"], d["w"], d["h"], d["primary"])
                                           for d in displays() if d["attached"]]))
            # Detach through the CCD API: with an agent screen present ChangeDisplaySettingsEx(name, 0x0) answers -1
            # (DISP_CHANGE_FAILED) for the physical screen; SetDisplayConfig with that path dropped works (observed).
            # The stand-ins take the physical screens' slot + primary role first, the physical screens move aside.
            so_slot = {n: p for p, n in pairs}
            x_ = max(p["x"] + p["w"] for p in phys)
            for s_ in svdas():
                if s_["attached"] and s_["name"] in so_slot:
                    p_ = so_slot[s_["name"]]
                    _stage(s_["name"], p_["x"], p_["y"], s_["w"], s_["h"], s_["hz"], primary=p_["primary"])
            for p, _n in pairs:
                _stage(p["name"], x_, p["y"], p["w"], p["h"], p["hz"]); x_ += p["w"]
            _park_agents(x_, min(p["y"] for p in phys))
            _apply()
            for p, _n in pairs:
                try:
                    _stage(p["name"])   # old way first: works when no agent screen exists
                    _apply()
                except RuntimeError as e:
                    _tlog(f"detach {p['name']} refused ({e}) -> SetDisplayConfig")
                    _ccd_detach([q["name"] for q, _x in pairs])
                    break
            _tlog("after detach: " + str([(d["name"][4:], d["x"], d["y"], d["w"], d["h"], d["primary"])
                                          for d in displays() if d["attached"]]))
            for p, n in pairs:   # topology change may reset it: verify again, log the result
                try:
                    if p.get("dpi") and _eff_dpi(n) != p["dpi"]:
                        _match_scale(n, p["dpi"])
                    _tlog(f"standin {n}: dpi {_eff_dpi(n)} (physical {p.get('dpi')}) mode {[(d['w'], d['h'], d['hz']) for d in displays() if d['name'] == n]}")
                except Exception as e:
                    _tlog(f"dpi verify error {e}")
            self.so = {"off": sorted(names), "standins": {p["name"]: n for p, n in pairs}, "t": time.time()}
            self.publish()
            _tlog(f"applied: off={sorted(names)} phys_att={[d['name'] for d in physical() if d['attached']]}")
            time.sleep(1.0)
            for w in wins:   # same coordinates as before, so each window lands on its physical screen's stand-in
                if u32.IsWindow(w["hwnd"]) and _pid(w["hwnd"]) == w["pid"]:
                    try:
                        _set_placement(w["hwnd"], w["pl"])
                    except Exception:
                        pass
            _to_user_screen()
            try:
                _agent_rects_restore(asnap)   # keep agent windows at their exact size (Windows rescales them on topology changes)
            except Exception:
                pass
        except Exception:
            self.so = {"off": sorted(names), "standins": {}, "t": time.time()}
            self.extend()
            raise
        self.t_off = time.time()

    # -- requests ---------------------------------------------------------------------------------------
    def handle(self, req):
        cmd, a, env = req.get("cmd"), req.get("args") or {}, req.get("env") or {}
        with self.lock:
            self.busy = True
            try:
                self.publish()
            except Exception:
                pass
            try:
                return self._handle(cmd, a, env)
            finally:
                self.busy = False
                try:
                    self.publish()
                except Exception:
                    pass

    def _handle(self, cmd, a, env):
        if True:
            if cmd == "agent_on":
                self.agent_on(env.get("sid") or "manual", env.get("home", ""), env.get("cwd", ""))
            elif cmd == "agent_off":
                self.agent_off(env.get("sid") or "manual")
            elif cmd == "screen_off":
                self.screen_off(a.get("targets"))
            elif cmd == "screen_on":
                self.screen_on(a.get("targets"))
            elif cmd == "place":   # window moves of elevated apps must come from this (elevated) process
                h = int(a["hwnd"])
                old = os.environ.get("HERMES_VDISPLAY_SESSION")
                os.environ["HERMES_VDISPLAY_SESSION"] = env.get("sid") or "manual"   # vdd() resolves the caller's screen
                try:
                    _place(h, a.get("where", "main"))
                finally:
                    if old is None:
                        os.environ.pop("HERMES_VDISPLAY_SESSION", None)
                    else:
                        os.environ["HERMES_VDISPLAY_SESSION"] = old
                if a.get("restore") and u32.IsIconic(h):
                    u32.ShowWindow(h, 9)   # SW_RESTORE
            elif cmd == "stop":
                for sid in list(self.agents):
                    self.drop_agent(sid)
                if off_names():
                    self.extend()
            elif cmd != "ping":
                raise RuntimeError(f"unknown daemon command {cmd!r}")
            self.publish()
            res = self.summary()
            if not self.agents and not self.so:
                self.stop.set()   # nothing wanted any more: the daemon (and so every monitor) goes away
            return res

    def serve_conn(self, c):
        try:
            c.settimeout(300)
            buf = b""
            while not buf.endswith(b"\n"):
                chunk = c.recv(65536)
                if not chunk:
                    return
                buf += chunk
            req = json.loads(buf.decode("utf-8"))
            if req.get("token") != self.token:
                res = {"ok": False, "error": "bad token"}
            else:
                try:
                    res = self.handle(req)
                except Exception as e:
                    import traceback
                    _tlog("request failed: " + traceback.format_exc().replace("\n", " | "))
                    _errlog(f"daemon request {req.get('cmd')}", traceback.format_exc())
                    res = {"ok": False, "error": str(e) or type(e).__name__}
            c.sendall((json.dumps(res) + "\n").encode("utf-8"))
        except Exception:
            pass
        finally:
            c.close()

    # -- background threads -----------------------------------------------------------------------------
    def ping_loop(self):   # SudoVDA drops its monitors ~3 s after the pings stop
        while True:
            _svda_io(self.h, SVDA_PING); time.sleep(0.5)

    def guard_loop(self):
        """Keep the agent screens for the agent (windows not in agent-windows.json go to the main screen, no
        activation) and the user's mouse off them; while the screen is off, stray windows go to the main stand-in."""
        me = os.getpid()
        last = 0.0
        while True:
            time.sleep(0.02)
            try:
                _fence()
                if time.time() - last < 0.5:
                    continue
                last = time.time()
                if not self.lock.acquire(blocking=False):
                    continue   # a monitor change is in progress
                try:
                    vs = vdds()
                    if not vs or u32.GetAsyncKeyState(0x01) & 0x8000:   # no agent screen / mid-drag
                        continue
                    if screen_off():
                        _to_user_screen()
                        continue
                    a = _allowed(); hw, pids = set(a.get("hwnds", [])), set(a.get("pids", []))
                    vnames = {v["name"] for v in vs}
                    for w in windows():
                        if w["display"] in vnames and w["hwnd"] not in hw and w["pid"] not in pids \
                                and w["pid"] != me and not _cloaked(w["hwnd"]):
                            try:
                                _place(w["hwnd"], "main")
                            except Exception:
                                pass
                finally:
                    self.lock.release()
            except Exception:
                import traceback
                _errlog("guard", traceback.format_exc()); time.sleep(2)

    def reaper_loop(self):
        """A session's screen is dropped once the session is open on neither the phone nor the PC (gone from the
        gateway's live list on REAP_MISSES consecutive 60 s polls, i.e. ~10 min)."""
        REAP_MISSES = 10
        missing = {}
        while True:
            time.sleep(60)
            try:
                homes = {}
                with self.lock:
                    for sid, r in self.agents.items():
                        if sid != "manual" and r.get("home"):
                            homes.setdefault(r["home"], []).append(sid)
                for home, sids in homes.items():
                    live = _live_sessions(home)
                    if live is None:
                        continue
                    for sid in sids:
                        missing[sid] = missing.get(sid, 0) + 1 if sid not in live else 0
                        if missing[sid] >= REAP_MISSES:
                            missing.pop(sid, None)
                            _tlog(f"reaper: session {sid} gone from the live list -> agent off")
                            self.handle({"cmd": "agent_off", "env": {"sid": sid}})
            except Exception:
                import traceback
                _errlog("reaper", traceback.format_exc())

    def wake_loop(self):
        """Low-level hooks; injected input (the agent's own SendInput) is ignored, real input turns the screens
        back on (screen on only: the agent screens are not touched)."""
        LRESULT = ctypes.c_ssize_t
        HOOK = ctypes.WINFUNCTYPE(LRESULT, ctypes.c_int, W.WPARAM, W.LPARAM)
        u32.CallNextHookEx.argtypes = [W.HHOOK, ctypes.c_int, W.WPARAM, W.LPARAM]
        u32.CallNextHookEx.restype = LRESULT
        u32.SetWindowsHookExW.argtypes = [ctypes.c_int, HOOK, W.HINSTANCE, W.DWORD]
        u32.SetWindowsHookExW.restype = W.HHOOK
        wake = threading.Event()

        def fire():
            if self.so and time.time() > getattr(self, "t_off", 0) + 1.5:
                wake.set()

        @HOOK
        def kb(n, wp, lp):
            if n == 0 and not (ctypes.cast(lp, ctypes.POINTER(W.DWORD))[2] & 0x10):   # LLKHF_INJECTED
                fire()
            return u32.CallNextHookEx(None, n, wp, lp)

        @HOOK
        def ms(n, wp, lp):
            if n == 0 and not (ctypes.cast(lp, ctypes.POINTER(W.DWORD))[3] & 0x1):    # LLMHF_INJECTED
                fire()
            return u32.CallNextHookEx(None, n, wp, lp)

        k32.GetModuleHandleW.restype = ctypes.c_void_p   # default c_int truncates the 64-bit handle -> hook error 126
        k32.GetModuleHandleW.argtypes = [W.LPCWSTR]
        mod = k32.GetModuleHandleW(None)
        h1, h2 = u32.SetWindowsHookExW(13, kb, mod, 0), u32.SetWindowsHookExW(14, ms, mod, 0)
        _tlog(f"hooks kb={bool(h1)} ms={bool(h2)} err={ctypes.get_last_error()}")

        def waker():
            while True:
                wake.wait(); wake.clear()
                try:
                    _tlog("real input -> screen on")
                    self.handle({"cmd": "screen_on"})
                except Exception:
                    import traceback
                    _errlog("wake", traceback.format_exc())
        threading.Thread(target=waker, daemon=True).start()
        msg = W.MSG()
        while u32.GetMessageW(ctypes.byref(msg), None, 0, 0) > 0:
            pass

    # -- main -------------------------------------------------------------------------------------------
    def run(self):
        global _D
        if _daemon_info():
            return   # another daemon is already running
        self.h = _svda_open()
        if not self.h:
            return
        _D = self
        self.t_off = 0
        lay = _jload(LAYOUT, {}).get("physical")   # screens are off right now (daemon restarted): the layout knows them
        self.base = lay or [d for d in physical() if d["attached"]]
        _tlog(f"daemon base phys={[b['name'] for b in self.base]} elevated={_is_admin()}")
        threading.Thread(target=self.ping_loop, daemon=True).start()
        self.srv = socket.socket(); self.srv.bind(("127.0.0.1", 0)); self.srv.listen(8)
        DAEMON_F.write_text(json.dumps({"pid": os.getpid(), "port": self.srv.getsockname()[1], "token": self.token,
                                        "api": API}), encoding="utf-8")
        self.publish()
        _tlog(f"daemon up port={self.srv.getsockname()[1]}")

        def acceptor():
            while True:
                try:
                    c, _ = self.srv.accept()
                except OSError:
                    return
                threading.Thread(target=self.serve_conn, args=(c,), daemon=True).start()
        for t in (acceptor, self.guard_loop, self.reaper_loop, self.wake_loop):
            threading.Thread(target=t, daemon=True).start()
        while True:
            self.stop.wait()
            time.sleep(0.5)   # let the last reply go out
            with self.lock:
                if self.agents or self.so:   # something was requested in the meantime: keep running
                    self.stop.clear()
                    continue
                DAEMON_F.unlink(missing_ok=True); DSTATE.unlink(missing_ok=True)
            _tlog("daemon exit (nothing wanted)")
            os._exit(0)


def _errlog(what, tb):
    try:
        with open(STATE / "error.log", "a", encoding="utf-8") as f:
            f.write(f"{time.strftime('%H:%M:%S')} {what}\n{tb}\n")
    except OSError:
        pass


# ---------------------------------------------------------------- CLI -> daemon
def _daemon_info():
    d = _jload(DAEMON_F, {})
    return d if d.get("pid") and _pid_alive(d["pid"]) else {}


def _task_exists():
    try:
        return subprocess.run(["schtasks", "/Query", "/TN", GUARD_TASK], capture_output=True, timeout=15,
                              creationflags=0x08000000).returncode == 0
    except Exception:
        return False


def _wait_daemon(sec):
    for _ in range(int(sec * 4)):
        time.sleep(0.25)
        if _daemon_info():
            return True
    return False


def _spawn_daemon():
    """Start the daemon elevated (it must be able to move windows of elevated apps; UIPI blocks a normal process):
    already admin -> directly; else through the scheduled task (RunLevel Highest, no UAC prompt per start).
    No task -> start it unelevated and warn."""
    exe = Path(sys.executable)
    pyw = exe.with_name("pythonw.exe") if exe.with_name("pythonw.exe").exists() else exe
    if os.environ.get("HERMES_VDISPLAY_NO_TASK"):   # debugging escape hatch: plain unelevated daemon
        pass
    elif not _is_admin():
        if _task_exists():
            r = subprocess.run(["schtasks", "/Run", "/TN", GUARD_TASK], capture_output=True, timeout=30,
                               creationflags=0x08000000)
            if r.returncode == 0 and _wait_daemon(15):
                return
            _WARNINGS.append(f"scheduled task {GUARD_TASK} did not start the daemon (rc={r.returncode}); "
                             "started it WITHOUT administrator rights: elevated apps' windows cannot be moved")
        else:
            _WARNINGS.append(f"scheduled task {GUARD_TASK} is missing: daemon started WITHOUT administrator rights, "
                             "so windows of elevated apps cannot be moved. Fix: run `hermes-vdisplay install-task` "
                             "(one UAC prompt, once)")
        print("WARNING: " + _WARNINGS[-1], file=sys.stderr)
    subprocess.Popen([str(pyw), "-I", str(Path(__file__).resolve()), "_daemon"], close_fds=True,
                     creationflags=0x00000008 | 0x00000200)   # DETACHED_PROCESS | NEW_PROCESS_GROUP
    if not _wait_daemon(20):
        raise RuntimeError("the hermes-vdisplay daemon did not start (SudoVDA driver missing? see error.log)")


def _rpc(cmd, args=None, start=True, timeout=300):
    """Ask the daemon (started on demand) to do something; returns its JSON answer."""
    info = _daemon_info()
    if not info:
        if not start:
            return None
        _spawn_daemon()
        info = _daemon_info()
    env = {"sid": _sid(), "home": os.environ.get("HERMES_HOME", ""), "cwd": os.getcwd()}
    req = json.dumps({"token": info["token"], "cmd": cmd, "args": args or {}, "env": env}) + "\n"
    with socket.create_connection(("127.0.0.1", info["port"]), timeout=10) as c:
        c.settimeout(timeout)
        c.sendall(req.encode("utf-8"))
        buf = b""
        while not buf.endswith(b"\n"):
            chunk = c.recv(65536)
            if not chunk:
                break
            buf += chunk
    res = json.loads(buf.decode("utf-8"))
    if not res.get("ok"):
        raise RuntimeError(res.get("error") or "daemon error")
    return res


def _recover():
    """No daemon is running but the physical screens are still off (daemon crashed / killed): bring them back."""
    if _daemon_info():
        return
    if LAYOUT.exists() or not any(d["attached"] for d in physical()):
        _tlog("recover: no daemon but screens are off -> to_extend")
        to_extend()


def ensure_agent():
    _rpc("agent_on")


def _main_screen():
    """Where windows go when an agent screen disappears: user screen while screen-off, else the primary screen."""
    return vuser() or next((d for d in physical() if d["attached"] and d["primary"]), None) \
        or next((d for d in physical() if d["attached"]), None)


def _evacuate(names):
    """Move every window on the given display(s) (agent screens about to be removed) to the main screen."""
    names = set(names)
    if not names or not _main_screen():
        return []
    moved = []
    for w in windows():
        if w["display"] in names:
            h = w["hwnd"]
            try:
                norm = not u32.IsIconic(h) and not u32.IsZoomed(h)
                r0 = w["rect"]
                _place(h, "main")
                if norm:
                    r = RECT(); u32.GetWindowRect(h, ctypes.byref(r))   # where _place put it
                    moved.append((h, w["pid"], r0[2] - r0[0], r0[3] - r0[1], r.l, r.t))
            except Exception:
                pass
    _keep_sizes(moved)
    return moved


class _PerMonitorV2:
    """Run a block in a per-monitor-v2 DPI awareness thread context: GetWindowRect / SetWindowPos then use real
    physical pixels whatever the process / window context is."""

    def __enter__(self):
        self.old = None
        try:
            u32.SetThreadDpiAwarenessContext.restype = ctypes.c_void_p
            u32.SetThreadDpiAwarenessContext.argtypes = [ctypes.c_void_p]
            self.old = u32.SetThreadDpiAwarenessContext(ctypes.c_void_p(-4))
        except Exception:
            pass
        return self

    def __exit__(self, *a):
        if self.old:
            try:
                u32.SetThreadDpiAwarenessContext(ctypes.c_void_p(self.old))
            except Exception:
                pass


def _mon_dpi(h):
    """Effective DPI of the monitor the window is on (0 when unknown)."""
    try:
        x, y = ctypes.c_uint(), ctypes.c_uint()
        sh = ctypes.WinDLL("shcore")
        sh.GetDpiForMonitor.argtypes = [W.HMONITOR, ctypes.c_int, ctypes.POINTER(ctypes.c_uint), ctypes.POINTER(ctypes.c_uint)]
        hm = u32.MonitorFromWindow(h, 2)
        return int(x.value) if sh.GetDpiForMonitor(hm, 0, ctypes.byref(x), ctypes.byref(y)) == 0 else 0
    except Exception:
        return 0


# ---- per-screen DPI scaling (stand-in = physical screen)
# Undocumented DisplayConfigSetDeviceInfo types: -3 GET_DPI_SCALE {header, min/cur/max ScaleRel}, -4 SET_DPI_SCALE
# {header, scaleRel}. ScaleRel is a step count relative to the screen's recommended scale on the 100..500 % list;
# the setting is stored per monitor (adapter + target id), so it is only ever written for the stand-in's own source.
DPI_STEPS = [100, 125, 150, 175, 200, 225, 250, 300, 350, 400, 450, 500]


class _DCHDR(ctypes.Structure):
    _fields_ = [("type", ctypes.c_int32), ("size", ctypes.c_uint32), ("lo", ctypes.c_uint32), ("hi", ctypes.c_int32),
                ("id", ctypes.c_uint32)]


class _DCGET(ctypes.Structure):
    _fields_ = [("h", _DCHDR), ("mn", ctypes.c_int32), ("cur", ctypes.c_int32), ("mx", ctypes.c_int32)]


class _DCSET(ctypes.Structure):
    _fields_ = [("h", _DCHDR), ("rel", ctypes.c_int32)]


def _source_of(gdi):
    """(adapter luid lo, hi, source id) of the active path whose GDI name is `gdi`, else None."""
    n_p, n_m = ctypes.c_uint32(), ctypes.c_uint32()
    if u32.GetDisplayConfigBufferSizes(2, ctypes.byref(n_p), ctypes.byref(n_m)):
        return None
    paths = (ctypes.c_ubyte * (72 * n_p.value))(); modes = (ctypes.c_ubyte * (64 * n_m.value))()
    if u32.QueryDisplayConfig(2, ctypes.byref(n_p), paths, ctypes.byref(n_m), modes, None):
        return None
    for i in range(n_p.value):
        p = bytes(paths[i * 72:(i + 1) * 72])
        hdr = (ctypes.c_ubyte * 84)()
        hdr[0:4] = (1).to_bytes(4, "little"); hdr[4:8] = (84).to_bytes(4, "little"); hdr[8:20] = p[0:12]
        if u32.DisplayConfigGetDeviceInfo(hdr) == 0 and \
                bytes(hdr[20:84]).decode("utf-16-le").split(chr(0))[0].upper() == gdi.upper():
            return (int.from_bytes(p[0:4], "little"), int.from_bytes(p[4:8], "little", signed=True),
                    int.from_bytes(p[8:12], "little"))
    return None


def _scale_range(gdi):
    """(min, cur, max) ScaleRel of the screen, None when the API is unavailable."""
    try:
        src = _source_of(gdi)
        if not src:
            return None
        g = _DCGET(); g.h.type = -3; g.h.size = ctypes.sizeof(g); g.h.lo, g.h.hi, g.h.id = src
        if u32.DisplayConfigGetDeviceInfo(ctypes.byref(g)) != 0:
            return None
        return g.mn, g.cur, g.mx
    except Exception:
        return None


def _scale_pct(gdi):
    r = _scale_range(gdi)
    if not r:
        return 0
    i = r[1] - r[0]
    return DPI_STEPS[i] if 0 <= i < len(DPI_STEPS) else 0


def _set_scale_pct(gdi, pct):
    """Set the screen's scaling to `pct` percent. Returns the percent in effect afterwards (0 = unknown)."""
    r = _scale_range(gdi)
    if not r or pct not in DPI_STEPS:
        _tlog(f"set_scale {gdi}: GET failed or {pct}% not a step (range {r})")
        return 0
    rel = max(r[0], min(r[2], DPI_STEPS.index(pct) + r[0]))
    src = _source_of(gdi)
    s = _DCSET(); s.h.type = -4; s.h.size = ctypes.sizeof(s); s.h.lo, s.h.hi, s.h.id = src; s.rel = rel
    rc = u32.DisplayConfigSetDeviceInfo(ctypes.byref(s))
    now = _scale_pct(gdi)
    _tlog(f"set_scale {gdi} -> {pct}% (rel {rel}, range {r[0]}..{r[2]}, was {DPI_STEPS[r[1] - r[0]]}%) rc={rc} now {now}%")
    return now


def _eff_dpi(gdi):
    """Effective DPI (MDT_EFFECTIVE_DPI, per-monitor-v2 aware thread) of the monitor with GDI name `gdi` (0 = unknown)."""
    out = []
    try:
        sh = ctypes.WinDLL("shcore")
        sh.GetDpiForMonitor.argtypes = [W.HMONITOR, ctypes.c_int, ctypes.POINTER(ctypes.c_uint), ctypes.POINTER(ctypes.c_uint)]
        CB = ctypes.WINFUNCTYPE(W.BOOL, W.HMONITOR, W.HDC, ctypes.POINTER(RECT), W.LPARAM)

        def cb(hm, _dc, _rc, _lp):
            mi = MONITORINFOEXW(); mi.cbSize = ctypes.sizeof(mi)
            if u32.GetMonitorInfoW(hm, ctypes.byref(mi)) and mi.szDevice.upper() == gdi.upper():
                x, y = ctypes.c_uint(), ctypes.c_uint()
                if sh.GetDpiForMonitor(hm, 0, ctypes.byref(x), ctypes.byref(y)) == 0:
                    out.append(int(x.value))
            return True
        u32.EnumDisplayMonitors.argtypes = [W.HDC, ctypes.c_void_p, CB, W.LPARAM]
        with _PerMonitorV2():
            u32.EnumDisplayMonitors(None, None, CB(cb), 0)
    except Exception:
        pass
    return out[0] if out else 0


def _match_scale(standin, want_dpi):
    """Give the stand-in the physical screen's scaling (want_dpi = its effective DPI). Logs and returns the measured
    effective DPI of the stand-in; on failure the size-restore fallback (_standin_restore) stays in charge."""
    if not want_dpi:
        _tlog("match_scale: physical dpi unknown -> fallback")
        return 0
    pct = int(round(want_dpi * 100 / 96.0))
    try:
        for _ in range(3):
            if _eff_dpi(standin) == want_dpi:
                break
            _set_scale_pct(standin, pct)
            time.sleep(0.7)
        got = _eff_dpi(standin)
    except Exception as e:
        _tlog(f"match_scale failed: {e}")
        return 0
    _tlog(f"match_scale {standin}: want dpi {want_dpi} got {got} -> {'OK' if got == want_dpi else 'MISMATCH, fallback'}")
    return got


def _settle_size(items, rounds=4):
    """items: [(hwnd, pid, w, h, x_or_None, y_or_None)]. Rule: a window keeps its PHYSICAL pixel size (and, when a
    position is given, that position) across a screen with another DPI. Windows' own DPI move rescales it
    (96 -> 192 dpi doubles 1280x800 to 2560x1600) and an app may rescale again on WM_DPICHANGED after the move,
    so re-apply after a short wait until a verify pass finds nothing to fix. Returns the final (hwnd, rect, dpi)."""
    with _PerMonitorV2():
        for i in range(rounds):
            time.sleep(0.4)
            fixed = 0
            for h, pid, cw, ch, x, y in items:
                if not u32.IsWindow(h) or _pid(h) != pid or u32.IsIconic(h) or u32.IsZoomed(h):
                    continue
                r = RECT(); u32.GetWindowRect(h, ctypes.byref(r))
                tx, ty = (r.l, r.t) if x is None else (x, y)
                if [r.l, r.t, r.r - r.l, r.b - r.t] != [tx, ty, cw, ch]:
                    u32.SetWindowPos(h, None, tx, ty, cw, ch, SWP_NOZORDER | SWP_NOACTIVATE)
                    fixed += 1
            if not fixed and i >= 1:
                break
        for h, pid, cw, ch, x, y in items:   # DPI-unaware window (its own DPI < the monitor's): Windows bitmap-scales
            if not u32.IsWindow(h) or _pid(h) != pid or u32.IsIconic(h) or u32.IsZoomed(h):   # it, so ask in its units
                continue
            r = RECT(); u32.GetWindowRect(h, ctypes.byref(r))
            md = _mon_dpi(h)
            if r.r - r.l != cw and md and 0 < _dpi(h) < md:
                try:
                    u32.SetThreadDpiAwarenessContext.restype = ctypes.c_void_p
                    old = u32.SetThreadDpiAwarenessContext(ctypes.c_void_p(-1))   # DPI_AWARENESS_CONTEXT_UNAWARE
                    f = _dpi(h) / md
                    for _ in range(3):
                        u32.SetWindowPos(h, None, int((r.l if x is None else x) * f), int((r.t if x is None else y) * f),
                                         int(cw * f), int(ch * f), SWP_NOZORDER | SWP_NOACTIVATE | SWP_NOMOVE)
                        time.sleep(0.3)
                    u32.SetThreadDpiAwarenessContext(ctypes.c_void_p(old))
                except Exception:
                    pass
        out = []
        for h, pid, cw, ch, x, y in items:
            if u32.IsWindow(h) and _pid(h) == pid:
                r = RECT(); u32.GetWindowRect(h, ctypes.byref(r))
                out.append((h, [r.l, r.t, r.r - r.l, r.b - r.t], _dpi(h), [cw, ch]))
        return out


def _keep_sizes(moved):
    """Agent screen removed / windows moved to the main screen: put every (hwnd, pid, w, h, x, y) back to the
    physical size / position it had right after the move."""
    res = _settle_size(moved)
    _tlog("keep_sizes " + str([(h, rc, dpi, want) for h, rc, dpi, want in res]))


def _standin_snap():
    """Normal-state windows on a screen-off stand-in: physical size + position relative to the stand-in."""
    names = {d["name"] for d in svdas()}
    out = []
    with _PerMonitorV2():
        for w in windows():
            h = w["hwnd"]
            if w["display"] in names and not u32.IsIconic(h) and not u32.IsZoomed(h):
                mon = _monitor(h).rcMonitor
                r = RECT(); u32.GetWindowRect(h, ctypes.byref(r))
                out.append((h, w["pid"], r.r - r.l, r.b - r.t, r.l - mon.l, r.t - mon.t))
    return out


def _standin_restore(snap):
    """Screen back on: a window that lived on the 96 dpi stand-in gets rescaled by Windows onto the 192 dpi
    physical screen (1280x800 -> 2558x1598); give it its old physical size back (position: same offset on the
    screen it is on now, clamped into the work area)."""
    items = []
    with _PerMonitorV2():
        for h, pid, cw, ch, rx, ry in snap:
            if not u32.IsWindow(h) or _pid(h) != pid or u32.IsIconic(h) or u32.IsZoomed(h):
                continue
            mi = _monitor(h); mon, wk = mi.rcMonitor, mi.rcWork
            cw, ch = min(cw, wk.r - wk.l), min(ch, wk.b - wk.t)
            x = max(wk.l, min(mon.l + rx, wk.r - cw)); y = max(wk.t, min(mon.t + ry, wk.b - ch))
            items.append((h, pid, cw, ch, x, y))
    res = _settle_size(items)
    _tlog("standin_restore " + str([(h, rc, dpi, want) for h, rc, dpi, want in res]))


def _rescue_orphans():
    """Minimized windows whose restore position is on no screen (e.g. left at -32000 by a removed agent screen)."""
    if not _main_screen():
        return
    for w in windows():
        if w["display"] == "" and u32.IsIconic(w["hwnd"]) and not _cloaked(w["hwnd"]):
            try:
                _place(w["hwnd"], "main")
            except Exception:
                pass


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


def _dpi(h):
    try:
        return int(u32.GetDpiForWindow(h))
    except Exception:
        return 0


def _fix_sizes(wins):
    """Re-attaching a physical screen can make Windows rescale windows (per-monitor DPI change / its own reshuffle).
    Put every normal-state window back to the exact rect it had before screen-off (size included)."""
    for _ in range(2):   # WM_DPICHANGED may resize again right after the first move
        for w in wins:
            h = w["hwnd"]
            if not u32.IsWindow(h) or _pid(h) != w["pid"] or w.get("iconic") or w.get("zoomed") or not w.get("rect"):
                continue
            if u32.IsIconic(h) or u32.IsZoomed(h):
                continue
            r = RECT(); u32.GetWindowRect(h, ctypes.byref(r))
            l, t, rr, b = w["rect"]
            if [r.l, r.t, r.r, r.b] != [l, t, rr, b]:
                u32.SetWindowPos(h, None, l, t, rr - l, b - t, SWP_NOZORDER | SWP_NOACTIVATE)
        time.sleep(0.4)


def _agent_rects():
    """Rect of every normal-state window on an agent screen, relative to that screen's origin."""
    vn = {d["name"] for d in vdds()}
    out = []
    for w in windows():
        h = w["hwnd"]
        if w["display"] in vn and not u32.IsIconic(h) and not u32.IsZoomed(h):
            mon = _monitor(h).rcMonitor
            out.append({"hwnd": h, "pid": w["pid"], "display": w["display"],
                        "rel": [w["rect"][0] - mon.l, w["rect"][1] - mon.t, w["rect"][2] - w["rect"][0], w["rect"][3] - w["rect"][1]]})
    return out


def _agent_rects_restore(snap):
    for _ in range(2):
        for w in snap:
            h = w["hwnd"]
            if not u32.IsWindow(h) or _pid(h) != w["pid"] or u32.IsIconic(h) or u32.IsZoomed(h)                     or _display_of(h) != w["display"]:
                continue
            mon = _monitor(h).rcMonitor
            x, y, cw, ch = w["rel"]
            r = RECT(); u32.GetWindowRect(h, ctypes.byref(r))
            if [r.l, r.t, r.r - r.l, r.b - r.t] != [mon.l + x, mon.t + y, cw, ch]:
                u32.SetWindowPos(h, None, mon.l + x, mon.t + y, cw, ch, SWP_NOZORDER | SWP_NOACTIVATE)
        time.sleep(0.4)


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
        _fix_sizes(lay.get("windows", []))
    except Exception:
        pass
    try:
        LAYOUT.unlink()
    except OSError:
        pass


# ---------------------------------------------------------------- windows
class RECT(ctypes.Structure):
    _fields_ = [("l", W.LONG), ("t", W.LONG), ("r", W.LONG), ("b", W.LONG)]


class MONITORINFOEXW(ctypes.Structure):
    _fields_ = [("cbSize", W.DWORD), ("rcMonitor", RECT), ("rcWork", RECT), ("dwFlags", W.DWORD),
                ("szDevice", W.WCHAR * 32)]


u32.MonitorFromWindow.restype = W.HMONITOR
u32.MonitorFromWindow.argtypes = [W.HWND, W.DWORD]
u32.GetMonitorInfoW.argtypes = [W.HMONITOR, ctypes.POINTER(MONITORINFOEXW)]
u32.GetDpiForWindow.argtypes = [W.HWND]
u32.GetDpiForWindow.restype = W.UINT
u32.SetWindowPos.argtypes = [W.HWND, W.HWND, ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_int, W.UINT]
u32.PostMessageW.argtypes = [W.HWND, W.UINT, W.WPARAM, W.LPARAM]
u32.GetForegroundWindow.restype = W.HWND
u32.WindowFromPoint.argtypes = [W.POINT]
u32.WindowFromPoint.restype = W.HWND
SWP_NOZORDER, SWP_NOACTIVATE, SWP_NOSIZE = 0x4, 0x10, 0x1
SWP_NOMOVE = 0x2


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


def _display_of(h):
    """Device name of the screen the window is on (restore position when minimized); "" when it is on no screen
    (e.g. minimized at -32000) instead of the nearest one."""
    if u32.IsIconic(h):
        wp = WINDOWPLACEMENT(); wp.length = ctypes.sizeof(wp)
        u32.GetWindowPlacement(h, ctypes.byref(wp))
        hm = u32.MonitorFromRect(ctypes.byref(wp.rc), 0)   # MONITOR_DEFAULTTONULL
    else:
        hm = u32.MonitorFromWindow(h, 0)
    if not hm:
        return ""
    mi = MONITORINFOEXW(); mi.cbSize = ctypes.sizeof(mi)
    return mi.szDevice if u32.GetMonitorInfoW(hm, ctypes.byref(mi)) else ""


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
            res.append({"hwnd": h, "pid": _pid(h), "display": _display_of(h), "title": _title(h),
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
    if where != "virtual" and svdas():
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


def _place_via(h, where, restore=False):
    """UIPI: a normal process cannot move an elevated app's window; the elevated daemon can."""
    if not _is_admin() and _daemon_info():
        try:
            _rpc("place", {"hwnd": h, "where": where, "restore": restore}, start=False)
            return
        except Exception as e:
            _WARNINGS.append(f"daemon could not place window: {e}")
    _place(h, where)


def move(h, where):
    _allow([h], remove=(where != "virtual"))
    if where == "virtual":
        ensure_agent()
    _place_via(h, where)


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
    if u32.IsZoomed(h):   # SW_SHOWNOACTIVATE keeps a maximized window maximized (and re-maximizes it on the old screen)
        wp = WINDOWPLACEMENT(); wp.length = ctypes.sizeof(wp)
        u32.GetWindowPlacement(h, ctypes.byref(wp))
        w, hh = min(wp.rc.r - wp.rc.l, tw), min(wp.rc.b - wp.rc.t, th)
        wp.rc = RECT(tx + 40, ty + 40, tx + 40 + w, ty + 40 + hh)
        wp.showCmd = 4   # normal size, no activation
        u32.SetWindowPlacement(h, ctypes.byref(wp))
        return
    if u32.IsIconic(h):
        u32.ShowWindow(h, 4)   # SW_SHOWNOACTIVATE (restore without focus)
    r, mon = RECT(), _monitor(h).rcMonitor
    u32.GetWindowRect(h, ctypes.byref(r))
    w, hh = min(r.r - r.l, tw), min(r.b - r.t, th)
    x = tx + max(0, min(r.l - mon.l, tw - w)); y = ty + max(0, min(r.t - mon.t, th - hh))
    u32.SetWindowPos(h, None, x, y, w, hh, SWP_NOZORDER | SWP_NOACTIVATE)


def show(h):
    _rpc("screen_on", start=False)   # the user wants to see it: wake the physical screen
    move(h, "main")
    _place_via(h, "main", restore=True)
    if u32.IsIconic(h):
        u32.ShowWindow(h, 9)   # SW_RESTORE
    u32.keybd_event(0x12, 0, 0, 0); u32.keybd_event(0x12, 0, 2, 0)   # ALT tap lets SetForegroundWindow succeed
    u32.SetForegroundWindow(h)


def launch(cmd):
    fg = u32.GetForegroundWindow()
    ensure_agent()
    before = {w["hwnd"] for w in windows()}
    ft = ctypes.c_ulonglong(); k32.GetSystemTimeAsFileTime(ctypes.byref(ft))
    t_launch = ft.value - 20_000_000   # 2 s slack (FILETIME is 100 ns units); own clock: packaged-app stubs exit at once
    try:
        p = subprocess.Popen(cmd, creationflags=0x00000200, close_fds=True)
    except FileNotFoundError:   # app-execution aliases (mspaint, Store apps) only resolve through the shell
        p = subprocess.Popen(["cmd.exe", "/c", "start", "", *cmd], creationflags=0x08000000, close_fds=True)
    _allow(pids=[p.pid])
    t0 = t_launch
    moved, t_end = set(), time.time() + 15
    while time.time() < t_end:
        time.sleep(0.4)
        for w in windows():
            # only windows of processes started by this launch (skips e.g. a tray app restoring itself meanwhile)
            if w["hwnd"] not in before and w["hwnd"] not in moved and w["pid"] in (p.pid,) or \
                    (w["hwnd"] not in before and w["hwnd"] not in moved and _started(w["pid"]) >= t0):
                move(w["hwnd"], "virtual"); moved.add(w["hwnd"])
        if fg and u32.GetForegroundWindow() != fg and u32.GetForegroundWindow() in moved:
            u32.SetForegroundWindow(fg)
        if moved and time.time() > t_end - 12:
            break
    return {"pid": p.pid, "windows": sorted(moved),
            "placed": [{"hwnd": w["hwnd"], "display": w["display"], "rect": w["rect"]}
                       for w in windows() if w["hwnd"] in moved]}


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
    """Post WM_CHAR for each character; returns what was sent (PostMessage's own result per character)."""
    h = _focus_child(top)
    sent = failed = 0
    for ch in text:
        if u32.PostMessageW(h, 0x0102, ord(ch), 1):   # WM_CHAR
            sent += 1
        else:
            failed += 1
        time.sleep(0.005)
    return {"ok": failed == 0 and sent == len(text), "sent": sent, "failed": failed, "chars": len(text),
            "target": f"0x{int(h or 0):X}"}


def bg_key(top, key):
    vk = VK.get(str(key).lower()) or int(str(key), 0)
    h = _focus_child(top)
    ok = bool(u32.PostMessageW(h, 0x0100, vk, 1)) & bool(u32.PostMessageW(h, 0x0101, vk, 0xC0000001))
    return {"ok": bool(ok), "key": str(key), "target": f"0x{int(h or 0):X}"}


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


def _profile_of(home):
    p = Path(home) if home else None
    return p.name if p and p.parent.name.lower() == "profiles" else "default"


def screens():
    """Every screen for the phone's screen manager. Physical: role main / secondary (index 1, 2, ... left to right).
    Agent: owning session, its profile and working folder. Screen-off: the physical screen it stands in for."""
    def order(ps):
        return sorted(ps, key=lambda d: (not d["primary"], d["x"], d["y"]))
    off = off_names()
    lay = order(_jload(LAYOUT, {}).get("physical", [])) if off else []
    phys = lay or order([d for d in physical() if d["attached"]])
    out, idx = [], {}
    for i, d in enumerate(phys):
        role = "main" if i == 0 else "secondary"
        idx[(d["x"], d["y"])] = (role, i)
        out.append({"name": d["name"], "kind": "physical", "role": role, "index": i, "on": d["name"] not in off,
                    "w": d["w"], "h": d["h"], "hz": d["hz"]})
    for d in svdas():
        role, i = idx.get((d["x"], d["y"]), ("main" if d["primary"] else "secondary", 0))
        out.append({"name": d["name"], "kind": "screen-off", "role": role, "index": i, "on": True,
                    "w": d["w"], "h": d["h"], "hz": d["hz"]})
    by = {d["name"]: d for d in displays() if d["attached"]}
    for sid, a in _agents_state().items():
        d = by.get(a.get("name"))
        if not d:
            continue
        out.append({"name": a["name"], "kind": "agent", "session": sid, "profile": _profile_of(a.get("home")),
                    "cwd": a.get("cwd", ""), "on": True, "w": d["w"], "h": d["h"], "hz": d["hz"]})
    return out


def shot_display(name, out=None, quality=70, max_w=1600):
    """JPEG of any attached screen (phone screen manager)."""
    from PIL import ImageGrab
    d = next((d for d in displays() if d["name"].lower() == str(name).lower() and d["attached"]), None)
    if not d:
        raise RuntimeError(f"display {name} is not active")
    im = ImageGrab.grab(bbox=(d["x"], d["y"], d["x"] + d["w"], d["y"] + d["h"]), all_screens=True).convert("RGB")
    if im.width > max_w:
        im = im.resize((max_w, im.height * max_w // im.width))
    out = Path(out or STATE / "shot-display.jpg")
    im.save(out, "JPEG", quality=quality)
    return str(out)


# ---------------------------------------------------------------- CLI
def status():
    _recover()
    v, ok = vdd(), svda_installed()
    return {"installed": ok, "api": API, "daemon": bool(_daemon_info()), "elevated": bool(dstate().get("elevated")) if _daemon_info() else False,
            "guard_task": _task_exists(), "session": _sid(), "agent": bool(v),
            "screen_off": screen_off(), "attached": bool(v), "mode": mode_now(), "user_screen_available": ok,
            "virtual": v, "agents": {k: a.get("name") for k, a in _agents_state().items()},
            "user_screen": vuser(), "physical": [d for d in physical() if d["attached"]]}


def _final(res=None):
    res = res or {"ok": True, "mode": mode_now(), "agent": agent_on(), "screen_off": screen_off(),
                  "off": sorted(off_names()), "agents": {k: a.get("name") for k, a in _agents_state().items()}}
    if _WARNINGS:
        res = dict(res, warning="; ".join(_WARNINGS))
    print(json.dumps(res))
    return 0


def main(a):
    if not a or a[0] in ("-h", "--help", "help"):
        print(__doc__); return 0
    c, r = a[0], a[1:]
    if c in ("_daemon", "_guard"):   # _guard: old scheduled task command line; remove after install-vdd re-run
        Daemon().run(); return 0
    if c == "agents":
        _recover(); print(json.dumps({k: v.get("name") for k, v in _agents_state().items()})); return 0
    if c == "allow":
        _allow([find(x) for x in r]); print(json.dumps(_allowed())); return 0
    if c == "disallow":
        _allow([find(x) for x in r], remove=True); print(json.dumps(_allowed())); return 0
    if c == "install-task":   # (re)register the elevated guard task only: one UAC prompt
        subprocess.run(["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(HERE / "install-vdd.ps1"),
                        "-TaskOnly", "-Cli", str(Path(__file__).resolve())], timeout=300)
        print(json.dumps({"guard_task": _task_exists()})); return 0
    if c in ("install", "uninstall"):
        args = ["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(HERE / "install-vdd.ps1")]
        subprocess.run(args + (["-Uninstall"] if c == "uninstall" else []), timeout=900)
        print(json.dumps(status())); return 0
    if c == "status":
        print(json.dumps(status(), indent=1)); return 0
    if c == "screens":
        _recover(); print(json.dumps({"screens": screens(), "screen_off": screen_off()}, ensure_ascii=False)); return 0
    if c == "shot-display":
        print(shot_display(r[0], r[1] if len(r) > 1 else None)); return 0
    if c == "recover":
        to_extend() if not _daemon_info() else None; return _final()
    if c == "daemon-stop":
        return _final(_rpc("stop", start=False) or {"ok": True, "daemon": False})
    if c in ("on", "agent-on") or (c == "agent" and r[:1] == ["on"]):
        return _final(_rpc("agent_on"))
    if c in ("off", "agent-off") or (c == "agent" and r[:1] == ["off"]):
        return _final(_rpc("agent_off", start=False) or _final())
    if c in ("screen-off", "screen-on", "screen", "mode", "extend", "second-only"):
        if c == "mode":
            c = r[0] if r and r[0] in ("extend", "second-only") else "mode"
        if c == "extend":
            off = False
        elif c == "second-only":
            off = True
        else:
            off = c == "screen-off" or (c == "screen" and r[:1] == ["off"])
        names = [x for x in (r[1:] if c == "screen" else r) if x.startswith("\\\\.\\")] or None
        if off:
            return _final(_rpc("screen_off", {"targets": names}))
        return _final(_rpc("screen_on", {"targets": names}, start=False) or _final())
    _recover()
    if c == "windows":
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
        res = bg_type(find(r[0]), " ".join(r[1:])); print(json.dumps(res)); return 0 if res["ok"] else 1
    elif c == "bg-key":
        res = bg_key(find(r[0]), r[1]); print(json.dumps(res)); return 0 if res["ok"] else 1
    elif c == "click":
        real_click(int(r[0]), int(r[1]), len(r) > 2 and r[2] == "right")
    elif c == "type":
        real_type(find(r[0]), " ".join(r[1:]))
    elif c == "shot":
        print(shot(r[0] if r else None)); return 0
    else:
        print(f"unknown command {c!r}\n\n{__doc__}", file=sys.stderr); return 2
    return _final()


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv[1:]))
    except Exception as e:
        import traceback
        tb = traceback.format_exc()
        _errlog(f"argv={sys.argv[1:]}", tb)   # keep the traceback: the one-line error alone hides where it broke
        err = {"ok": False, "error": str(e) or type(e).__name__}
        if not isinstance(e, (RuntimeError, IndexError, KeyError, ValueError)):
            err["type"] = type(e).__name__; err["where"] = tb.strip().splitlines()[-3:-1]
        print(json.dumps(err))
        sys.exit(1)
