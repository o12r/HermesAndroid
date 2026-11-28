"""Phone relay: exposes the Hermes desktop WebSocket protocol (hermes-gateway-v1) on Tailscale.

The gateway's /api/ws listens on loopback only and needs a single-use ticket that only a local
process can mint (`hermes gateway attach`). This relay:
  * listens on RELAY_HOST:RELAY_PORT (all addresses, :8643; Bearer API key required),
  * authenticates the phone with the same API_SERVER_KEY as the api_server (Bearer header),
  * mints a fresh ticket for the requested profile and pipes frames both ways, unmodified.
Run with the Hermes venv python. Never logs keys or tickets.
"""
import asyncio, ctypes, hmac, json, logging, os, re, subprocess, sys, tempfile, threading, time
from ctypes import wintypes
from pathlib import Path
from urllib.parse import quote
from aiohttp import web, ClientSession, WSMsgType

HERMES_HOME = Path(os.environ.get("LOCALAPPDATA", "")) / "hermes"
HOST = os.environ.get("RELAY_HOST", "0.0.0.0")  # all addresses: Tailscale + LAN (API key required)
PORT = int(os.environ.get("RELAY_PORT", "8643"))
HERMES = str(HERMES_HOME / "bin" / "hermes.exe")
LOG = Path(os.environ.get("RELAY_LOG") or HERMES_HOME / "launchers" / "phone-relay.log")
logging.basicConfig(filename=LOG, level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("relay")


def api_key() -> str:
    if os.environ.get("RELAY_API_KEY"):   # test instances
        return os.environ["RELAY_API_KEY"]
    for line in (HERMES_HOME / ".env").read_text(encoding="utf-8").splitlines():
        if line.startswith("API_SERVER_KEY="):
            return line.split("=", 1)[1].strip()
    raise SystemExit("API_SERVER_KEY missing from default .env")


KEY = api_key()
NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)  # no console window for hermes.exe children


def profile_ok(p: str) -> bool:
    return p == "default" or (re.fullmatch(r"[a-z0-9][a-z0-9_-]{0,63}", p or "") is not None
                              and (HERMES_HOME / "profiles" / p).is_dir())


IO_THREADS = 32      # worker threads for blocking work (file/yaml/stat); keeps the event loop free for sockets
_IO_POOL = None


def _io(fn, *a):
    """Run blocking [fn] on the dedicated thread pool so one slow disk/yaml/subprocess call never stalls other phones."""
    return asyncio.get_running_loop().run_in_executor(_IO_POOL, fn, *a)


_HTTP = None         # one shared aiohttp client (keep-alive pool) for all proxied /api calls


def _http():
    global _HTTP
    if _HTTP is None or _HTTP.closed:
        from aiohttp import TCPConnector
        _HTTP = ClientSession(connector=TCPConnector(limit=100, limit_per_host=50, keepalive_timeout=30))
    return _HTTP


_ATTACH_SEM = None   # created lazily inside the running loop: at most 3 `gateway attach` subprocesses at once
MAX_WS = 64          # simultaneous phone sockets; more get 503 instead of exhausting the PC
_WS_COUNT = 0
SEND_TIMEOUT = 30    # a phone that stops reading is dropped instead of stalling the pump (and memory) forever


async def attach(profile: str) -> dict:
    global _ATTACH_SEM
    if _ATTACH_SEM is None:
        _ATTACH_SEM = asyncio.Semaphore(3)
    async with _ATTACH_SEM:
        try:
            return await _attach(profile)
        except Exception as e:
            log.info("attach %s failed (%s); retrying once", profile, e)
            await asyncio.sleep(1.5)
            return await _attach(profile)


async def _attach(profile: str) -> dict:
    args = [HERMES] + ([] if profile == "default" else ["-p", profile]) + ["gateway", "attach", "--start", "--json"]  # --start: works without the desktop app open
    proc = await asyncio.create_subprocess_exec(*args, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, creationflags=NO_WINDOW)
    try:
        out, _ = await asyncio.wait_for(proc.communicate(), timeout=60)
    except asyncio.TimeoutError:
        proc.kill(); raise RuntimeError("gateway attach timed out")
    lines = out.decode("utf-8", "replace").strip().splitlines()
    d = json.loads(lines[-1]) if lines else {}
    if d.get("state") != "ready" or not d.get("url"):
        raise RuntimeError(f"gateway not ready ({d.get('state')}, {d.get('reason_code')})")
    return d


class TicketServer:
    """One long-lived `hermes gateway tickets --serve`: a fresh single-use native-http ticket per request."""
    def __init__(self):
        self.proc = None; self.origin = None; self.lock = asyncio.Lock(); self.n = 0

    async def _start(self):
        self.proc = await asyncio.create_subprocess_exec(
            HERMES, "-p", "default", "gateway", "tickets", "--serve", stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL, creationflags=NO_WINDOW)
        hello = json.loads(await asyncio.wait_for(self.proc.stdout.readline(), 60))
        self.origin = (hello.get("endpoint") or {}).get("api_origin")
        if not self.origin:
            raise RuntimeError("ticket server: no api_origin")

    async def ticket(self, profile: str):
        async with self.lock:
            for attempt in (1, 2):
                try:
                    if self.proc is None or self.proc.returncode is not None:
                        await self._start()
                    self.n += 1
                    req = {"id": str(self.n), "purpose": "native-http"}
                    if profile != "default":
                        req["profile"] = profile
                    self.proc.stdin.write((json.dumps(req) + "\n").encode()); await self.proc.stdin.drain()
                    r = json.loads(await asyncio.wait_for(self.proc.stdout.readline(), 30))
                    if not r.get("ok") or not r.get("ticket"):
                        raise RuntimeError(f"ticket refused: {r.get('error') or r.get('reason')}")
                    return self.origin, r["ticket"]
                except Exception:
                    if self.proc is not None and self.proc.returncode is None:
                        self.proc.kill()
                    self.proc = None
                    if attempt == 2:
                        raise


TICKETS = TicketServer()
_FWD_HEADERS = ("content-type", "accept")


async def http_proxy(req: web.Request):
    """Forward /api/* to the gateway's native HTTP API (the desktop app's sessions/messages routes)."""
    auth = req.headers.get("Authorization", "")
    if not (auth.startswith("Bearer ") and hmac.compare_digest(auth[7:].strip(), KEY)):
        return web.json_response({"error": "unauthorized"}, status=401)
    profile = req.query.get("profile", "default")
    if not profile_ok(profile):
        return web.json_response({"error": "unknown profile"}, status=404)
    try:
        origin, ticket = await TICKETS.ticket(profile)
    except Exception as e:
        log.warning("ticket failed: %s", e)
        return web.json_response({"error": f"gateway unavailable: {e}"}, status=503)
    headers = {k: v for k, v in req.headers.items() if k.lower() in _FWD_HEADERS}
    headers["x-hermes-gateway-ticket"] = ticket
    body = await req.read()
    try:
        s = _http()
        async with s.request(req.method, origin + req.path_qs, headers=headers, data=body or None,
                             timeout=__import__("aiohttp").ClientTimeout(total=120)) as r:
            data = await r.read()
            return web.Response(status=r.status, body=data,
                                content_type=(r.content_type or "application/json"))
    except Exception as e:
        log.warning("proxy %s failed: %s", req.path, e)
        return web.json_response({"error": f"gateway unavailable: {e}"}, status=502)


UPDATES = HERMES_HOME / "launchers" / "app-updates"


async def app_files(req: web.Request):
    """In-app updates/hotfix for any android-dev app: launchers/app-updates/<app>/{latest.json,hotfix.json,app.apk}."""
    auth = req.headers.get("Authorization", "")
    if not (auth.startswith("Bearer ") and hmac.compare_digest(auth[7:].strip(), KEY)):
        return web.json_response({"error": "unauthorized"}, status=401)
    app_id, what = req.match_info["app"], req.match_info["what"]
    if not re.fullmatch(r"[a-z0-9][a-z0-9_-]{0,63}", app_id):
        return web.json_response({"error": "bad app"}, status=404)
    d = UPDATES / app_id
    if what in ("latest.json", "hotfix.json"):
        f = d / what
        return web.FileResponse(f, headers={"Cache-Control": "no-store"}) if f.is_file() else web.json_response({"error": "none"}, status=404)
    if what == "apk":
        f = d / "app.apk"
        return web.FileResponse(f, headers={"Content-Type": "application/vnd.android.package-archive"}) if f.is_file() \
            else web.json_response({"error": "none"}, status=404)
    return web.json_response({"error": "not found"}, status=404)


async def fs_op(req: web.Request):
    """POST /fs/stat|mkdir {"path": "C:\\..."}: lets the phone check / create a project folder. Absolute paths only."""
    auth = req.headers.get("Authorization", "")
    if not (auth.startswith("Bearer ") and hmac.compare_digest(auth[7:].strip(), KEY)):
        return web.json_response({"error": "unauthorized"}, status=401)
    try:
        body = await req.json()
        raw = str(body.get("path", "")).strip()
        keep = [str(k) for k in (body.get("keep") or []) if k]
    except Exception:
        return web.json_response({"error": "bad request"}, status=400)
    op = req.match_info["op"]
    if op == "list" and not raw:
        return web.json_response(await _io(_fs_roots))
    p = Path(os.path.expandvars(os.path.expanduser(raw)))
    if not raw or not p.is_absolute():
        return web.json_response({"error": "path must be absolute"}, status=400)
    if op == "list":
        try:
            return web.json_response(await _io(_fs_list, p))
        except OSError as e:
            return web.json_response({"error": f"{e.strerror or e}"}, status=400)
    if op in ("pin", "unpin"):
        if not await _io(p.is_dir):
            return web.json_response({"error": "not a folder"}, status=400)
        try:
            err = await _io(_fs_pin, p, op == "pin")
        except Exception as e:
            err = str(e)
        if err:
            return web.json_response({"error": err}, status=400)
        log.info("phone %sned %s in Quick access", op, p)
        return web.json_response({"ok": True})
    if op == "rename":
        new = str(body.get("new", "")).strip()
        if not new or any(c in new for c in '\\/:*?"<>|') or new in (".", ".."):
            return web.json_response({"error": "invalid name"}, status=400)
        try:
            await _io(lambda: p.rename(p.with_name(new)))
        except OSError as e:
            return web.json_response({"error": f"rename failed: {e.strerror or e}"}, status=400)
        log.info("phone renamed %s -> %s", p, new)
        return web.json_response({"ok": True})
    if op == "delete":
        try:
            err = await _io(_fs_delete, p, keep)
        except OSError as e:
            return web.json_response({"error": f"delete failed: {e.strerror or e}"}, status=400)
        if err:
            return web.json_response({"error": err}, status=400)
        log.info("phone deleted %s", p)
        return web.json_response({"ok": True})
    if op == "stat":
        ex, isdir = await _io(lambda: (p.exists(), p.is_dir()))
        return web.json_response({"exists": ex, "dir": isdir})
    if op == "mkdir":
        try:
            await _io(lambda: p.mkdir(parents=True, exist_ok=True))
        except OSError as e:
            return web.json_response({"error": f"mkdir failed: {e.strerror or e}"}, status=400)
        log.info("phone created folder %s", p)
        return web.json_response({"exists": True, "dir": True})
    if op == "rmdir":
        # Delete a project's own folder. Refuses roots, the user's home, Hermes' own data and any folder that
        # contains (or is) another project's folder (`keep`).
        def _key(x: Path) -> str:
            return os.path.normcase(os.path.abspath(str(x))).rstrip("\\/")
        def _rm():
            import shutil
            if not p.exists():
                return None
            if not p.is_dir() or p.is_symlink():
                return "not a plain folder"
            k = _key(p)
            if p.parent == p or len(Path(k).parts) < 3:
                return "refusing to delete a drive root or top-level folder"
            for guard in (Path.home(), HERMES_HOME, Path(os.environ.get("SystemRoot", "C:\\Windows")),
                          Path(os.environ.get("ProgramFiles", "C:\\Program Files"))):
                g = _key(guard)
                if k == g or g.startswith(k + os.sep):
                    return "refusing to delete a protected folder"
            for o in keep:
                ok = _key(Path(o))
                if ok == k or ok.startswith(k + os.sep):
                    return "folder contains another project's folder"
            shutil.rmtree(p)
            return None
        try:
            err = await _io(_rm)
        except OSError as e:
            return web.json_response({"error": f"delete failed: {e.strerror or e}"}, status=400)
        if err:
            return web.json_response({"error": err}, status=400)
        log.info("phone deleted folder %s", p)
        return web.json_response({"exists": False, "dir": False})
    return web.json_response({"error": "not found"}, status=404)


_QA = {"t": 0.0, "v": []}


