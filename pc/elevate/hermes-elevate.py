"""Phone-approved elevation for the Hermes agent (no UAC prompt on the PC).

The agent asks to run a program elevated; the request shows up on the phone (relay /elevate/*); on approval an
elevated scheduled task (HermesElevate, created once by install-elevate.ps1) starts it. Only programs on the
whitelist may ever be elevated. The whitelist lives in %ProgramData%\\HermesElevate\\allow.json, which only admins
can write, so a normal process cannot widen it. Never whitelist shells/interpreters (powershell, cmd, python...).

  request [--wait] -- <exe> [args...]   ask for elevation (JSON: id, status, publisher, sha256)
  pending                                requests waiting for approval
  approve <id> | deny <id>               used by the phone relay
  status <id>                            pending|approved|denied|running|done|failed
  allow add <exe> | allow remove <exe> | allow list     whitelist (add/remove need admin: one UAC prompt)
  _runner                                (internal, elevated task) starts approved requests
"""
import ctypes, hashlib, json, os, subprocess, sys, time, uuid
from pathlib import Path

HOME = Path(os.environ.get("LOCALAPPDATA", "")) / "hermes"
STATE = HOME / "elevate"
REQ = STATE / "req"
ALLOW = Path(os.environ.get("ProgramData", r"C:\ProgramData")) / "HermesElevate" / "allow.json"
TASK = "HermesElevate"
NOWIN = 0x08000000
TTL = 600          # an unanswered request expires after 10 minutes


def out(o):
    print(json.dumps(o, ensure_ascii=False, indent=1)); return o


def whitelist():
    try:
        return {os.path.normcase(os.path.abspath(p)) for p in json.loads(ALLOW.read_text(encoding="utf-8"))}
    except Exception:
        return set()


def resolve(exe):
    import shutil
    p = shutil.which(exe) or exe
    return os.path.normcase(os.path.abspath(p))


def sha256(p):
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()


def publisher(p):
    try:
        r = subprocess.run(["powershell", "-NoProfile", "-Command",
                            f"$s=Get-AuthenticodeSignature -LiteralPath '{p}';"
                            "if($s.SignerCertificate){$s.SignerCertificate.Subject}"],
                           capture_output=True, text=True, timeout=20, creationflags=NOWIN)
        return r.stdout.strip() or "(unsigned)"
    except Exception:
        return "(unknown)"


def load(i):
    return json.loads((REQ / f"{i}.json").read_text(encoding="utf-8"))


def save(r):
    REQ.mkdir(parents=True, exist_ok=True)
    (REQ / f"{r['id']}.json").write_text(json.dumps(r), encoding="utf-8")


def request(argv, wait=False):
    if not argv:
        return out({"ok": False, "error": "no program given"})
    exe = resolve(argv[0])
    if not os.path.isfile(exe):
        return out({"ok": False, "error": f"not found: {argv[0]}"})
    if exe not in whitelist():
        return out({"ok": False, "error": "program is not whitelisted for elevation", "exe": exe,
                    "hint": "on the PC: hermes-elevate.py allow add <exe> (one UAC prompt)"})
    r = {"id": uuid.uuid4().hex[:8], "exe": exe, "args": argv[1:], "sha256": sha256(exe),
         "publisher": publisher(exe), "status": "pending", "t": time.time()}
    save(r)
    if wait:
        end = time.time() + TTL
        while time.time() < end:
            time.sleep(1)
            r = load(r["id"])
            if r["status"] not in ("pending", "approved", "running"):
                break
        else:
            r["status"] = "expired"; save(r)
    return out({"ok": r["status"] in ("pending", "done", "running", "approved"), **r})


def pending():
    res = []
    for f in sorted(REQ.glob("*.json")) if REQ.exists() else []:
        try:
            r = json.loads(f.read_text(encoding="utf-8"))
        except Exception:
            continue
        if r["status"] == "pending" and time.time() - r["t"] > TTL:
            r["status"] = "expired"; save(r)
        if r["status"] == "pending":
            res.append(r)
    return res


def decide(i, ok):
    r = load(i)
    if r["status"] != "pending":
        return out({"ok": False, "error": f"request is {r['status']}"})
    r["status"] = "approved" if ok else "denied"
    save(r)
    if ok:
        subprocess.run(["schtasks.exe", "/run", "/tn", TASK], capture_output=True, creationflags=NOWIN, timeout=20)
    return out({"ok": True, "id": i, "status": r["status"]})


def runner():
    wl = whitelist()
    for f in sorted(REQ.glob("*.json")):
        try:
            r = json.loads(f.read_text(encoding="utf-8"))
        except Exception:
            continue
        if r["status"] != "approved":
            continue
        try:
            if r["exe"] not in wl or sha256(r["exe"]) != r["sha256"]:
                raise RuntimeError("not whitelisted or the file changed since it was requested")
            p = subprocess.Popen([r["exe"], *r["args"]], close_fds=True, creationflags=0x00000200)
            r["status"], r["pid"] = "running", p.pid
            save(r)
            threading_wait(r, p)
        except Exception as e:
            r["status"], r["error"] = "failed", str(e)
            save(r)


def threading_wait(r, p):
    """Detach: record 'done' only if the program exits quickly (< 3 s); long-running programs stay 'running'."""
    try:
        p.wait(3)
        r["status"], r["rc"] = "done", p.returncode
        save(r)
    except subprocess.TimeoutExpired:
        pass


def is_admin():
    return bool(ctypes.windll.shell32.IsUserAnAdmin())


def allow(op, exe=None):
    if op == "list":
        return out(sorted(whitelist()))
    if not is_admin():   # re-run elevated (single UAC prompt)
        args = " ".join(f'"{a}"' for a in [str(Path(__file__).resolve()), "allow", op, exe or ""])
        ctypes.windll.shell32.ShellExecuteW(None, "runas", sys.executable, args, None, 0)
        return out({"ok": True, "note": "elevation prompt shown on the PC"})
    ALLOW.parent.mkdir(parents=True, exist_ok=True)
    cur = whitelist()
    p = resolve(exe)
    cur.add(p) if op == "add" else cur.discard(p)
    ALLOW.write_text(json.dumps(sorted(cur)), encoding="utf-8")
    subprocess.run(["icacls", str(ALLOW.parent), "/inheritance:r", "/grant:r", "Administrators:(OI)(CI)F",
                    "SYSTEM:(OI)(CI)F", "Users:(OI)(CI)RX"], capture_output=True, creationflags=NOWIN)
    return out({"ok": True, "allowed": sorted(cur)})


def main(a):
    c = a[0] if a else ""
    if c == "request":
        wait = "--wait" in a[1:]
        rest = a[1:]
        if "--" in rest:
            rest = rest[rest.index("--") + 1:]
        else:
            rest = [x for x in rest if x != "--wait"]
        request(rest, wait)
    elif c == "pending":
        out(pending())
    elif c in ("approve", "deny") and len(a) > 1:
        decide(a[1], c == "approve")
    elif c == "status" and len(a) > 1:
        out(load(a[1]))
    elif c == "allow" and len(a) > 1:
        allow(a[1], a[2] if len(a) > 2 else None)
    elif c == "_runner":
        runner()
    else:
        print(__doc__)


if __name__ == "__main__":
    main(sys.argv[1:])
