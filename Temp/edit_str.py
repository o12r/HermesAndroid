import sys

p = "C:/Users/oscar/Projects/hermes-client/HermesAndroid/shared/src/main/kotlin/dev/oscar/hermes/Str.kt"
with open(p, "rb") as f:
    txt = f.read().decode("utf-8")
crlf = "\r\n" in txt
txt = txt.replace("\r\n", "\n")
old = "object Str {\n    const val attachments_disabled"
if txt.count(old) != 1:
    print("FAIL", txt.count(old))
    sys.exit(1)
txt = txt.replace(old, "object Str {\n    const val admin = \"admin\"\n    const val attachments_disabled", 1)
if crlf:
    txt = txt.replace("\n", "\r\n")
with open(p, "wb") as f:
    f.write(txt.encode("utf-8"))
print("ok")