def _fs_quick() -> list:
    """Windows Quick access folders (pinned + frequent), read through the shell; cached for a minute."""
    if _QA["v"] and time.time() - _QA["t"] < 60:
        return _QA["v"]
    ps = ("[Console]::OutputEncoding=[Text.Encoding]::UTF8; $o=(New-Object -ComObject Shell.Application)"
          ".Namespace('shell:::{679F85CB-0220-4080-B29B-5540CC05AAB6}'); if($o){$o.Items()|%{ $_.Name+'|'+$_.Path }}")
    out, seen = [], set()
    try:
        r = subprocess.run(["powershell", "-NoProfile", "-NonInteractive", "-Command", ps], capture_output=True, timeout=10,
                           creationflags=0x08000000)
        for line in r.stdout.decode("utf-8", "replace").splitlines():
            name, _, p = line.strip().partition("|")
            if p and p.upper() not in seen and os.path.isdir(p):
                seen.add(p.upper()); out.append({"name": name or os.path.basename(p), "dir": True, "size": 0, "mtime": 0, "path": p})
    except Exception:
        pass
    _QA["t"], _QA["v"] = time.time(), out[:24]
    return _QA["v"]


def _fs_pin(p: Path, pin: bool) -> str | None:
    """Pin / unpin a folder in Windows Quick access through the shell verbs; returns an error text or None.
    Unpin must be invoked on the item as listed *inside* Quick access (pinned -> unpinfromhome, frequent -> removefromhome)."""
    if pin:
        ps = ("$f=(New-Object -ComObject Shell.Application).Namespace($env:HFS_P); if(-not $f){exit 2}; $f.Self.InvokeVerb('pintohome')")
    else:
        ps = ("$n=(New-Object -ComObject Shell.Application).Namespace('shell:::{679F85CB-0220-4080-B29B-5540CC05AAB6}'); "
              "$t=$env:HFS_P.TrimEnd('\\').ToLower(); $hit=$null; "
              "foreach($i in $n.Items()){ if($i.Path -and $i.Path.TrimEnd('\\').ToLower() -eq $t){ $hit=$i; break } }; "
              "if(-not $hit){exit 3}; $names=@($hit.Verbs()|%{ $_.Name.Replace('&','') }); "
              "foreach($v in $hit.Verbs()){ $nm=$v.Name.Replace('&',''); if($nm -match 'Unpin from Quick access|取消固定|从“快速访问”取消固定|Remove from Quick access|从“快速访问”删除'){ $v.DoIt(); exit 0 } }; "
              "try{ $hit.InvokeVerb('unpinfromhome'); exit 0 }catch{}; try{ $hit.InvokeVerb('removefromhome'); exit 0 }catch{}; exit 4")
    env = dict(os.environ, HFS_P=str(p))
    r = subprocess.run(["powershell", "-NoProfile", "-NonInteractive", "-Command", ps], capture_output=True, timeout=20,
                       env=env, creationflags=0x08000000)
    _QA["t"] = 0.0
    if r.returncode == 3:
        return "not in Quick access"
    return None if r.returncode == 0 else "could not pin/unpin this folder"


def _fs_roots() -> dict:
    home = str(Path.home())
    drives = []
    try:
        import string
        drives = [f"{c}:\\" for c in string.ascii_uppercase if os.path.exists(f"{c}:\\")]
    except Exception:
        pass
    return {"path": "", "parent": None, "home": home, "quick": _fs_quick(),
            "items": [{"name": d, "dir": True, "size": 0, "mtime": 0, "path": d} for d in drives]}


def _fs_list(p: Path) -> dict:
    if not p.is_dir():
        raise NotADirectoryError(20, "not a folder")
    items = []
    with os.scandir(p) as it:
        for e in it:
            try:
                st = e.stat()
                items.append({"name": e.name, "dir": e.is_dir(), "size": 0 if e.is_dir() else st.st_size,
                              "mtime": int(st.st_mtime)})
            except OSError:
                items.append({"name": e.name, "dir": False, "size": 0, "mtime": 0})
            if len(items) >= 5000:
                break
    items.sort(key=lambda x: (not x["dir"], x["name"].lower()))
    parent = "" if p.parent == p else str(p.parent)
    return {"path": str(p), "parent": parent, "home": str(Path.home()), "items": items}


def _fs_delete(p: Path, keep: list):
    """Delete a file or folder. Refuses drive roots, the user's home, Hermes' own data, system folders and anything
    that contains one of `keep` (other projects' folders)."""
    import shutil
    def _key(x: Path) -> str:
        return os.path.normcase(os.path.abspath(str(x))).rstrip("\\/")
    if not p.exists() and not p.is_symlink():
        return "not found"
    k = _key(p)
    if p.parent == p or len(Path(k).parts) < 3:
        return "refusing to delete a drive root or top-level folder"
    for guard in (Path.home(), HERMES_HOME, Path(os.environ.get("SystemRoot", "C:\\Windows")),
                  Path(os.environ.get("ProgramFiles", "C:\\Program Files"))):
        g = _key(guard)
        if k == g or g.startswith(k + os.sep):
            return "refusing to delete a protected folder"
    for o in keep:
        ok = _key(Path(o))
        if ok == k or ok.startswith(k + os.sep):
            return "folder contains another project's folder"
    if p.is_dir() and not p.is_symlink():
        shutil.rmtree(p)
    else:
        p.unlink()
    return None


def _fs_auth(req: web.Request) -> bool:
    auth = req.headers.get("Authorization", "")
    return auth.startswith("Bearer ") and hmac.compare_digest(auth[7:].strip(), KEY)


async def fs_download(req: web.Request):
    """GET /fs/download?path=C:\\file: streams a PC file to the phone (ranges supported)."""
    if not _fs_auth(req):
        return web.json_response({"error": "unauthorized"}, status=401)
    p = Path(os.path.expandvars(os.path.expanduser(req.query.get("path", ""))))
    if not p.is_absolute() or not await _io(p.is_file):
        return web.json_response({"error": "not a file"}, status=404)
    return web.FileResponse(p, headers={"Content-Disposition": "attachment; filename*=UTF-8''" + quote(p.name)})


async def fs_upload(req: web.Request):
    """POST /fs/upload?path=<folder>&name=<file name>[&overwrite=1]: raw body is written into that PC folder."""
    if not _fs_auth(req):
        return web.json_response({"error": "unauthorized"}, status=401)
    d = Path(os.path.expandvars(os.path.expanduser(req.query.get("path", ""))))
    name = req.query.get("name", "").strip()
    if not d.is_absolute() or not await _io(d.is_dir):
        return web.json_response({"error": "not a folder"}, status=400)
    if not name or any(c in name for c in '\\/:*?"<>|') or name in (".", ".."):
        return web.json_response({"error": "invalid name"}, status=400)
    dest = d / name
    if dest.exists() and req.query.get("overwrite") != "1":
        return web.json_response({"error": "exists"}, status=409)
    tmp = d / (name + ".hermes-part")
    try:
        with open(tmp, "wb") as f:
            async for chunk in req.content.iter_chunked(1 << 20):
                await _io(f.write, chunk)
        await _io(lambda: os.replace(tmp, dest))
    except OSError as e:
        await _io(lambda: tmp.unlink(missing_ok=True))
        return web.json_response({"error": f"upload failed: {e.strerror or e}"}, status=400)
    log.info("phone uploaded %s", dest)
    return web.json_response({"ok": True})


VDISPLAY = HERMES_HOME / "launchers" / "hermes-vdisplay.py"   # open-source virtual display CLI (pc/vdisplay)
# on/off = agent screen; screen-off/screen-on = main screen dark + user windows on the user screen (independent)
# no "on": only the agent opens its own screen; the phone can only see and close it
VD_OPS = {"status": ["status"], "off": ["off"], "screens": ["screens"], "screen-off": ["screen", "off"],
          "screen-on": ["screen", "on"], "dim": ["screen", "dim"], "undim": ["screen", "undim"], "extend": ["mode", "extend"], "second-only": ["mode", "second-only"]}


async def vdisplay_op(req: web.Request):
    """POST /vdisplay/status|on|off|screen-off|screen-on: the phone app's agent-screen and screen-off switches."""
    if not _authed(req):
        return web.json_response({"error": "unauthorized"}, status=401)
    args = VD_OPS.get(req.match_info["op"])
    if not args:
        return web.json_response({"error": "not found"}, status=404)
    if not VDISPLAY.exists():
        return web.json_response({"installed": False, "error": "virtual display not installed on the PC"})
    try:
        body = await req.json() if req.can_read_body else {}
    except ValueError:
        body = {}
    sid = str((body or {}).get("session") or "")
    env = dict(os.environ)
    if re.fullmatch(r"[\w.:-]{1,160}", sid):          # each phone chat drives its own session's agent screen
        env["HERMES_VDISPLAY_SESSION"] = sid
    disp = str((body or {}).get("display") or "")
    if args[0] == "screen" and re.fullmatch(r"\\\\\.\\DISPLAY\d{1,3}", disp):   # per-screen screen off/on
        args = args + [disp]
    py = Path(sys.executable).with_name("python.exe")
    proc = await asyncio.create_subprocess_exec(str(py if py.exists() else sys.executable), "-I", str(VDISPLAY), *args, env=env,
                                                stdout=subprocess.PIPE, stderr=subprocess.PIPE, creationflags=NO_WINDOW)
    try:
        out, _ = await asyncio.wait_for(proc.communicate(), 90)
    except asyncio.TimeoutError:
        proc.kill()
        return web.json_response({"error": "timed out"}, status=504)
    try:
        res = json.loads(out.decode("utf-8", "replace") or "{}")
    except ValueError:
        res = {"ok": False, "error": "bad output from hermes-vdisplay"}
    if args[0] != "status":
        log.info("phone vdisplay %s -> %s", req.match_info["op"], res.get("ok"))
    return web.json_response(res)


_VK = {"ctrl": 0x11, "control": 0x11, "alt": 0x12, "shift": 0x10, "win": 0x5B, "esc": 0x1B, "escape": 0x1B, "tab": 0x09,
       "space": 0x20, "enter": 0x0D, "return": 0x0D, "backspace": 0x08, "delete": 0x2E, "del": 0x2E, "insert": 0x2D,
       "home": 0x24, "end": 0x23, "pageup": 0x21, "pagedown": 0x22, "left": 0x25, "up": 0x26, "right": 0x27, "down": 0x28,
       "printscreen": 0x2C, "volumeup": 0xAF, "volumedown": 0xAE, "mute": 0xAD, "playpause": 0xB3, "nexttrack": 0xB0,
       "prevtrack": 0xB1, "launchapp1": 0xB6, "launchapp2": 0xB7}
_VK.update({f"f{i}": 0x6F + i for i in range(1, 25)})
_VK.update({c: ord(c.upper()) for c in "abcdefghijklmnopqrstuvwxyz0123456789"})
_EXT = {0x2E, 0x2D, 0x24, 0x23, 0x21, 0x22, 0x25, 0x26, 0x27, 0x28, 0x5B}


def _press_combo(codes):
    """Press the keys in order, release in reverse (a real key chord; hotkey tools such as vendor fan/performance apps see it)."""
    import ctypes
    u32 = ctypes.windll.user32
    for c in codes:
        u32.keybd_event(c, u32.MapVirtualKeyW(c, 0), 1 if c in _EXT else 0, 0)
        time.sleep(0.03)
    time.sleep(0.08)
    for c in reversed(codes):
        u32.keybd_event(c, u32.MapVirtualKeyW(c, 0), (1 if c in _EXT else 0) | 2, 0)
        time.sleep(0.02)


async def pc_keys(req: web.Request):
    """POST /pc/keys {"keys": "ctrl+alt+f10"}: simulates that key chord on the PC (modifiers + at most one other key)."""
    if not _authed(req):
        return web.json_response({"error": "unauthorized"}, status=401)
    try:
        body = await req.json()
    except ValueError:
        body = {}
    parts = [x.strip().lower() for x in str((body or {}).get("keys") or "").split("+") if x.strip()]
    if not 1 <= len(parts) <= 4 or any(x not in _VK for x in parts):
        return web.json_response({"ok": False, "error": "bad key combo"}, status=400)
    mods = {"ctrl", "control", "alt", "shift", "win"}
    if sum(1 for x in parts if x not in mods) > 1:
        return web.json_response({"ok": False, "error": "use modifiers plus one key"}, status=400)
    codes = [_VK[x] for x in parts if x in mods] + [_VK[x] for x in parts if x not in mods]
    await asyncio.get_running_loop().run_in_executor(None, _press_combo, codes)
    log.info("phone keys %s", "+".join(parts))
    return web.json_response({"ok": True, "keys": "+".join(parts)})


