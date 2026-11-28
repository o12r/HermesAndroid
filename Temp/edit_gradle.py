import sys

ROOT = "C:/Users/oscar/Projects/hermes-client/HermesAndroid/"


def edit(p, old, new):
    with open(ROOT + p, "rb") as f:
        raw = f.read()
    txt = raw.decode("utf-8")
    crlf = "\r\n" in txt
    txt = txt.replace("\r\n", "\n")
    if txt.count(old) != 1:
        print("FAIL", p, txt.count(old))
        sys.exit(1)
    txt = txt.replace(old, new, 1)
    if crlf:
        txt = txt.replace("\n", "\r\n")
    with open(ROOT + p, "wb") as f:
        f.write(txt.encode("utf-8"))
    print("ok", p)


edit("settings.gradle.kts",
     'listOf("vdisplay", "pcfiles", "pcstats").forEach { include(":feature-$it") }',
     'listOf("vdisplay", "pcfiles", "pcstats", "admin").forEach { include(":feature-$it") }')

edit("app/build.gradle.kts",
     'val all = listOf("vdisplay", "pcfiles", "pcstats")',
     'val all = listOf("vdisplay", "pcfiles", "pcstats", "admin")')
