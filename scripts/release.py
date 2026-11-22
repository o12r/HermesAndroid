"""Bump version, build the APK, publish it to the PC relay and (optionally) GitHub Releases.

    python scripts/release.py --notes "What changed"            # PC relay only
    python scripts/release.py --notes "..." --github            # also gh release
    python scripts/release.py --hotfix                          # only publish hotfix.json (no build)

The PC relay serves %LOCALAPPDATA%/hermes/launchers/app-updates/hermes-android/{latest.json,app.apk,hotfix.json}.
GitHub: tag v<name>, asset HermesAndroid-<name>-<code>.apk (the app reads the code from the asset name);
hotfix.json is read from the repo's main branch, so commit + push it for the GitHub fallback.
"""
import argparse, json, os, re, shutil, subprocess, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
APP_ID = "hermes-android"
PC_DIR = Path(os.environ["LOCALAPPDATA"]) / "hermes" / "launchers" / "app-updates" / APP_ID
APK = ROOT / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"


def read_version():
    p = dict(l.split("=", 1) for l in (ROOT / "version.properties").read_text().split() if "=" in l)
    return int(p["VERSION_CODE"]), p["VERSION_NAME"]


def write_version(code, name):
    (ROOT / "version.properties").write_text(f"VERSION_CODE={code}\nVERSION_NAME={name}\n")


def publish_hotfix():
    src = ROOT / "hotfix.json"
    json.loads(src.read_text(encoding="utf-8"))  # validate
    PC_DIR.mkdir(parents=True, exist_ok=True)
    shutil.copy2(src, PC_DIR / "hotfix.json")
    print("hotfix.json -> PC relay (commit + push it for the GitHub fallback)")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--notes", default="")
    ap.add_argument("--github", action="store_true")
    ap.add_argument("--hotfix", action="store_true", help="only publish hotfix.json")
    ap.add_argument("--no-bump", action="store_true")
    a = ap.parse_args()
    if a.hotfix:
        return publish_hotfix()

    code, name = read_version()
    if not a.no_bump:
        major, minor, patch = (int(x) for x in re.findall(r"\d+", name)[:3])
        code, name = code + 1, f"{major}.{minor}.{patch + 1}"
        write_version(code, name)
    print(f"building {name} ({code})")
    gradlew = str(ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew"))
    env = dict(os.environ)
    if not Path(env.get("JAVA_HOME", "")).joinpath("bin").is_dir():  # e.g. an MSYS-style /c/... path
        env["JAVA_HOME"] = "C:/Java/jdk21"
    r = subprocess.run([gradlew, "assembleDebug", "--console=plain"], cwd=ROOT, timeout=1200, env=env)
    if r.returncode:
        sys.exit("build failed")

    PC_DIR.mkdir(parents=True, exist_ok=True)
    shutil.copy2(APK, PC_DIR / "app.apk")
    (PC_DIR / "latest.json").write_text(json.dumps(
        {"version_code": code, "version_name": name, "notes": a.notes}, ensure_ascii=False, indent=1), encoding="utf-8")
    if (ROOT / "hotfix.json").is_file():
        publish_hotfix()
    print(f"published {name} ({code}) to the PC relay")

    if a.github:
        asset = ROOT / "app" / "build" / f"HermesAndroid-{name}-{code}.apk"
        shutil.copy2(APK, asset)
        pc = pc_zip(name)
        subprocess.run(["gh", "release", "create", f"v{name}", str(asset), str(pc), "--title", f"v{name}",
                        "--notes", a.notes or f"Version {name} ({code})"], cwd=ROOT, check=True, timeout=600)
        print("published GitHub release", f"v{name}")


def pc_zip(name):
    """PC install bundle: run install.cmd (relay, tray, skill, virtual-screen CLI + driver installer)."""
    import zipfile
    out = ROOT / "app" / "build" / f"HermesAndroid-PC-{name}.zip"
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        for f in sorted((ROOT / "pc").rglob("*")):
            if f.is_file() and "__pycache__" not in f.parts and f.suffix != ".pyc":
                z.write(f, Path("HermesAndroid-PC") / f.relative_to(ROOT / "pc"))
    return out


if __name__ == "__main__":
    main()