async def vdisplay_shot(req: web.Request):
    """POST /vdisplay/shot {"display": "<device name>"} -> image/jpeg of that screen (phone screen manager)."""
    if not _authed(req):
        return web.json_response({"error": "unauthorized"}, status=401)
    try:
        body = await req.json()
    except ValueError:
        body = {}
    name = str((body or {}).get("display") or "")
    if not re.fullmatch(r"\\\\\.\\DISPLAY\d{1,3}", name) or not VDISPLAY.exists():
        return web.json_response({"error": "bad display"}, status=400)
    out = Path(tempfile.gettempdir()) / f"hermes-shot-{os.getpid()}-{time.time_ns()}.jpg"
    py = Path(sys.executable).with_name("python.exe")
    proc = await asyncio.create_subprocess_exec(str(py if py.exists() else sys.executable), "-I", str(VDISPLAY),
                                                "shot-display", name, str(out), stdout=subprocess.PIPE,
                                                stderr=subprocess.PIPE, creationflags=NO_WINDOW)
    try:
        _, err = await asyncio.wait_for(proc.communicate(), 60)
    except asyncio.TimeoutError:
        proc.kill()
        return web.json_response({"error": "timed out"}, status=504)
    if not out.exists():
        return web.json_response({"error": (err.decode("utf-8", "replace").strip().splitlines() or ["shot failed"])[-1]},
                                 status=500)
    data = out.read_bytes(); out.unlink(missing_ok=True)
    return web.Response(body=data, content_type="image/jpeg", headers={"Cache-Control": "no-store"})


ELEVATE = HERMES_HOME / "launchers" / "hermes-elevate.py"   # phone-approved elevation (pc/elevate)


async def _elevate_cli(*args):
    py = Path(sys.executable).with_name("python.exe")
    proc = await asyncio.create_subprocess_exec(str(py if py.exists() else sys.executable), "-I", str(ELEVATE), *args,
                                                stdout=subprocess.PIPE, stderr=subprocess.PIPE, creationflags=NO_WINDOW)
    out, _ = await asyncio.wait_for(proc.communicate(), 30)
    return json.loads(out.decode("utf-8", "replace") or "null")


_TABS: dict = {}   # device -> (last report time, [{"profile","id"}]): chats each phone currently has open as tabs
_TABS_TTL = 180


async def tabs_report(req: web.Request):
    """POST /tabs {device, tabs:[{profile,id}]}: a phone reports its open tabs (also an empty list when it closes them)."""
    if not _authed(req):
        return web.json_response({"error": "unauthorized"}, status=401)
    import time
    try:
        body = await req.json()
        _TABS[str(body["device"])] = (time.time(), [t for t in body.get("tabs", []) if t.get("id")])
    except Exception as e:
        return web.json_response({"error": str(e)}, status=400)
    return web.json_response({"ok": True})


async def tabs_list(req: web.Request):
    """GET /tabs: every phone's open tabs (reports older than the TTL are dropped)."""
    if not _authed(req):
        return web.json_response({"error": "unauthorized"}, status=401)
    import time
    now = time.time()
    for d in [d for d, (t, _) in _TABS.items() if now - t > _TABS_TTL]:
        _TABS.pop(d, None)
    return web.json_response([{"device": d, "profile": t.get("profile"), "id": t["id"]} for d, (_, tabs) in _TABS.items() for t in tabs])


STATS = HERMES_HOME / "launchers" / "reply-stats.json"   # {session: {replyKey: [tokIn, tokOut, prefillTps, genTps]}} shared by every device
_STATS_LOCK = threading.Lock()


def _stats_load() -> dict:
    try:
        return json.loads(STATS.read_text(encoding="utf-8"))
    except Exception:
        return {}


async def stats_get(req: web.Request):
    """GET /stats?session=<id>: the per-reply token/speed stats any device recorded for that session."""
    if not _authed(req):
        return web.json_response({"error": "unauthorized"}, status=401)
    with _STATS_LOCK:
        return web.json_response(_stats_load().get(req.query.get("session", ""), {}))


async def stats_put(req: web.Request):
    """POST /stats {session, stats:{replyKey:[4 numbers|null]}}: merge a device's measured stats (existing keys are kept)."""
    if not _authed(req):
        return web.json_response({"error": "unauthorized"}, status=401)
    try:
        body = await req.json()
        sid = str(body["session"]); new = body["stats"]
        assert sid and isinstance(new, dict) and len(new) <= 500
        assert all(isinstance(v, list) and len(v) == 4 for v in new.values())
    except Exception:
        return web.json_response({"error": "bad request"}, status=400)
    with _STATS_LOCK:
        allst = _stats_load(); cur = allst.setdefault(sid, {})
        for k, v in new.items():
            cur.setdefault(k, v)
        while len(cur) > 300:
            cur.pop(next(iter(cur)))
        while len(allst) > 500:
            allst.pop(next(iter(allst)))
        tmp = STATS.with_suffix(".tmp"); tmp.write_text(json.dumps(allst), encoding="utf-8"); os.replace(tmp, STATS)
    return web.json_response({"ok": True})


async def elevate_pending(req: web.Request):
    """GET /elevate/pending: elevation requests the agent made, waiting for the phone's decision."""
    if not _authed(req):
        return web.json_response({"error": "unauthorized"}, status=401)
    if not ELEVATE.exists():
        return web.json_response([])
    try:
        return web.json_response(await _elevate_cli("pending") or [])
    except Exception as e:
        return web.json_response({"error": str(e)}, status=500)


async def elevate_decide(req: web.Request):
    """POST /elevate/approve/<id> | /elevate/deny/<id>"""
    if not _authed(req):
        return web.json_response({"error": "unauthorized"}, status=401)
    op, rid = req.match_info["op"], req.match_info["id"]
    if op not in ("approve", "deny") or not re.fullmatch(r"[0-9a-f]{8}", rid) or not ELEVATE.exists():
        return web.json_response({"error": "not found"}, status=404)
    try:
        res = await _elevate_cli(op, rid)
    except Exception as e:
        return web.json_response({"error": str(e)}, status=500)
    log.info("phone elevate %s %s -> %s", op, rid, (res or {}).get("ok"))
    return web.json_response(res or {"ok": False})


OUTBOX = HERMES_HOME / "launchers" / "phone-outbox"   # files the agent sends to the phone (send-to-phone.py)
SAFE_NAME = re.compile(r'[^\x00-\x1f<>:"/\\|?*\s.][^\x00-\x1f<>:"/\\|?*]{0,200}')   # Unicode names allowed

def root_session(sid, home):
    """A sub-agent's session id -> the dispatching (root) session id, via parent_session_id in the profiles' state.db files.
    Unknown ids and any error return the id unchanged."""
    import sqlite3
    dbs = [home / "state.db"] + sorted((home / "profiles").glob("*/state.db"))
    for _ in range(8):
        parent = None
        for db in dbs:
            try:
                c = sqlite3.connect("file:" + str(db).replace("\\", "/") + "?mode=ro", uri=True, timeout=2)
                try:
                    row = c.execute("select parent_session_id from sessions where id = ?", (sid,)).fetchone()
                finally:
                    c.close()
            except Exception:
                continue
            if row and row[0]:
                parent = row[0]
                break
        if not parent or parent == sid:
            break
        sid = parent
    return sid


def _authed(req):
    auth = req.headers.get("Authorization", "")
    return auth.startswith("Bearer ") and hmac.compare_digest(auth[7:].strip(), KEY)


def _outbox_files():
    OUTBOX.mkdir(parents=True, exist_ok=True)
    files = [f for f in OUTBOX.iterdir() if f.is_file() and SAFE_NAME.fullmatch(f.name) and not f.name.endswith((".session", ".anchor"))]
    files.sort(key=lambda f: f.stat().st_mtime, reverse=True)
    out = []
    for f in files:
        side = f.with_name(f.name + ".session")
        sess = re.sub(r"[^A-Za-z0-9_\-]", "", side.read_text(encoding="utf-8").strip()) if side.is_file() else ""
        if sess:
            sess = root_session(sess, HERMES_HOME)   # sub-agent session -> its dispatcher's chat
        anc = f.with_name(f.name + ".anchor")
        try:
            anchor = int(anc.read_text(encoding="utf-8").strip()) if anc.is_file() else 0
        except ValueError:
            anchor = 0
        out.append({"name": f.name, "size": f.stat().st_size, "time": int(f.stat().st_mtime), "session": sess, "anchor": anchor})
    return out


async def outbox_list(req: web.Request):
    """GET /outbox?wait=N&known=k1,k2: files waiting for the phone (put there by send-to-phone.py).

    With `known` (keys "name|time" the phone already has) it long-polls: returns as soon as a file
    the phone doesn't know yet appears, or after `wait` seconds (max 30) with the current list."""
    if not _authed(req):
        return web.json_response({"error": "unauthorized"}, status=401)
    try:
        wait = min(max(int(req.query.get("wait", "0")), 0), 30)
    except ValueError:
        wait = 0
    known = set(filter(None, req.query.get("known", "").split(",")))
    loop = asyncio.get_running_loop()
    deadline = loop.time() + wait
    while True:
        files = await _io(_outbox_files)
        if any(f"{x['name']}|{x['time']}" not in known for x in files) or loop.time() >= deadline:
            break
        await asyncio.sleep(1)
    return web.json_response({"files": files})


async def outbox_send(req: web.Request):
    """POST /outbox/send {path, session}: an agent reply said "MEDIA:<path>": copy that PC file into the outbox
    (same as send-to-phone.py) so the phone shows it in the chat."""
    if not _authed(req):
        return web.json_response({"error": "unauthorized"}, status=401)
    try:
        body = await req.json()
        src = Path(str(body.get("path", ""))).expanduser()
        sess = re.sub(r"[^A-Za-z0-9_\-]", "", str(body.get("session", "")))
        anchor = int(body.get("ts") or 0)   # ms timestamp of the reply that said MEDIA: the card is shown right after it
    except Exception:
        return web.json_response({"error": "bad request"}, status=400)

    def work():
        import shutil
        if not src.is_file():
            return None, "not a file"
        if src.stat().st_size > 500 * 1024 * 1024:
            return None, "file is over 500 MB"
        OUTBOX.mkdir(parents=True, exist_ok=True)
        base = re.sub(r'[\x00-\x1f<>:"/\\|?*]', "_", src.name.strip())[:150].strip(" .") or "file"
        if base.endswith(".session"):
            base += "_"
        stem, suf = os.path.splitext(base)
        dst, n = OUTBOX / base, 2
        while dst.exists():
            dst = OUTBOX / f"{stem} ({n}){suf}"
            n += 1
        shutil.copyfile(src, dst)
        if sess:
            dst.with_name(dst.name + ".session").write_text(sess, encoding="utf-8")
        if anchor > 0:
            dst.with_name(dst.name + ".anchor").write_text(str(anchor), encoding="utf-8")
        return dst.name, None

    name, err = await _io(work)
    if err:
        return web.json_response({"error": err}, status=404)
    return web.json_response({"ok": True, "name": name})


async def outbox_item(req: web.Request):
    """GET /outbox/<name> downloads a file; DELETE removes it once the phone has saved it."""
    if not _authed(req):
        return web.json_response({"error": "unauthorized"}, status=401)
    name = req.match_info["name"]
    if not SAFE_NAME.fullmatch(name):
        return web.json_response({"error": "bad name"}, status=400)
    f = OUTBOX / name
    if not await _io(f.is_file) or name.endswith(".session"):
        return web.json_response({"error": "not found"}, status=404)
    if req.method == "DELETE":
        await _io(lambda: (f.unlink(), f.with_name(name + ".session").unlink(missing_ok=True), f.with_name(name + ".anchor").unlink(missing_ok=True)))
        return web.json_response({"ok": True})
    return web.FileResponse(f, headers={"Content-Disposition": f'attachment; filename="{name}"', "Cache-Control": "no-store"})


# ---------------------------------------------------------------- phone -> PC uploads (any size, chunked, resumable)
INBOX = Path(os.environ.get("RELAY_INBOX") or HERMES_HOME / "inbox")          # <inbox>/<yyyy-mm-dd>/<name>; the agent reads these PC paths
UPLOADS = {}                           # id -> {"part","final","size","got":{offset: length}}
UP_LOCK = threading.Lock()
BAD_CHARS = re.compile(r'[<>:"/\\|?*\x00-\x1f]')
WIN_RESERVED = re.compile(r"(?i)^(con|prn|aux|nul|com[0-9]|lpt[0-9])(\..*)?$")


def _clean_name(name):
    name = BAD_CHARS.sub("_", os.path.basename(str(name).replace("\\", "/"))).strip(" .") or "file"
    if WIN_RESERVED.match(name):
        name = "_" + name
    stem, dot, ext = name.rpartition(".")
    return name[:150] if not dot or not stem else stem[:150 - len(ext) - 1] + "." + ext[:20]


