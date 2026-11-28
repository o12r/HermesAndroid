import sys

ROOT = "C:/Users/oscar/Projects/hermes-client/HermesAndroid/"

EN = [
    ("admin", "Admin"),
    ("admin_summary", "Skills, memory, tools, plugins, usage"),
    ("admin_skills", "Skills"),
    ("admin_memory", "Memory"),
    ("admin_tools", "Tools"),
    ("admin_plugins", "Plugins"),
    ("admin_usage", "Usage"),
    ("admin_loading", "Loading…"),
    ("admin_empty", "Nothing to show"),
    ("admin_starting", "The dashboard is still starting on the PC — retry in a moment."),
    ("admin_error", "Could not load: %1$s"),
    ("admin_retry", "Retry"),
    ("admin_enabled", "on"),
    ("admin_disabled", "off"),
    ("admin_locked", "locked"),
    ("admin_available", "available"),
    ("admin_unavailable", "unavailable"),
    ("admin_configured", "configured"),
    ("admin_category_other", "Uncategorized"),
    ("admin_tools_n", "%1$d tools"),
    ("admin_providers", "Providers"),
    ("admin_active_provider", "Active: %1$s"),
    ("admin_none_active", "No active memory provider"),
    ("admin_builtin_files", "Built-in files"),
    ("admin_chars", "%1$d chars"),
    ("admin_provider_status", "Status: %1$s"),
    ("admin_totals", "Totals"),
    ("admin_in", "Input"),
    ("admin_out", "Output"),
    ("admin_cache", "Cache read"),
    ("admin_cost", "Est. cost"),
    ("admin_sessions_count", "Sessions"),
    ("admin_api_calls", "API calls"),
    ("admin_period", "Last %1$d days"),
    ("admin_daily", "Daily tokens"),
    ("admin_by_model", "By model"),
    ("admin_in_out", "In %1$s · out %2$s"),
    ("admin_usage_meta", "%1$s sessions · %2$s calls"),
    ("admin_plugin_tab", "Tab %1$s"),
    ("admin_version", "version %1$s"),
    ("admin_used_n", "used %1$d×"),
    ("modules", "Modules"),
    ("modules_summary", "Optional features: turn on or off"),
    ("modules_help", "A switched-off module hides its buttons and pages and stops its background work. Only modules built into this app are listed."),
    ("modules_none", "No optional modules in this build."),
    ("module_vdisplay", "PC screens"),
    ("module_pcfiles", "PC files"),
    ("module_pcstats", "PC resources"),
    ("module_admin", "Admin dashboard"),
    ("module_vdisplay_desc", "Virtual screen page and elevation prompts"),
    ("module_pcfiles_desc", "File browser and project folder picker"),
    ("module_pcstats_desc", "CPU, memory and GPU graphs on the PC page"),
    ("module_admin_desc", "Read-only skills, memory, tools, plugins and usage"),
]

ZH = {
    "admin": "管理",
    "admin_summary": "技能、记忆、工具、插件与用量",
    "admin_skills": "技能",
    "admin_memory": "记忆",
    "admin_tools": "工具",
    "admin_plugins": "插件",
    "admin_usage": "用量",
    "admin_loading": "加载中…",
    "admin_empty": "暂无内容",
    "admin_starting": "仪表盘正在 PC 上启动，请稍后重试。",
    "admin_error": "加载失败：%1$s",
    "admin_retry": "重试",
    "admin_enabled": "开",
    "admin_disabled": "关",
    "admin_locked": "已锁定",
    "admin_available": "可用",
    "admin_unavailable": "不可用",
    "admin_configured": "已配置",
    "admin_category_other": "未分类",
    "admin_tools_n": "%1$d 个工具",
    "admin_providers": "提供方",
    "admin_active_provider": "当前：%1$s",
    "admin_none_active": "未启用记忆提供方",
    "admin_builtin_files": "内置文件",
    "admin_chars": "%1$d 字符",
    "admin_provider_status": "状态：%1$s",
    "admin_totals": "合计",
    "admin_in": "输入",
    "admin_out": "输出",
    "admin_cache": "缓存读取",
    "admin_cost": "预估费用",
    "admin_sessions_count": "会话数",
    "admin_api_calls": "API 调用",
    "admin_period": "最近 %1$d 天",
    "admin_daily": "每日 token",
    "admin_by_model": "按模型",
    "admin_in_out": "输入 %1$s · 输出 %2$s",
    "admin_usage_meta": "%1$s 次会话 · %2$s 次调用",
    "admin_plugin_tab": "标签页 %1$s",
    "admin_version": "版本 %1$s",
    "admin_used_n": "使用 %1$d 次",
    "modules": "模块",
    "modules_summary": "可选功能开关",
    "modules_help": "关闭的模块会隐藏其按钮与页面，并停止其后台任务。仅列出本版本包含的模块。",
    "modules_none": "本版本没有可选模块。",
    "module_vdisplay": "PC 屏幕",
    "module_pcfiles": "PC 文件",
    "module_pcstats": "PC 资源",
    "module_admin": "管理面板",
    "module_vdisplay_desc": "虚拟屏幕页面与提权确认",
    "module_pcfiles_desc": "文件浏览与项目文件夹选择",
    "module_pcstats_desc": "PC 页面上的 CPU、内存、GPU 图表",
    "module_admin_desc": "只读的技能、记忆、工具、插件与用量",
}

missing = [k for k, _ in EN if k not in ZH]
if missing:
    print("missing zh:", missing)
    sys.exit(1)


def insert(path, pairs):
    with open(ROOT + path, "rb") as f:
        txt = f.read().decode("utf-8")
    if "</resources>" not in txt:
        print("no close tag", path)
        sys.exit(1)
    block = "".join('    <string name="%s">%s</string>\n' % (k, v.replace("&", "&amp;").replace("<", "&lt;")) for k, v in pairs)
    if "\r\n" in txt:
        block = block.replace("\n", "\r\n")
    txt = txt.replace("</resources>", block + "</resources>", 1)
    with open(ROOT + path, "wb") as f:
        f.write(txt.encode("utf-8"))
    print("ok", path, len(pairs))


insert("core/src/main/res/values/strings.xml", EN)
insert("core/src/main/res/values-zh-rCN/strings.xml", [(k, ZH[k]) for k, _ in EN])
