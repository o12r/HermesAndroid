import json, os, re, sys, urllib.request

env = {}
with open("C:/Users/oscar/AppData/Local/hermes/.env", encoding="utf-8") as f:
    for line in f:
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        env[k.strip()] = v.strip().strip('"').strip("'")

key = env.get("API_SERVER_KEY") or env.get("HERMES_API_KEY") or ""
base = "http://127.0.0.1:8643"

def get(path):
    req = urllib.request.Request(base + path, headers={"Authorization": "Bearer " + key})
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            return r.status, r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")

def shape(v, depth=0):
    if isinstance(v, dict):
        if depth >= 3:
            return "{...}"
        return {k: shape(x, depth + 1) for k, x in list(v.items())[:12]}
    if isinstance(v, list):
        return [shape(v[0], depth + 1), "len=%d" % len(v)] if v else []
    if isinstance(v, str):
        return "str:" + v[:40]
    return v

out = {}
for p in ["/dash/skills", "/dash/memory", "/dash/tools/toolsets", "/dash/dashboard/plugins", "/dash/analytics/usage", "/dash/status"]:
    st, body = get(p)
    entry = {"status": st, "len": len(body)}
    try:
        j = json.loads(body)
        entry["shape"] = shape(j)
    except Exception as e:
        entry["raw"] = body[:300]
    out[p] = entry

# analytics usage specifics
st, body = get("/dash/analytics/usage")
try:
    u = json.loads(body)
    out["usage_keys"] = list(u.keys())
    out["totals"] = u.get("totals")
    d = u.get("daily")
    out["daily_type"] = type(d).__name__
    out["daily_sample"] = d[:3] if isinstance(d, list) else (list(d.items())[:3] if isinstance(d, dict) else d)
    m = u.get("by_model")
    out["by_model_sample"] = m[:3] if isinstance(m, list) else (list(m.items())[:3] if isinstance(m, dict) else m)
    t = u.get("by_task")
    out["by_task_sample"] = t[:3] if isinstance(t, list) else (list(t.items())[:3] if isinstance(t, dict) else t)
    out["period_days"] = u.get("period_days")
    out["skills_tools"] = (u.get("skills"), u.get("tools"))
except Exception as e:
    out["usage_err"] = repr(e)

st, body = get("/dash/memory")
try:
    m = json.loads(body)
    out["memory_shape"] = {"active": m.get("active"), "providers": shape(m.get("providers")), "builtin_files": shape(m.get("builtin_files"))}
except Exception as e:
    out["memory_err"] = repr(e)

with open("C:/Users/oscar/Projects/hermes-client/HermesAndroid/Temp/dash_shapes.json", "w", encoding="utf-8") as f:
    f.write(json.dumps(out, indent=1, ensure_ascii=False))
print("written", len(json.dumps(out)))