def _upload_init(name, size):
    day = INBOX / time.strftime("%Y-%m-%d")
    day.mkdir(parents=True, exist_ok=True)
    base = _clean_name(name)
    stem, dot, ext = base.rpartition(".")
    if not dot or not stem:
        stem, ext = base, ""
    for n in range(0, 10000):
        cand = base if n == 0 else f"{stem} ({n}){'.' + ext if ext else ''}"
        final = day / cand
        part = day / (cand + ".part")
        if final.exists():
            continue
        try:
            with open(part, "xb") as f:      # exclusive create: reserves the name, never overwrites
                if size:
                    f.truncate(size)
        except FileExistsError:
            continue
        uid = os.urandom(12).hex()
        with UP_LOCK:
            UPLOADS[uid] = {"part": part, "final": final, "size": size, "got": {}}
        return uid
    raise OSError("no free name")


def _write_at(part, offset, data):
    with open(part, "r+b") as f:
        f.seek(offset)
        f.write(data)


async def upload_init(req: web.Request):
    """POST /upload/init {name,size} -> {id, chunk}"""
    if not _authed(req):
        return web.json_response({"error": "unauthorized"}, status=401)
    try:
        b = await req.json()
        name, size = str(b["name"]), int(b["size"])
        if size < 0:
            raise ValueError
    except Exception:
        return web.json_response({"error": "bad request"}, status=400)
    uid = await _io(_upload_init, name, size)
    return web.json_response({"id": uid, "chunk": 4 * 1024 * 1024})


async def upload_chunk(req: web.Request):
    """PUT /upload/<id>?offset=N (body = bytes, streamed) writes a chunk; GET /upload/<id> lists received chunks (resume)."""
    if not _authed(req):
        return web.json_response({"error": "unauthorized"}, status=401)
    u = UPLOADS.get(req.match_info["id"])
    if not u:
        return web.json_response({"error": "unknown upload"}, status=404)
    if req.method == "GET":
        return web.json_response({"size": u["size"], "got": [[o, n] for o, n in sorted(u["got"].items())]})
    try:
        off = int(req.query["offset"])
    except Exception:
        return web.json_response({"error": "bad offset"}, status=400)
    pos = off
    async for data in req.content.iter_chunked(1 << 20):
        if pos + len(data) > u["size"] or off < 0:
            return web.json_response({"error": "out of range"}, status=416)
        await _io(_write_at, u["part"], pos, data)
        pos += len(data)
    if pos > off:
        u["got"][off] = pos - off
    return web.json_response({"ok": True, "written": pos - off})


async def upload_done(req: web.Request):
    """POST /upload/<id>/done -> {path}; fails with 409 (and the missing ranges) unless every byte arrived."""
    if not _authed(req):
        return web.json_response({"error": "unauthorized"}, status=401)
    uid = req.match_info["id"]
    u = UPLOADS.get(uid)
    if not u:
        return web.json_response({"error": "unknown upload"}, status=404)
    end = 0
    for o, n in sorted(u["got"].items()):
        if o > end:
            break
        end = max(end, o + n)
    if end != u["size"]:
        return web.json_response({"error": "incomplete", "have": end, "size": u["size"]}, status=409)
    await _io(os.replace, u["part"], u["final"])
    UPLOADS.pop(uid, None)
    log.info("upload saved %s (%d bytes)", u["final"], u["size"])
    return web.json_response({"path": str(u["final"]), "size": u["size"]})


# ---------------------------------------------------------------- context / compression settings (all profiles)
CTX_KEYS = {   # phone field -> (config.yaml path, type)
    "compression_model": (("auxiliary", "compression", "model"), str),
    "compression_provider": (("auxiliary", "compression", "provider"), str),
    "context_length": (("model", "context_length"), int),
    "threshold": (("compression", "threshold"), float),
    "target_ratio": (("compression", "target_ratio"), float),
    "protect_last_n": (("compression", "protect_last_n"), int),
}


def _profile_homes():
    homes = {"default": HERMES_HOME}
    pd = HERMES_HOME / "profiles"
    if pd.is_dir():
        for d in sorted(pd.iterdir()):
            if (d / "config.yaml").is_file() and profile_ok(d.name):
                homes[d.name] = d
    return homes


def _yaml():
    from ruamel.yaml import YAML
    y = YAML(); y.preserve_quotes = True; y.width = 4096
    return y


def _ctx_read(home):
    try:
        data = _yaml().load((home / "config.yaml").read_text(encoding="utf-8")) or {}
    except Exception:
        return {}
    out = {}
    for k, (path, _t) in CTX_KEYS.items():
        v = data
        for part in path:
            v = v.get(part) if hasattr(v, "get") else None
        out[k] = v if isinstance(v, (int, float, str)) and not isinstance(v, bool) else None
    return out


def _ctx_state():
    per = {name: _ctx_read(h) for name, h in _profile_homes().items()}
    base = per.get("default") or {}
    return {"settings": base, "profiles": list(per), "differs": [n for n, v in per.items() if v != base]}


def _ctx_write(values):
    """Write the given settings into every profile's config.yaml (round-trip: comments and order kept)."""
    y = _yaml()
    for name, home in _profile_homes().items():
        f = home / "config.yaml"
        data = y.load(f.read_text(encoding="utf-8")) or {}
        for k, v in values.items():
            path, _t = CTX_KEYS[k]
            node = data
            for part in path[:-1]:
                if not hasattr(node.get(part), "get"):
                    node[part] = {}
                node = node[part]
            if v is None:
                node.pop(path[-1], None)
            else:
                node[path[-1]] = v
        tmp = f.with_name(f.name + ".phone.tmp")
        with open(tmp, "w", encoding="utf-8") as fh:
            y.dump(data, fh)
        os.replace(tmp, f)
        log.info("phone set context settings for profile %s: %s", name, sorted(values))


async def config_context(req: web.Request):
    """GET /config/context -> current settings; POST {field: value|null} writes them into every profile and
    answers with what the PC now has (the phone shows only that)."""
    if not _authed(req):
        return web.json_response({"error": "unauthorized"}, status=401)
    if req.method == "POST":
        try:
            body = await req.json()
        except ValueError:
            return web.json_response({"error": "bad request"}, status=400)
        vals = {}
        for k, v in (body or {}).items():
            if k not in CTX_KEYS:
                continue
            t = CTX_KEYS[k][1]
            if v is None or v == "":
                vals[k] = None; continue
            try:
                v = t(v)
            except (TypeError, ValueError):
                return web.json_response({"error": f"bad value for {k}"}, status=400)
            if (k in ("threshold", "target_ratio") and not 0 < v <= 1) or \
                    (k == "context_length" and not 1024 <= v <= 10_000_000) or (k == "protect_last_n" and not 0 <= v <= 500):
                return web.json_response({"error": f"{k} out of range"}, status=400)
            vals[k] = v
        try:
            await asyncio.get_running_loop().run_in_executor(_IO_POOL, _ctx_write, vals)
        except Exception as e:
            log.warning("context settings write failed: %s", e)
            return web.json_response({"error": f"write failed: {e}"}, status=500)
    return web.json_response(await asyncio.get_running_loop().run_in_executor(_IO_POOL, _ctx_state))


# ---------------------------------------------------------------- advanced delegation settings (all profiles)
# Only these four delegation.* keys; names/meaning checked against the installed Hermes delegate_tool_config.py.
DELEG_KEYS = {"max_concurrent_children": int, "max_spawn_depth": int, "child_timeout_seconds": float, "reasoning_effort": str}
DELEG_EFFORTS = ("none", "minimal", "low", "medium", "high", "xhigh", "max")


def _deleg_read(home):
    try:
        data = _yaml().load((home / "config.yaml").read_text(encoding="utf-8")) or {}
    except Exception:
        return {}
    sec = data.get("delegation") if hasattr(data, "get") else None
    out = {}
    for k in DELEG_KEYS:
        v = sec.get(k) if hasattr(sec, "get") else None
        out[k] = v if isinstance(v, (int, float, str)) and not isinstance(v, bool) else None
    return out


def _deleg_state():
    per = {name: _deleg_read(h) for name, h in _profile_homes().items()}
    base = per.get("default") or {}
    return {"settings": base, "profiles": list(per), "differs": [n for n, v in per.items() if v != base]}


def _deleg_write(values):
    """Write the given delegation.* values into every profile's config.yaml (None removes the key)."""
    y = _yaml()
    for name, home in _profile_homes().items():
        f = home / "config.yaml"
        data = y.load(f.read_text(encoding="utf-8")) or {}
        if not hasattr(data.get("delegation"), "get"):
            data["delegation"] = {}
        sec = data["delegation"]
        for k, v in values.items():
            if v is None:
                sec.pop(k, None)
            else:
                sec[k] = v
        tmp = f.with_name(f.name + ".phone.tmp")
        with open(tmp, "w", encoding="utf-8") as fh:
            y.dump(data, fh)
        os.replace(tmp, f)
        log.info("phone set delegation settings for profile %s: %s", name, sorted(values))


async def config_delegation(req: web.Request):
    """GET /config/delegation -> current values; POST {field: value|null} writes them into every profile."""
    if not _authed(req):
        return web.json_response({"error": "unauthorized"}, status=401)
    if req.method == "POST":
        try:
            body = await req.json()
        except ValueError:
            return web.json_response({"error": "bad request"}, status=400)
        vals = {}
        for k, v in (body or {}).items():
            if k not in DELEG_KEYS:
                continue
            if v is None or v == "":
                vals[k] = None
                continue
            if k == "reasoning_effort":
                if str(v) not in DELEG_EFFORTS:
                    return web.json_response({"error": f"bad value for {k}"}, status=400)
                vals[k] = str(v)
                continue
            try:
                v = DELEG_KEYS[k](v)
            except (TypeError, ValueError):
                return web.json_response({"error": f"bad value for {k}"}, status=400)
            if (k == "max_concurrent_children" and not 1 <= v <= 100) or \
                    (k == "max_spawn_depth" and not 1 <= v <= 10) or \
                    (k == "child_timeout_seconds" and not 0 < v <= 86400):
                return web.json_response({"error": f"{k} out of range"}, status=400)
            vals[k] = v
        try:
            await asyncio.get_running_loop().run_in_executor(_IO_POOL, _deleg_write, vals)
        except Exception as e:
            log.warning("delegation settings write failed: %s", e)
            return web.json_response({"error": f"write failed: {e}"}, status=500)
    return web.json_response(await asyncio.get_running_loop().run_in_executor(_IO_POOL, _deleg_state))


# ---------------------------------------------------------------- advanced delegation: mode + routes (per profile)
# The plugin's delegation.backend and delegation.backends.{model_map,profile_routes} keys (advanced-delegation v1.2.0).
# Writes touch only those keys of the chosen profile (or every profile with "all_profiles": true); ruamel round-trips
# the file so comments and other settings survive. Route checks run the plugin's own store.validate_route in the
# hermes-agent venv (same answers as the plugin); if that cannot run, only the structural checks below apply.
ADV_MODES = ("default", "model_map", "profile_routes")
ADV_ROUTE_TEXT = ("name", "description", "provider", "base_url", "model", "api_key", "api_mode", "reasoning_effort", "service_tier")
ADV_EFFORTS = ("none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra")
ADV_TIERS = ("normal", "fast", "ultrafast")
ADV_NAME_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$")
ADV_AGENT = HERMES_HOME / "hermes-agent"
ADV_PY = ADV_AGENT / "venv" / "Scripts" / "python.exe"
ADV_PLUGIN = HERMES_HOME / "plugins" / "advanced-delegation"
ADV_VALIDATE = (
    "import json, sys\n"
    "sys.path.insert(0, sys.argv[1]); sys.path.insert(0, sys.argv[2])\n"
    "import store\n"
    "routes, others, out = json.load(sys.stdin), [], []\n"
    "for r in routes:\n"
    "    out.extend(f\"{r.get('name')}: {p}\" for p in store.validate_route(r, others))\n"
    "    others.append(r)\n"
    "print(json.dumps(out))\n"
)


