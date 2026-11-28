import sys

p = "C:/Users/oscar/Projects/hermes-client/HermesAndroid/core/src/main/java/dev/oscar/hermes/AppShell.kt"
with open(p, "rb") as f:
    txt = f.read().decode("utf-8")
crlf = "\r\n" in txt
txt = txt.replace("\r\n", "\n")
old = '        if (vm.c.screen == Screen.Settings || vm.c.activePage?.page == "pc") vm.c.leavePage()\n'
new = '        if (vm.c.screen == Screen.Settings || vm.c.screen == Screen.Feature) vm.c.leavePage()\n'
if txt.count(old) != 1:
    print("FAIL", txt.count(old))
    sys.exit(1)
txt = txt.replace(old, new, 1)
if crlf:
    txt = txt.replace("\n", "\r\n")
with open(p, "wb") as f:
    f.write(txt.encode("utf-8"))
print("ok")
