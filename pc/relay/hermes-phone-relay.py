"""Phone relay: exposes the Hermes desktop WebSocket protocol (hermes-gateway-v1) on Tailscale.

The gateway's /api/ws listens on loopback only and needs a single-use ticket that only a local
process can mint (`hermes gateway attach`). This relay:
  * listens on RELAY_HOST:RELAY_PORT (all addresses, :8643; Bearer API key required),
  * authenticates the phone with the same API_SERVER_KEY as the api_server (Bearer header),
  * mints a fresh ticket for the requested profile and pipes frames both ways, unmodified.
Run with the Hermes venv python. Never logs keys or tickets.
"""
import asyncio, hmac, json, logging, os, re, subprocess, sys
from pathlib import Path
from aiohttp import web, ClientSession, WSMsgType

HERMES_HOME = Path(os.environ.get("LOCALAPPDATA", "")) / "hermes"
HOST = os.environ.get("RELAY_HOST", "0.0.0.0")  # all addresses: Tailscale + LAN (API key required)
PORT = int(os.environ.get("RELAY_PORT", "8643"))
HERMES = str(HERMES_HOME / "bin" / "hermes.exe")
LOG = HERMES_HOME / "launchers" / "phone-relay.log"
logging.basicConfig(filename=LOG, level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("relay")


def api_key() -> str:
    for line in (HERMES_HOME / ".env").read_text(encoding="utf-8").splitlines():
        if line.startswith("API_SERVER_KEY="):
            return line.split("=", 1)[1].strip()
    raise SystemExit("API_SERVER_KEY missing from default .env")


KEY = api_key()
NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)  # no console window for hermes.exe children


def profile_ok(p: str) -> bool:
    return p == "default" or (re.fullmatch(r"[a-z0-9][a-z0-9_-]{0,63}", p or "") is not None
                              and (HERMES_HOME / "profiles" / p).is_dir())


async def attach(profile: str) -> dict:
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
    async with ClientSession() as s:
        async with s.request(req.method, origin + req.path_qs, headers=headers, data=body or None,
                             timeout=__import__("aiohttp").ClientTimeout(total=120)) as r:
            data = await r.read()
            return web.Response(status=r.status, body=data,
                                content_type=(r.content_type or "application/json"))


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
        raw = str((await req.json()).get("path", "")).strip()
    except Exception:
        return web.json_response({"error": "bad request"}, status=400)
    p = Path(os.path.expandvars(os.path.expanduser(raw)))
    if not raw or not p.is_absolute():
        return web.json_response({"error": "path must be absolute"}, status=400)
    op = req.match_info["op"]
    if op == "stat":
        return web.json_response({"exists": p.exists(), "dir": p.is_dir()})
    if op == "mkdir":
        try:
            p.mkdir(parents=True, exist_ok=True)
        except OSError as e:
            return web.json_response({"error": f"mkdir failed: {e.strerror or e}"}, status=400)
        log.info("phone created folder %s", p)
        return web.json_response({"exists": True, "dir": True})
    return web.json_response({"error": "not found"}, status=404)


VDISPLAY = HERMES_HOME / "launchers" / "hermes-vdisplay.py"   # open-source virtual display CLI (pc/vdisplay)
# on/off = agent screen; screen-off/screen-on = main screen dark + user windows on the user screen (independent)
VD_OPS = {"status": ["status"], "on": ["on"], "off": ["off"], "screen-off": ["screen", "off"],
          "screen-on": ["screen", "on"], "extend": ["mode", "extend"], "second-only": ["mode", "second-only"]}


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


ELEVATE = HERMES_HOME / "launchers" / "hermes-elevate.py"   # phone-approved elevation (pc/elevate)


async def _elevate_cli(*args):
    py = Path(sys.executable).with_name("python.exe")
    proc = await asyncio.create_subprocess_exec(str(py if py.exists() else sys.executable), "-I", str(ELEVATE), *args,
                                                stdout=subprocess.PIPE, stderr=subprocess.PIPE, creationflags=NO_WINDOW)
    out, _ = await asyncio.wait_for(proc.communicate(), 30)
    return json.loads(out.decode("utf-8", "replace") or "null")


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
SAFE_NAME = re.compile(r"[A-Za-z0-9_][A-Za-z0-9._() \-]{0,200}")


def _authed(req):
    auth = req.headers.get("Authorization", "")
    return auth.startswith("Bearer ") and hmac.compare_digest(auth[7:].strip(), KEY)