def _adv_plugin_problems(home, routes):
    """The plugin's validate_route problems for ``routes`` under profile ``home`` ([] = clean); None when it cannot run."""
    if not routes:
        return []
    if not (ADV_PY.is_file() and (ADV_PLUGIN / "store.py").is_file()):
        return None
    env = {**os.environ, "HERMES_HOME": str(home)}
    try:
        r = subprocess.run([str(ADV_PY), "-I", "-c", ADV_VALIDATE, str(ADV_AGENT), str(ADV_PLUGIN)],
                           input=json.dumps(routes), capture_output=True, text=True, encoding="utf-8",
                           timeout=60, env=env, cwd=str(ADV_AGENT),
                           creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    except (OSError, subprocess.SubprocessError) as e:
        log.warning("plugin route check could not run: %s", e)
        return None
    if r.returncode != 0:
        log.warning("plugin route check failed: %s", (r.stderr or "")[-400:])
        return None
    try:
        return [str(p) for p in json.loads(r.stdout.strip().splitlines()[-1])]
    except (ValueError, IndexError):
        return None


def _adv_clean(body, homes):
    """Structural checks on a phone POST. Returns (clean, error); clean = mode, options, option_default, profiles, profile_default."""
    if not isinstance(body, dict):
        return None, "bad request"
    mode = body.get("mode")
    if mode not in ADV_MODES:
        return None, "bad mode"
    mm, pr = body.get("model_map") or {}, body.get("profile_routes") or {}
    if not isinstance(mm, dict) or not isinstance(pr, dict):
        return None, "bad model_map or profile_routes"
    raw_opts, raw_profs = mm.get("options") or [], pr.get("profiles") or []
    if not isinstance(raw_opts, list) or not isinstance(raw_profs, list) or len(raw_opts) > 50 or len(raw_profs) > 50:
        return None, "bad route list"
    options, names = [], []
    for raw in raw_opts:
        if not isinstance(raw, dict):
            return None, "bad model route"
        route = {k: v for k, v in raw.items() if v is not None}
        for k in ADV_ROUTE_TEXT:
            if route.get(k) is None:
                route.pop(k, None)
                continue
            if not isinstance(route[k], str):
                return None, f"bad value for route field {k}"
            v = route[k].strip() if k in ("api_key", "base_url") else " ".join(route[k].split())
            if v:
                route[k] = v
            else:
                route.pop(k)
        name = route.get("name", "")
        if not ADV_NAME_RE.match(name):
            return None, f"route name {name!r}: letters, digits, '.', '_' or '-' (no spaces), starting with a letter or digit"
        if name in names:
            return None, f"two routes are named {name!r}"
        if not route.get("description"):
            return None, f"route {name}: needs a one-line description"
        if not route.get("model"):
            return None, f"route {name}: needs a model"
        if not (route.get("provider") or route.get("base_url")):
            return None, f"route {name}: needs a provider or a base_url"
        if route.get("reasoning_effort") and route["reasoning_effort"] not in ADV_EFFORTS:
            return None, f"route {name}: unknown reasoning effort {route['reasoning_effort']!r}"
        if route.get("service_tier") and route["service_tier"] not in ADV_TIERS:
            return None, f"route {name}: unknown speed tier {route['service_tier']!r}"
        if route.get("daybreak") not in (None, True, False):
            return None, f"route {name}: daybreak must be true or false"
        names.append(name)
        options.append(route)
    option_default = mm.get("default") or ""
    if not isinstance(option_default, str):
        return None, "bad model route default"
    option_default = " ".join(option_default.split())
    if option_default and option_default not in names:
        return None, f"default {option_default!r} is not one of the model routes"
    profiles, pnames = [], []
    for raw in raw_profs:
        if not isinstance(raw, dict) or not isinstance(raw.get("name"), str):
            return None, "bad profile entry"
        pname = raw["name"].strip()
        if pname not in homes:
            return None, f"unknown profile {pname!r}"
        if pname in pnames:
            return None, f"profile {pname!r} is listed twice"
        desc = raw.get("description") or ""
        if not isinstance(desc, str):
            return None, f"bad description for profile {pname}"
        profiles.append({"name": pname, "description": " ".join(desc.split())[:200]})
        pnames.append(pname)
    profile_default = pr.get("default") or ""
    if not isinstance(profile_default, str):
        return None, "bad profile default"
    profile_default = profile_default.strip()
    if profile_default and profile_default not in pnames:
        return None, f"default profile {profile_default!r} is not one of the profile routes"
    if mode == "model_map" and not options:
        return None, "Add at least one model route, or choose Default."
    if mode == "profile_routes" and not profiles:
        return None, "Check at least one profile, or choose Default."
    return {"mode": mode, "options": options, "option_default": option_default,
            "profiles": profiles, "profile_default": profile_default}, ""


def _adv_set_default(block, value):
    if value:
        block["default"] = value
    elif "default" in block:
        block["default"] = ""  # blank, never removed (the plugin's own writer refuses dropped keys)


def _adv_write(name, home, clean):
    y = _yaml()
    y.indent(mapping=2, sequence=4, offset=2)  # the layout the user's config.yaml already has: a no-op for untouched keys
    f = home / "config.yaml"
    data = y.load(f.read_text(encoding="utf-8")) or {}
    if not hasattr(data.get("delegation"), "get"):
        data["delegation"] = {}
    sec = data["delegation"]
    sec["backend"] = clean["mode"]
    if not hasattr(sec.get("backends"), "get"):
        sec["backends"] = {}
    backends = sec["backends"]
    for key in ("model_map", "profile_routes"):
        if not hasattr(backends.get(key), "get"):
            backends[key] = {}
    mm, pr = backends["model_map"], backends["profile_routes"]
    mm["options"] = clean["options"]
    _adv_set_default(mm, clean["option_default"])
    pr["profiles"] = clean["profiles"]
    _adv_set_default(pr, clean["profile_default"])
    tmp = f.with_name(f.name + ".phone.tmp")
    with open(tmp, "w", encoding="utf-8") as fh:
        y.dump(data, fh)
    os.replace(tmp, f)
    log.info("phone set advanced delegation for profile %s: mode %s", name, clean["mode"])


def _adv_state(name, home, names):
    try:
        data = _yaml().load((home / "config.yaml").read_text(encoding="utf-8")) or {}
    except Exception:
        data = {}
    sec = data.get("delegation") if hasattr(data, "get") else None
    sec = sec if hasattr(sec, "get") else {}
    backend = sec.get("backend")
    backends = sec.get("backends")
    backends = backends if hasattr(backends, "get") else {}
    mm = backends.get("model_map")
    mm = mm if hasattr(mm, "get") else {}
    pr = backends.get("profile_routes")
    pr = pr if hasattr(pr, "get") else {}
    options = [dict(o) for o in (mm.get("options") or []) if hasattr(o, "get")]
    profs = [dict(p) for p in (pr.get("profiles") or []) if hasattr(p, "get")]
    mdef, pdef = mm.get("default"), pr.get("default")
    return {
        "profile": name,
        "mode": backend.strip().lower() if isinstance(backend, str) and backend.strip() else "default",
        "model_map": {"options": options, "default": mdef if isinstance(mdef, str) else ""},
        "profile_routes": {"profiles": [{"name": str(p.get("name") or ""), "description": str(p.get("description") or "")}
                                        for p in profs],
                           "default": pdef if isinstance(pdef, str) else ""},
        "others": [n for n in names if n != name],
        "efforts": list(ADV_EFFORTS),
    }


async def config_delegation_advanced(req: web.Request):
    """GET /config/delegation/advanced?profile=NAME -> that profile's mode and routes. POST the same shape to write it
    ("all_profiles": true writes every profile); the answer is the re-read state of NAME."""
    if not _authed(req):
        return web.json_response({"error": "unauthorized"}, status=401)
    loop = asyncio.get_running_loop()
    homes = await loop.run_in_executor(_IO_POOL, _profile_homes)
    name = (req.query.get("profile") or "default").strip() or "default"
    if name not in homes:
        return web.json_response({"error": "unknown profile"}, status=404)
    if req.method == "POST":
        try:
            body = await req.json()
        except ValueError:
            return web.json_response({"error": "bad request"}, status=400)
        clean, err = _adv_clean(body, homes)
        if err:
            return web.json_response({"error": err}, status=400)
        targets = dict(homes) if body.get("all_profiles") is True else {name: homes[name]}

        def apply():
            for pname, home in targets.items():
                probs = _adv_plugin_problems(home, clean["options"])
                if probs:
                    return f"{pname}: " + " ".join(probs)
            for pname, home in targets.items():
                _adv_write(pname, home, clean)
            return None

        try:
            problem = await loop.run_in_executor(_IO_POOL, apply)
        except Exception as e:
            log.warning("advanced delegation write failed: %s", e)
            return web.json_response({"error": f"write failed: {e}"}, status=500)
        if problem:
            return web.json_response({"error": problem}, status=400)
    return web.json_response(await loop.run_in_executor(_IO_POOL, _adv_state, name, homes[name], list(homes)))


# ---------------------------------------------------------------- model settings (all profiles)# ---------------------------------------------------------------- model settings (all profiles)
# main -> model.default / model.provider; delegation -> delegation.model / delegation.provider;
# auxiliary.<task> -> auxiliary.<task>.model/provider, tasks = whatever the config.yaml auxiliary section holds.
MODEL_PATHS = {
    "main": {"model": ("model", "default"), "provider": ("model", "provider"), "reasoning_effort": ("agent", "reasoning_effort")},
    "delegation": {"model": ("delegation", "model"), "provider": ("delegation", "provider"), "reasoning_effort": ("delegation", "reasoning_effort")},
}


def _model_read(home):
    try:
        data = _yaml().load((home / "config.yaml").read_text(encoding="utf-8")) or {}
    except Exception:
        return {}
    out = {"main": {}, "delegation": {}, "auxiliary": {}}
    for slot, paths in MODEL_PATHS.items():
        for field, path in paths.items():
            v = data
            for part in path:
                v = v.get(part) if hasattr(v, "get") else None
            out[slot][field] = v if isinstance(v, str) else None
    aux = data.get("auxiliary") if hasattr(data, "get") else None
    if hasattr(aux, "get"):
        for task, sub in aux.items():
            if hasattr(sub, "get"):
                out["auxiliary"][task] = {
                    "model": sub.get("model") if isinstance(sub.get("model"), str) else None,
                    "provider": sub.get("provider") if isinstance(sub.get("provider"), str) else None,
                    "reasoning_effort": sub.get("reasoning_effort") if isinstance(sub.get("reasoning_effort"), str) else None,
                }
            else:
                out["auxiliary"][task] = {"model": None, "provider": None, "reasoning_effort": None}
    return out


def _model_state(profile="default"):
    per = {name: _model_read(h) for name, h in _profile_homes().items()}
    base = per.get(profile) or per.get("default") or {}
    return {"settings": base, "profile": profile if profile in per else "default", "profiles": list(per), "differs": [n for n, v in per.items() if v != base]}


def _model_write(body, only=None):
    y = _yaml()
    for name, home in _profile_homes().items():
        if only is not None and name != only:
            continue
        f = home / "config.yaml"
        data = y.load(f.read_text(encoding="utf-8")) or {}
        for slot, paths in MODEL_PATHS.items():
            spec = (body or {}).get(slot)
            if not hasattr(spec, "get"):
                continue
            for field, path in paths.items():
                if field not in spec:
                    continue
                v = spec[field]
                node = data
                for part in path[:-1]:
                    if not hasattr(node.get(part), "get"):
                        node[part] = {}
                    node = node[part]
                if v in (None, ""):
                    node.pop(path[-1], None)
                else:
                    node[path[-1]] = v
        aux = (body or {}).get("auxiliary")
        if hasattr(aux, "get"):
            node = data.get("auxiliary")
            if not hasattr(node, "get"):
                node = {}
                data["auxiliary"] = node
            for task, spec in aux.items():
                if not hasattr(spec, "get"):
                    continue
                sub = node.get(task)
                if not hasattr(sub, "get"):
                    sub = {}
                    node[task] = sub
                for field in ("model", "provider", "reasoning_effort"):
                    if field not in spec:
                        continue
                    v = spec[field]
                    if v in (None, ""):
                        sub.pop(field, None)
                    else:
                        sub[field] = v
        tmp = f.with_name(f.name + ".phone.tmp")
        with open(tmp, "w", encoding="utf-8") as fh:
            y.dump(data, fh)
        os.replace(tmp, f)
        log.info("phone set models for profile %s", name)


def _validate_models(body):
    for slot in ("main", "delegation"):
        spec = body.get(slot)
        if spec is None:
            continue
        if not isinstance(spec, dict):
            return f"bad {slot}"
        for field in ("model", "provider", "reasoning_effort"):
            if field in spec and spec[field] is not None and not isinstance(spec[field], str):
                return f"bad value for {slot}.{field}"
    aux = body.get("auxiliary")
    if aux is not None:
        if not isinstance(aux, dict):
            return "bad auxiliary"
        for task, spec in aux.items():
            if not isinstance(spec, dict):
                return f"bad auxiliary.{task}"
            for field in ("model", "provider", "reasoning_effort"):
                if field in spec and spec[field] is not None and not isinstance(spec[field], str):
                    return f"bad value for auxiliary.{task}.{field}"
    return None


async def config_models(req: web.Request):
    """GET /config/models -> current models; POST writes them into every profile and answers with the re-read state."""
    if not _authed(req):
        return web.json_response({"error": "unauthorized"}, status=401)
    prof = (req.query.get("profile") or "default").strip() or "default"
    if req.method == "POST":
        try:
            body = await req.json()
        except ValueError:
            return web.json_response({"error": "bad request"}, status=400)
        if not isinstance(body, dict):
            return web.json_response({"error": "bad request"}, status=400)
        err = _validate_models(body)
        if err:
            return web.json_response({"error": err}, status=400)
        only = None if body.get("all_profiles") is True else prof
        if only is not None and only not in _profile_homes():
            return web.json_response({"error": f"unknown profile {only}"}, status=400)
        try:
            await asyncio.get_running_loop().run_in_executor(_IO_POOL, _model_write, body, only)
        except Exception as e:
            log.warning("models write failed: %s", e)
            return web.json_response({"error": f"write failed: {e}"}, status=500)
    return web.json_response(await asyncio.get_running_loop().run_in_executor(_IO_POOL, _model_state, prof))


# ---------------------------------------------------------------- PC stats (CPU / memory / GPU, sampled in a thread)
class _FILETIME(ctypes.Structure):
    _fields_ = [("dwLowDateTime", wintypes.DWORD), ("dwHighDateTime", wintypes.DWORD)]


class _MEMORYSTATUSEX(ctypes.Structure):
    _fields_ = [
        ("dwLength", wintypes.DWORD),
        ("dwMemoryLoad", wintypes.DWORD),
        ("ullTotalPhys", ctypes.c_ulonglong),
        ("ullAvailPhys", ctypes.c_ulonglong),
        ("ullTotalPageFile", ctypes.c_ulonglong),
        ("ullAvailPageFile", ctypes.c_ulonglong),
        ("ullTotalVirtual", ctypes.c_ulonglong),
        ("ullAvailVirtual", ctypes.c_ulonglong),
        ("ullAvailExtendedVirtual", ctypes.c_ulonglong),
    ]


class _PDH_FMT_COUNTERVALUE(ctypes.Structure):
    _fields_ = [("CStatus", wintypes.DWORD), ("doubleValue", ctypes.c_double)]


class _PDH_FMT_COUNTERVALUE_ITEM(ctypes.Structure):
    _fields_ = [("szName", ctypes.c_void_p), ("FmtValue", _PDH_FMT_COUNTERVALUE)]


def _ft_val(v):
    return (int(v.dwHighDateTime) << 32) | int(v.dwLowDateTime)


def _read_system_times():
    idle, kernel, user = _FILETIME(), _FILETIME(), _FILETIME()
    ctypes.windll.kernel32.GetSystemTimes(ctypes.byref(idle), ctypes.byref(kernel), ctypes.byref(user))
    return _ft_val(idle), _ft_val(kernel), _ft_val(user)


def _gpu_nvidia():
    """[(name, util%)] per NVIDIA GPU via nvidia-smi, or []."""
    try:
        r = subprocess.run(["nvidia-smi", "--query-gpu=name,utilization.gpu", "--format=csv,noheader,nounits"],
                           capture_output=True, timeout=5, creationflags=NO_WINDOW)
        if r.returncode != 0:
            return []
        out = []
        for line in r.stdout.decode("utf-8", "replace").splitlines():
            n, _, v = line.rpartition(",")
            if re.fullmatch(r"\s*\d+(\.\d+)?\s*", v):
                out.append((n.strip(), float(v)))
        return out
    except Exception:
        return []


_GPU_NAMES = {}   # luid "0xHHHHHHHH_0xLLLLLLLL" (lower case) -> adapter description


def _load_gpu_names():
    """Adapter LUID -> name from HKLM\\SOFTWARE\\Microsoft\\DirectX (same LUIDs the GPU Engine counters use)."""
    _GPU_NAMES.clear()
    try:
        import winreg
        k = winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, r"SOFTWARE\Microsoft\DirectX")
        i = 0
        while True:
            try:
                sub = winreg.EnumKey(k, i); i += 1
            except OSError:
                break
            try:
                s = winreg.OpenKey(k, sub)
                desc = winreg.QueryValueEx(s, "Description")[0]
                luid = int(winreg.QueryValueEx(s, "AdapterLuid")[0])
                if "Basic Render" in desc:
                    continue
                _GPU_NAMES["0x%08x_0x%08x" % (luid >> 32, luid & 0xFFFFFFFF)] = desc
            except Exception:
                pass
    except Exception:
        pass


