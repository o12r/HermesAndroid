"""Send a file from this PC to the Hermes Android app's Inbox.

    python -I send-to-phone.py <file> [--name "nice name.pdf"] [--session <id>]

--session defaults to $HERMES_SESSION_ID (set for the agent's own session), so the file shows up in the
chat it was sent from. Without a session the phone shows it in whichever chat is open.

The file is copied into %LOCALAPPDATA%\hermes\launchers\phone-outbox, which the phone relay serves.
The phone lists it under the Inbox button; "Save" puts it in the phone's Downloads folder and clears it from the PC.
Prints the name the file was stored under (use it in the reply to the user).
"""
import argparse, os, re, shutil, sys
from pathlib import Path

OUTBOX = Path(os.environ.get("LOCALAPPDATA", str(Path.home() / "AppData" / "Local"))) / "hermes" / "launchers" / "phone-outbox"
UNSAFE = re.compile(r"[^A-Za-z0-9._() \-]")
MAX_BYTES = 500 * 1024 * 1024


def main():
    ap = argparse.ArgumentParser(description="Send a file to the phone's Inbox.")
    ap.add_argument("file")
    ap.add_argument("--name", help="name on the phone (default: the file's name)")
    ap.add_argument("--session", default=os.environ.get("HERMES_SESSION_ID", ""), help="chat session id (default: $HERMES_SESSION_ID)")
    a = ap.parse_args()
    src = Path(a.file).expanduser()
    if not src.is_file():
        sys.exit(f"not a file: {src}")
    if src.stat().st_size > MAX_BYTES:
        sys.exit("file is over 500 MB")
    base = UNSAFE.sub("_", (a.name or src.name).strip())[:150].strip(" .") or "file"
    if base.endswith(".session"):
        base += "_"
    OUTBOX.mkdir(parents=True, exist_ok=True)
    stem, suf = os.path.splitext(base)
    dst, n = OUTBOX / base, 2
    while dst.exists():
        dst = OUTBOX / f"{stem} ({n}){suf}"
        n += 1
    shutil.copyfile(src, dst)
    sess = re.sub(r"[^A-Za-z0-9_\-]", "", a.session or "")
    if sess:
        dst.with_name(dst.name + ".session").write_text(sess, encoding="utf-8")
    print(dst.name)


if __name__ == "__main__":
    main()