def _outbox_files():
    OUTBOX.mkdir(parents=True, exist_ok=True)
    files = [f for f in OUTBOX.iterdir() if f.is_file() and SAFE_NAME.fullmatch(f.name) and not f.name.endswith(".session")]
    files.sort(key=lambda f: f.stat().st_mtime, reverse=True)
    out = []
    for f in files:
        side = f.with_name(f.name + ".session")
        sess = re.sub(r"[^A-Za-z0-9_\-]", "", side.read_text(encoding="utf-8").strip()) if side.is_file() else ""
        out.append({"name": f.name, "size": f.stat().st_size, "time": int(f.stat().st_mtime), "session": sess})
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
        files = _outbox_files()
        if any(f"{x['name']}|{x['time']}" not in known for x in files) or loop.time() >= deadline:
            break
        await asyncio.sleep(1)
    return web.json_response({"files": files})


async def outbox_item(req: web.Request):
    """GET /outbox/<name> downloads a file; DELETE removes it once the phone has saved it."""
    if not _authed(req):
        return web.json_response({"error": "unauthorized"}, status=401)
    name = req.match_info["name"]
    if not SAFE_NAME.fullmatch(name):
        return web.json_response({"error": "bad name"}, status=400)
    f = OUTBOX / name
    if not f.is_file() or name.endswith(".session"):
        return web.json_response({"error": "not found"}, status=404)
    if req.method == "DELETE":
        f.unlink()
        f.with_name(name + ".session").unlink(missing_ok=True)
        return web.json_response({"ok": True})
    return web.FileResponse(f, headers={"Content-Disposition": f'attachment; filename="{name}"', "Cache-Control": "no-store"})


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


async def health(_req):
    return web.json_response({"status": "ok", "relay": "hermes-phone-relay"})


async def ws_handler(req: web.Request):
    auth = req.headers.get("Authorization", "")
    if not (auth.startswith("Bearer ") and hmac.compare_digest(auth[7:].strip(), KEY)):
        return web.json_response({"error": "unauthorized"}, status=401)
    profile = req.query.get("profile", "default")
    if not profile_ok(profile):
        return web.json_response({"error": "unknown profile"}, status=404)
    try:
        a = await attach(profile)
    except Exception as e:
        log.warning("attach failed for %s: %s", profile, e)
        return web.json_response({"error": f"gateway unavailable: {e}"}, status=503)

    phone = web.WebSocketResponse(heartbeat=25, max_msg_size=0)
    await phone.prepare(req)
    log.info("phone connected profile=%s from %s", profile, req.remote)
    async with ClientSession() as s:
        try:
            up = await s.ws_connect(a["url"], protocols=a["protocols"], max_msg_size=0, heartbeat=25)
        except Exception as e:
            log.warning("upstream connect failed: %s", e)
            await phone.close(code=1011, message=b"upstream connect failed")
            return phone

        async def pump(src, dst, name):
            async for m in src:
                if m.type == WSMsgType.TEXT:
                    await dst.send_str(m.data)
                elif m.type == WSMsgType.BINARY:
                    await dst.send_bytes(m.data)
                elif m.type in (WSMsgType.ERROR, WSMsgType.CLOSE):
                    break
            log.info("%s closed", name)

        tasks = [asyncio.create_task(pump(phone, up, "phone")), asyncio.create_task(pump(up, phone, "gateway"))]
        await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
        for t in tasks:
            t.cancel()
        await up.close()
    await phone.close()
    log.info("phone disconnected profile=%s", profile)
    return phone


def main():
    app = web.Application()
    app.router.add_get("/health", health)
    app.router.add_get("/ws", ws_handler)
    app.router.add_route("*", "/api/{tail:.*}", http_proxy)
    app.router.add_get("/app/{app}/{what}", app_files)
    app.router.add_post("/fs/{op}", fs_op)
    app.router.add_post("/vdisplay/{op}", vdisplay_op)
    app.router.add_get("/elevate/pending", elevate_pending)
    app.router.add_post("/elevate/{op}/{id}", elevate_decide)
    app.router.add_get("/outbox", outbox_list)
    app.router.add_route("*", "/outbox/{name}", outbox_item)
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
            app = web.Application()
            app.router.add_get("/health", health)
            app.router.add_get("/ws", ws_handler)
            app.router.add_route("*", "/api/{tail:.*}", http_proxy)
            app.router.add_get("/app/{app}/{what}", app_files)
            app.router.add_post("/fs/{op}", fs_op)
            app.router.add_post("/vdisplay/{op}", vdisplay_op)
            app.router.add_get("/elevate/pending", elevate_pending)
            app.router.add_post("/elevate/{op}/{id}", elevate_decide)
            app.router.add_get("/outbox", outbox_list)
            app.router.add_route("*", "/outbox/{name}", outbox_item)
            app.on_startup.append(_watchdog)


if __name__ == "__main__":
    main()