def _gpu_pdh():
    pdh = ctypes.windll.pdh
    PDH_FMT_DOUBLE = 0x00000200
    PDH_MORE_DATA = 0x800007D2
    query = ctypes.c_void_p()
    counter = ctypes.c_void_p()
    pdh.PdhOpenQueryW.argtypes = [ctypes.c_wchar_p, ctypes.c_void_p, ctypes.POINTER(ctypes.c_void_p)]
    pdh.PdhOpenQueryW.restype = ctypes.c_long
    pdh.PdhAddEnglishCounterW.argtypes = [ctypes.c_void_p, ctypes.c_wchar_p, ctypes.c_void_p, ctypes.POINTER(ctypes.c_void_p)]
    pdh.PdhAddEnglishCounterW.restype = ctypes.c_long
    pdh.PdhAddCounterW.argtypes = [ctypes.c_void_p, ctypes.c_wchar_p, ctypes.c_void_p, ctypes.POINTER(ctypes.c_void_p)]
    pdh.PdhAddCounterW.restype = ctypes.c_long
    pdh.PdhCollectQueryData.argtypes = [ctypes.c_void_p]
    pdh.PdhCollectQueryData.restype = ctypes.c_long
    pdh.PdhGetFormattedCounterArrayW.argtypes = [ctypes.c_void_p, ctypes.c_ulong, ctypes.POINTER(ctypes.c_ulong), ctypes.POINTER(ctypes.c_ulong), ctypes.c_void_p]
    pdh.PdhGetFormattedCounterArrayW.restype = ctypes.c_long
    pdh.PdhCloseQuery.argtypes = [ctypes.c_void_p]
    pdh.PdhCloseQuery.restype = ctypes.c_long
    if pdh.PdhOpenQueryW(None, 0, ctypes.byref(query)) != 0:
        return None
    try:
        path = "\\GPU Engine(*)\\Utilization Percentage"
        r = pdh.PdhAddEnglishCounterW(query, path, 0, ctypes.byref(counter))
        if r != 0:
            r = pdh.PdhAddCounterW(query, path, 0, ctypes.byref(counter))
        if r != 0:
            return None
        pdh.PdhCollectQueryData(query)
        import time as _t
        _t.sleep(0.5)
        pdh.PdhCollectQueryData(query)
        size = ctypes.c_ulong(0)
        count = ctypes.c_ulong(0)
        r = pdh.PdhGetFormattedCounterArrayW(counter, PDH_FMT_DOUBLE, ctypes.byref(size), ctypes.byref(count), None)
        if (r & 0xFFFFFFFF) != PDH_MORE_DATA:
            return {}
        buf = ctypes.create_string_buffer(size.value)
        r = pdh.PdhGetFormattedCounterArrayW(counter, PDH_FMT_DOUBLE, ctypes.byref(size), ctypes.byref(count), buf)
        if r != 0:
            return None
        # per adapter (LUID): sum the instances of each engine type, report the busiest engine type (like Task Manager)
        per = {}
        step = ctypes.sizeof(_PDH_FMT_COUNTERVALUE_ITEM)
        for i in range(count.value):
            item = _PDH_FMT_COUNTERVALUE_ITEM.from_buffer(buf, i * step)
            if item.FmtValue.CStatus != 0 or not item.szName:
                continue
            name = ctypes.wstring_at(item.szName).lower()
            m = re.search(r"(luid_0x[0-9a-f]+_0x[0-9a-f]+).*?engtype_(\w+)", name)
            if m:
                d = per.setdefault(m.group(1)[5:], {})
                d[m.group(2)] = d.get(m.group(2), 0.0) + item.FmtValue.doubleValue
        return {l: round(min(max(e.values()), 100.0), 1) for l, e in per.items()}
    finally:
        pdh.PdhCloseQuery(query)


def _gpus():
    """Every real GPU as [{name, util}]: the GPU Engine counters list the live adapters (LUIDs), DirectX's registry names them.
    Basic Render Driver and adapters without a name (virtual displays) are skipped."""
    _load_gpu_names()
    try:
        per = _gpu_pdh() or {}
    except Exception:
        per = {}
    out = [{"name": _GPU_NAMES[l], "util": v} for l, v in per.items() if l in _GPU_NAMES]
    if not out:
        out = [{"name": n, "util": v} for n, v in _gpu_nvidia()]
    return out


STATS = {"cpu": None, "mem": None, "gpu": None, "gpus": [], "ts": 0}
_stats_prev = {"busy": 0, "total": 0, "have": False}


def _sample_once():
    idle, kernel, user = _read_system_times()
    busy = (kernel + user) - idle
    total = kernel + user
    cpu = None
    if _stats_prev["have"] and total > _stats_prev["total"]:
        d_busy = busy - _stats_prev["busy"]
        d_total = total - _stats_prev["total"]
        if d_total > 0:
            cpu = round(min(max(d_busy * 100.0 / d_total, 0.0), 100.0), 1)
    _stats_prev.update(busy=busy, total=total, have=True)
    mem = None
    m = _MEMORYSTATUSEX()
    m.dwLength = ctypes.sizeof(m)
    if ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(m)):
        mem = round(float(m.dwMemoryLoad), 1)
    gpus = _gpus()
    STATS.update(cpu=cpu, mem=mem, gpu=(max(g["util"] for g in gpus) if gpus else None), gpus=gpus, ts=int(time.time() * 1000))


def _stats_loop():
    try:
        idle, kernel, user = _read_system_times()
        _stats_prev.update(busy=(kernel + user) - idle, total=kernel + user, have=True)
    except Exception:
        pass
    while True:
        try:
            _sample_once()
        except Exception:
            pass
        time.sleep(1)


def _lan_ips():
    """Private IPv4 addresses of this PC: the default-route adapter first, then the others (no Tailscale / CGNAT)."""
    import socket
    out = []
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); s.connect(("192.0.2.1", 9)); out.append(s.getsockname()[0]); s.close()
    except OSError:
        pass
    try:
        for ai in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            out.append(ai[4][0])
    except OSError:
        pass
    ok = lambda ip: re.match(r"^(10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.)", ip)
    seen = []
    for ip in out:
        if ip and ok(ip) and ip not in seen:
            seen.append(ip)
    return seen


async def lan_info(req: web.Request):
    """GET /lan -> {ips: [...], port} (Bearer required): the phone picks the address on its own Wi-Fi subnet."""
    if not _authed(req):
        return web.json_response({"error": "unauthorized"}, status=401)
    return web.json_response({"ips": _lan_ips(), "port": PORT})


async def pc_stats(req: web.Request):
    """GET /pc/stats -> {cpu, mem, gpu, ts} from the 1 s sampler cache (Bearer required)."""
    if not _authed(req):
        return web.json_response({"error": "unauthorized"}, status=401)
    return web.json_response(dict(STATS))



async def _watchdog(app):
    """After hibernate/resume the Tailscale socket can go dead while the process lives on.
    Probe our own /health every 60 s; after 3 straight failures exit, so the scheduled
    task (Hermes_PhoneRelay, every 5 min / on wake / at logon) starts a fresh instance."""
    async def loop():
        fails = 0
        await asyncio.sleep(60)
        while True:
            try:
                async with ClientSession() as s:
                    async with s.get(f"http://127.0.0.1:{PORT}/health", timeout=__import__("aiohttp").ClientTimeout(total=10)) as r:
                        fails = 0 if r.status == 200 else fails + 1
            except Exception:
                fails += 1
            if fails >= 3:
                log.warning("self health check failed 3x; exiting for restart")
                os._exit(3)
            await asyncio.sleep(60)
    app["watchdog"] = asyncio.create_task(loop())


# ---- dashboard passthrough (GET /dash/<allow-listed path>) ----------------------------------------------------------
# The Hermes dashboard (REST: skills, memory, toolsets, plugins, usage) binds 127.0.0.1 only. On loopback it has no login page but
# every /api call needs the X-Hermes-Session-Token header. The relay owns a dedicated instance (DASH_PORT) started with a fixed token
# kept in launchers/dashboard.token; the phone never sees that token, it only presents the relay API key.
DASH_PORT = int(os.environ.get("RELAY_DASH_PORT", "9120"))
DASH_TOKEN_FILE = HERMES_HOME / "launchers" / "dashboard.token"
DASH_READ_ONLY = {"status", "skills", "memory", "tools/toolsets", "dashboard/plugins", "analytics/usage",
                    "cron/jobs", "env", "mcp/servers", "logs"}
_DASH_LOCK = None


def _dash_token() -> str:
    try:
        t = DASH_TOKEN_FILE.read_text(encoding="utf-8").strip()
        if len(t) >= 32:
            return t
    except OSError:
        pass
    import secrets
    t = secrets.token_urlsafe(32)
    DASH_TOKEN_FILE.parent.mkdir(parents=True, exist_ok=True)
    DASH_TOKEN_FILE.write_text(t, encoding="utf-8")
    return t


async def _dash_up(tok: str) -> bool:
    try:
        async with _http().get(f"http://127.0.0.1:{DASH_PORT}/api/skills", headers={"X-Hermes-Session-Token": tok},
                               timeout=__import__("aiohttp").ClientTimeout(total=5)) as r:
            return r.status == 200
    except Exception:
        return False


async def _dash_ensure(tok: str) -> bool:
    """True once our dashboard answers with our token; starts it on first use (orphans from an earlier relay are reused)."""
    global _DASH_LOCK
    if _DASH_LOCK is None:
        _DASH_LOCK = asyncio.Lock()
    async with _DASH_LOCK:
        if await _dash_up(tok):
            return True
        env = dict(os.environ, HERMES_DASHBOARD_SESSION_TOKEN=tok)
        await asyncio.create_subprocess_exec(
            HERMES, "dashboard", "--host", "127.0.0.1", "--port", str(DASH_PORT), "--no-open", "--skip-build",
            env=env, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, creationflags=NO_WINDOW)
        for _ in range(45):
            await asyncio.sleep(1)
            if await _dash_up(tok):
                return True
        return False


# Response filters for the read-only paths that carry secret-adjacent data. Each is an ALLOW-list of keys, so env values,
# MCP env/args/url/command and cron prompts never reach the phone even if the dashboard adds fields later.
_DASH_LOG_FILES = {"agent", "errors", "gateway"}
_DASH_LOG_MAX_LINES = 200
_DASH_LOG_MAX_LINE = 1000
_DASH_LOG_MAX_BYTES = 64 * 1024
_DASH_SECRET_RE = re.compile(r"(sk-[A-Za-z0-9_\-]{8,}|gh[pousr]_[A-Za-z0-9]{12,}|Bearer\s+\S+|"
                             r"(?i:(api[_-]?key|token|secret|password|authorization)\w*)\s*[=:]\s*(?:Bearer\s+)?\S+)")


def _dash_filter_env(data):
    """Dashboard /api/env is {NAME: {is_set, redacted_value, ...}}. Keep NAME + is_set + labels; never values or previews."""
    if not isinstance(data, dict):
        raise ValueError("env: unexpected shape")
    keys = ("category", "description", "provider", "provider_label", "channel_managed", "custom", "advanced")
    out = []
    for name, row in sorted(data.items()):
        if not isinstance(row, dict):
            continue
        item = {"name": str(name), "is_set": bool(row.get("is_set"))}
        for k in keys:
            v = row.get(k)
            if isinstance(v, str):
                item[k] = v[:300]
            elif isinstance(v, bool) or v is None:
                item[k] = v
        out.append(item)
    return {"vars": out}


def _dash_filter_cron(data):
    """Dashboard /api/cron/jobs (list or {"jobs": [...]}). Keep scheduling/status fields; drop prompts, scripts, targets."""
    jobs = data.get("jobs", []) if isinstance(data, dict) else data
    if not isinstance(jobs, list):
        raise ValueError("cron: unexpected shape")
    keys = ("id", "name", "profile", "schedule_display", "enabled", "state", "next_run_at", "last_run_at", "last_status")
    out = []
    for job in jobs:
        if isinstance(job, dict):
            row = {}
            for k in keys:
                v = job.get(k)
                if isinstance(v, str):
                    row[k] = v[:300]
                elif isinstance(v, (int, float, bool)) or v is None:
                    row[k] = v
            out.append(row)
    return {"jobs": out}


def _dash_filter_mcp(data):
    """Dashboard /api/mcp/servers. Keep name/transport/enabled/source/tool names; drop url, command, args, env, headers."""
    servers = data.get("servers", []) if isinstance(data, dict) else data
    if not isinstance(servers, list):
        raise ValueError("mcp: unexpected shape")
    out = []
    for s in servers:
        if not isinstance(s, dict):
            continue
        tools = s.get("tools")
        out.append({
            "name": str(s.get("name") or "")[:200],
            "transport": str(s.get("transport") or "unknown")[:32],
            "enabled": bool(s.get("enabled")),
            "auth": s.get("auth") if isinstance(s.get("auth"), str) else None,
            "source": str(s.get("source") or "")[:32],
            "plugin": s.get("plugin") if isinstance(s.get("plugin"), str) else None,
            "tools": [str(t)[:120] for t in tools][:200] if isinstance(tools, list) else None,
        })
    return {"servers": out}


def _dash_filter_logs(data):
    """Dashboard /api/logs. Redact key-shaped substrings, cap line length, line count and total bytes."""
    lines = data.get("lines", []) if isinstance(data, dict) else []
    if not isinstance(lines, list):
        raise ValueError("logs: unexpected shape")
    kept = [_DASH_SECRET_RE.sub("[redacted]", str(line))[:_DASH_LOG_MAX_LINE] for line in lines[-_DASH_LOG_MAX_LINES:]]
    total = sum(len(s) for s in kept)
    dropped = 0
    while kept and total > _DASH_LOG_MAX_BYTES:
        total -= len(kept.pop(0))
        dropped += 1
    return {"file": str(data.get("file", "agent"))[:32] if isinstance(data, dict) else "agent",
            "lines": kept, "truncated": dropped > 0 or len(lines) > len(kept)}


_DASH_FILTERS = {"env": _dash_filter_env, "cron/jobs": _dash_filter_cron, "mcp/servers": _dash_filter_mcp, "logs": _dash_filter_logs}


async def dash_proxy(req: web.Request):
    """GET /dash/<path> -> dashboard /api/<path> for the read-only allow-list (Bearer required)."""
    if not _authed(req):
        return web.json_response({"error": "unauthorized"}, status=401)
    tail = req.match_info["tail"].strip("/")
    if req.method != "GET" or tail not in DASH_READ_ONLY:
        return web.json_response({"error": "not allowed"}, status=403)
    profile = req.query.get("profile")
    if profile is not None and not profile_ok(profile):
        return web.json_response({"error": "unknown profile"}, status=400)
    filt = _DASH_FILTERS.get(tail)
    params = {}
    if profile is not None:
        params["profile"] = profile
    if tail == "logs":
        # Only the keys the log view uses are forwarded; line count is clamped, file is allow-listed.
        if req.query.get("file", "agent") not in _DASH_LOG_FILES:
            return web.json_response({"error": "unknown log file"}, status=400)
        try:
            n = int(req.query.get("lines", "100"))
        except ValueError:
            return web.json_response({"error": "bad lines"}, status=400)
        params = {"file": req.query.get("file", "agent"), "lines": str(max(1, min(n, _DASH_LOG_MAX_LINES)))}
        if req.query.get("level"):
            params["level"] = req.query["level"][:16]
        if req.query.get("search"):
            params["search"] = req.query["search"][:200]
        if profile is not None:
            params["profile"] = profile
    tok = _dash_token()
    if not await _dash_ensure(tok):
        return web.json_response({"error": "dashboard unavailable"}, status=503)
    try:
        qs = ("?" + "&".join(f"{k}={quote(v, safe='')}" for k, v in params.items())) if params else ""
        async with _http().get(f"http://127.0.0.1:{DASH_PORT}/api/{tail}{qs}", headers={"X-Hermes-Session-Token": tok},
                               timeout=__import__("aiohttp").ClientTimeout(total=30)) as r:
            data = await r.read()
            status = r.status
            ctype = r.content_type or "application/json"
        if filt is None:
            return web.Response(status=status, body=data, content_type=ctype)
        if status != 200:
            return web.json_response({"error": f"dashboard status {status}"}, status=status if status in (404, 503) else 502)
        try:
            filtered = filt(json.loads(data.decode("utf-8") or "null"))
        except Exception as e:
            log.warning("dash %s filter failed: %s", tail, e)
            return web.json_response({"error": "unexpected dashboard response"}, status=502)
        return web.json_response(filtered)
    except Exception as e:
        log.warning("dash %s failed: %s", tail, e)
        return web.json_response({"error": f"dashboard unavailable: {e}"}, status=502)


# ---- dashboard writes (PUT /dash/...): only the skill and toolset enable switches ----------------------------------
# PUT /dash/skills/toggle {"name", "enabled"} and PUT /dash/tools/toolsets/<name> {"enabled"} are the ONLY writable routes.
# The profile travels as ?profile=, the body is size-limited and must carry exactly the expected keys.
_DASH_WRITE_MAX = 1024
_DASH_NAME_RE = r"[A-Za-z0-9_][A-Za-z0-9_.\-]{0,127}"
_DASH_TOOLSET_PATH = re.compile(r"tools/toolsets/(" + _DASH_NAME_RE + r")")


async def dash_write(req: web.Request):
    if not _authed(req):
        return web.json_response({"error": "unauthorized"}, status=401)
    tail = req.match_info["tail"].strip("/")
    if req.method != "PUT":
        return web.json_response({"error": "not allowed"}, status=403)
    if tail == "skills/toggle":
        keys = {"name", "enabled"}
    elif _DASH_TOOLSET_PATH.fullmatch(tail):
        keys = {"enabled"}
    else:
        return web.json_response({"error": "not allowed"}, status=403)
    profile = req.query.get("profile")
    if profile is not None and not profile_ok(profile):
        return web.json_response({"error": "unknown profile"}, status=400)
    if req.content_length is None or req.content_length > _DASH_WRITE_MAX:
        return web.json_response({"error": "body too large"}, status=413)
    try:
        body = json.loads((await req.read()).decode("utf-8") or "null")
    except Exception:
        body = None
    if not isinstance(body, dict) or set(body) != keys or not isinstance(body.get("enabled"), bool):
        return web.json_response({"error": "bad request"}, status=400)
    if tail == "skills/toggle":
        if not isinstance(body["name"], str) or re.fullmatch(_DASH_NAME_RE, body["name"]) is None:
            return web.json_response({"error": "bad request"}, status=400)
        payload = {"name": body["name"], "enabled": body["enabled"]}
    else:
        payload = {"enabled": body["enabled"]}
    tok = _dash_token()
    if not await _dash_ensure(tok):
        return web.json_response({"error": "dashboard unavailable"}, status=503)
    qs = ("?profile=" + quote(profile, safe="")) if profile is not None else ""
    try:
        async with _http().put(f"http://127.0.0.1:{DASH_PORT}/api/{tail}{qs}", json=payload,
                               headers={"X-Hermes-Session-Token": tok},
                               timeout=__import__("aiohttp").ClientTimeout(total=30)) as r:
            data = await r.read()
            status = r.status
            ctype = r.content_type or "application/json"
    except Exception as e:
        log.warning("dash write %s failed: %s", tail, e)
        return web.json_response({"error": f"dashboard unavailable: {e}"}, status=502)
    log.info("dash write %s -> %s", tail, status)
    return web.Response(status=status, body=data, content_type=ctype)


# ---- background-task progress (POST /bgtasks) ----------------------------------------------------------------------
# The gateway already lists a session's background processes (WS RPC `process.list`: id, command, cwd, status, exit_code,
# uptime_seconds, output_tail). The phone calls that on its own socket and posts the rows here; the relay only adds what
# needs the PC: parsing progress from the output tail and, for silent downloads (`curl -s -o f URL`), file size vs Content-Length.
_ANSI = re.compile(r"\x1b\[[0-9;?]*[A-Za-z]")
_CURL_HDR = re.compile(r"%\s+Total\s+%\s+Received")
_CURL_ROW = re.compile(r"^\s*(\d{1,3})\s+\S+\s+\d{1,3}\s+\S+\s+\d{1,3}\s+\S+")   # " 45 4672M  45 2100M  0 0 ..."
_PCT = re.compile(r"(?<![\w.])(\d{1,3}(?:\.\d+)?)\s?%")
_COUNT = re.compile(r"(?<![\w/.:\-])(\d{1,9})/(\d{1,9})(?![\w/.:\-])")
_SIZE_TTL = 60.0
_TOTALS = {}          # url -> (monotonic ts, content-length|None)


def _lines(tail: str) -> list:
    return [l for l in re.split(r"[\r\n]+", _ANSI.sub("", tail or "")) if l.strip()]


def parse_progress(tail: str):
    """(percent 0-100 | None, detail | None) from a process output tail. Last meaningful signal wins."""
    ls = _lines(tail)
    for i in range(len(ls) - 1, -1, -1):
        l = ls[i]
        m = _CURL_ROW.match(l)
        if m and any(_CURL_HDR.search(h) for h in ls[max(0, i - 3):i]) and int(m.group(1)) <= 100:   # curl meter: first column = %
            return float(m.group(1)), l.strip()[:120]
        pcts = [float(x) for x in _PCT.findall(l) if float(x) <= 100]
        if pcts:                                                       # tqdm / wget / pip / curl -# / generic
            return pcts[-1], l.strip()[:120]
        c = _COUNT.findall(l)
        if c:
            a, b = int(c[-1][0]), int(c[-1][1])
            if b >= 2 and a <= b:
                return round(100.0 * a / b, 1), f"{a}/{b}"
    return None, None


def _out_file_url(command: str, cwd):
    """(output path, url) for curl -o/--output FILE or wget -O/-P style commands, else (None, None)."""
    import shlex
    try:
        t = shlex.split(command or "", posix=True)
    except ValueError:
        return None, None
    url = next((x for x in t if re.match(r"https?://", x)), None)
    out = None
    for i, x in enumerate(t[:-1]):
        base = os.path.basename(t[0]).lower()
        if x == "--output" or x == "-O" and "wget" in base or re.fullmatch(r"-[A-Za-z]*o", x) and "curl" in base \
                or x == "--output-document":
            out = t[i + 1]
        elif x.startswith("--output="):
            out = x.split("=", 1)[1]
    if not url or not out:
        return None, None
    path = Path(out)
    if not path.is_absolute() and cwd:
        path = Path(cwd) / path
    return path, url


def _content_length(url: str):
    now = time.monotonic()
    hit = _TOTALS.get(url)
    if hit and (hit[1] is not None or now - hit[0] < _SIZE_TTL):
        return hit[1]
    import urllib.request
    n = None
    try:
        with urllib.request.urlopen(urllib.request.Request(url, method="HEAD"), timeout=6) as r:   # follows redirects
            n = int(r.headers.get("Content-Length") or 0) or None
    except Exception:
        n = None
    _TOTALS[url] = (now, n)
    if len(_TOTALS) > 64:
        _TOTALS.pop(next(iter(_TOTALS)), None)
    return n


def _fmt_bytes(n: float) -> str:
    for u in ("B", "KB", "MB", "GB"):
        if n < 1024 or u == "GB":
            return f"{n:.0f} {u}" if u == "B" else f"{n:.1f} {u}"
        n /= 1024


def bgtask_progress(row: dict) -> dict:
    """Enrich one process.list row (blocking: stat + maybe one HEAD)."""
    tail = str(row.get("output_tail") or row.get("output_preview") or "")
    ls = _lines(tail)
    pct, detail = parse_progress(tail)
    if pct is None and row.get("status") == "running":
        path, url = _out_file_url(str(row.get("command") or ""), row.get("cwd"))
        try:
            size = path.stat().st_size if path else None
        except OSError:
            size = None
        if size:
            total = _content_length(url)
            if total and size <= total:
                pct, detail = round(100.0 * size / total, 1), f"{_fmt_bytes(size)} / {_fmt_bytes(total)}"
            else:
                detail = _fmt_bytes(size)
    if row.get("status") != "running" and row.get("exit_code") == 0 and pct is not None:
        pct = 100.0
    return {"id": row.get("session_id"), "percent": pct, "detail": detail, "last": ls[-1].strip()[:200] if ls else "",
            "tail": ls[-30:]}


async def bgtasks(req: web.Request):
    """POST /bgtasks {processes:[process.list rows]} -> {tasks:[{id, percent|null, detail|null, last, tail:[<=30 lines]}]}. Read-only."""
    if not _authed(req):
        return web.json_response({"error": "unauthorized"}, status=401)
    try:
        rows = [r for r in (await req.json()).get("processes", [])[:20] if isinstance(r, dict)]
    except Exception as e:
        return web.json_response({"error": str(e)}, status=400)
    return web.json_response({"tasks": [await _io(bgtask_progress, r) for r in rows]})


async def health(_req):
    return web.json_response({"status": "ok", "relay": "hermes-phone-relay"})


async def ws_handler(req: web.Request):
    auth = req.headers.get("Authorization", "")
    if not (auth.startswith("Bearer ") and hmac.compare_digest(auth[7:].strip(), KEY)):
        return web.json_response({"error": "unauthorized"}, status=401)
    profile = req.query.get("profile", "default")
    if not profile_ok(profile):
        return web.json_response({"error": "unknown profile"}, status=404)
    global _WS_COUNT
    if _WS_COUNT >= MAX_WS:
        return web.json_response({"error": "too many connections"}, status=503, headers={"Retry-After": "5"})
    _WS_COUNT += 1
    try:
        try:
            a = await attach(profile)
        except Exception as e:
            log.warning("attach failed for %s: %s", profile, e)
            return web.json_response({"error": f"gateway unavailable: {e}"}, status=503, headers={"Retry-After": "3"})

        phone = web.WebSocketResponse(heartbeat=25, max_msg_size=0)
        await phone.prepare(req)
        log.info("phone connected profile=%s from %s (open=%d)", profile, req.remote, _WS_COUNT)
        try:
            async with ClientSession() as s:
                try:
                    up = await s.ws_connect(a["url"], protocols=a["protocols"], max_msg_size=0, heartbeat=25)
                except Exception as e:
                    log.warning("upstream connect failed: %s", e)
                    await phone.close(code=1011, message=b"upstream connect failed")
                    return phone

                async def pump(src, dst, name):
                    try:
                        async for m in src:
                            if m.type == WSMsgType.TEXT:
                                await asyncio.wait_for(dst.send_str(m.data), SEND_TIMEOUT)
                            elif m.type == WSMsgType.BINARY:
                                await asyncio.wait_for(dst.send_bytes(m.data), SEND_TIMEOUT)
                            elif m.type in (WSMsgType.ERROR, WSMsgType.CLOSE, WSMsgType.CLOSING, WSMsgType.CLOSED):
                                break
                    except asyncio.CancelledError:
                        raise
                    except Exception as e:  # one dead peer must never take the process or other sockets down
                        log.info("%s pump ended: %r", name, e)
                    log.info("%s closed", name)

                tasks = [asyncio.create_task(pump(phone, up, "phone")), asyncio.create_task(pump(up, phone, "gateway"))]
                try:
                    await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
                finally:
                    for t in tasks:
                        t.cancel()
                    await asyncio.gather(*tasks, return_exceptions=True)
                    await asyncio.shield(up.close())
        finally:
            await phone.close()
            log.info("phone disconnected profile=%s", profile)
        return phone
    finally:
        _WS_COUNT -= 1


# ---- connected clients (for the tray): who talked to the relay recently, and over which path ----
_CLIENTS = {}   # (ip, ua) -> {"first": ts, "last": ts, "n": count}


def _conn_kind(ip: str) -> str:
    import ipaddress
    try:
        a = ipaddress.ip_address(ip.split("%")[0])
    except ValueError:
        return "unknown"
    if a.is_loopback:
        return "local"
    if a.version == 4:
        if a in ipaddress.ip_network("100.64.0.0/10"):
            return "tailscale"
        if a in ipaddress.ip_network("192.168.137.0/24"):
            return "hotspot"
        if a.is_private:
            return "lan"
    elif a in ipaddress.ip_network("fd7a:115c:a1e0::/48"):
        return "tailscale"
    return "other"


@web.middleware
async def _track_clients(req, handler):
    try:
        p = req.path
        if p != "/health" and p != "/_clients":
            ip = req.remote or "?"
            ua = (req.headers.get("User-Agent") or "")[:60]
            if _conn_kind(ip) != "local":
                k = (ip, ua)
                now = time.time()
                e = _CLIENTS.get(k)
                if e is None:
                    if len(_CLIENTS) > 200:
                        _CLIENTS.clear()
                    e = _CLIENTS[k] = {"first": now, "last": now, "n": 0}
                e["last"] = now
                e["n"] += 1
    except Exception:
        pass
    return await handler(req)


async def clients_get(req):
    """GET /_clients (loopback only): clients seen in the last 3 minutes."""
    if _conn_kind(req.remote or "") != "local":
        return web.json_response({"error": "forbidden"}, status=403)
    now = time.time()
    out = []
    for (ip, ua), e in list(_CLIENTS.items()):
        age = now - e["last"]
        if age <= 180:
            out.append({"ip": ip, "via": _conn_kind(ip), "ua": ua, "age": int(age), "n": e["n"]})
    out.sort(key=lambda x: x["age"])
    return web.json_response({"clients": out})


def main():
    global _IO_POOL
    from concurrent.futures import ThreadPoolExecutor
    _IO_POOL = ThreadPoolExecutor(max_workers=IO_THREADS, thread_name_prefix="relay-io")
    threading.Thread(target=_stats_loop, daemon=True).start()
    app = web.Application(middlewares=[_track_clients])
    app.router.add_get("/health", health)
    app.router.add_get("/_clients", clients_get)
    app.router.add_get("/ws", ws_handler)
    app.router.add_route("*", "/api/{tail:.*}", http_proxy)
    app.router.add_get("/app/{app}/{what}", app_files)
    app.router.add_get("/fs/download", fs_download)
    app.router.add_post("/fs/upload", fs_upload)
    app.router.add_post("/fs/{op}", fs_op)
    app.router.add_post("/vdisplay/shot", vdisplay_shot)
    app.router.add_post("/vdisplay/{op}", vdisplay_op)
    app.router.add_post("/pc/keys", pc_keys)
    app.router.add_get("/elevate/pending", elevate_pending)
    app.router.add_post("/tabs", tabs_report)
    app.router.add_get("/tabs", tabs_list)
    app.router.add_get("/stats", stats_get)
    app.router.add_post("/stats", stats_put)
    app.router.add_post("/elevate/{op}/{id}", elevate_decide)
    app.router.add_get("/outbox", outbox_list)
    app.router.add_post("/outbox/send", outbox_send)
    app.router.add_route("*", "/outbox/{name}", outbox_item)
    app.router.add_post("/upload/init", upload_init)
    app.router.add_post("/upload/{id}/done", upload_done)
    app.router.add_route("*", "/upload/{id}", upload_chunk)
    app.router.add_route("*", "/config/context", config_context)
    app.router.add_route("*", "/config/models", config_models)
    app.router.add_route("*", "/config/delegation", config_delegation)
    app.router.add_route("*", "/config/delegation/advanced", config_delegation_advanced)
    app.router.add_get("/pc/stats", pc_stats)
    app.router.add_get("/lan", lan_info)
    app.router.add_post("/bgtasks", bgtasks)
    app.router.add_get("/dash/{tail:.*}", dash_proxy)
    app.router.add_put("/dash/{tail:.*}", dash_write)
    app.on_startup.append(_watchdog)
    import time
    while True:  # the Tailscale IP can be unbindable for a while (boot, resume from sleep): retry
        try:
            log.info("relay listening on %s:%s", HOST, PORT)
            web.run_app(app, host=HOST, port=PORT, print=None)
            return
        except OSError as e:
            log.warning("bind %s:%s failed (%s); retrying in 30s", HOST, PORT, e)
            time.sleep(30)
            app = web.Application(middlewares=[_track_clients])
            app.router.add_get("/health", health)
            app.router.add_get("/_clients", clients_get)
            app.router.add_get("/ws", ws_handler)
            app.router.add_route("*", "/api/{tail:.*}", http_proxy)
            app.router.add_get("/app/{app}/{what}", app_files)
            app.router.add_get("/fs/download", fs_download)
            app.router.add_post("/fs/upload", fs_upload)
            app.router.add_post("/fs/{op}", fs_op)
            app.router.add_post("/vdisplay/shot", vdisplay_shot)
            app.router.add_post("/vdisplay/{op}", vdisplay_op)
            app.router.add_post("/pc/keys", pc_keys)
            app.router.add_get("/elevate/pending", elevate_pending)
            app.router.add_post("/tabs", tabs_report)
            app.router.add_get("/tabs", tabs_list)
            app.router.add_get("/stats", stats_get)
            app.router.add_post("/stats", stats_put)
            app.router.add_post("/elevate/{op}/{id}", elevate_decide)
            app.router.add_get("/outbox", outbox_list)
            app.router.add_post("/outbox/send", outbox_send)
            app.router.add_route("*", "/outbox/{name}", outbox_item)
            app.router.add_post("/upload/init", upload_init)
            app.router.add_post("/upload/{id}/done", upload_done)
            app.router.add_route("*", "/upload/{id}", upload_chunk)
            app.router.add_route("*", "/config/context", config_context)
            app.router.add_route("*", "/config/models", config_models)
            app.router.add_get("/pc/stats", pc_stats)
            app.router.add_get("/lan", lan_info)
            app.router.add_post("/bgtasks", bgtasks)
            app.router.add_get("/dash/{tail:.*}", dash_proxy)
            app.router.add_put("/dash/{tail:.*}", dash_write)
            app.on_startup.append(_watchdog)


if __name__ == "__main__":
    main()
