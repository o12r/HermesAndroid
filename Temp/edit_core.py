import io, sys

ROOT = "C:/Users/oscar/Projects/hermes-client/HermesAndroid/"


def read(p):
    with open(ROOT + p, "rb") as f:
        b = f.read()
    txt = b.decode("utf-8")
    crlf = b.count(b"\r\n") > 0
    return txt.replace("\r\n", "\n"), crlf


def write(p, txt, crlf):
    data = (txt.replace("\r\n", "\n").replace("\n", "\r\n") if crlf else txt.replace("\r\n", "\n"))
    with open(ROOT + p, "wb") as f:
        f.write(data.encode("utf-8"))


def edit(p, old, new, count=1):
    txt, crlf = read(p)
    n = txt.count(old)
    if n != count:
        print("FAIL", p, "matches", n, "expected", count)
        sys.exit(1)
    txt = txt.replace(old, new, count)
    write(p, txt, crlf)
    print("ok", p)


# ---- shared/Store.kt : feature switch persistence hooks ----
edit("shared/src/main/kotlin/dev/oscar/hermes/Store.kt",
     "    var hotfixRelay: String?\n",
     "    /** Runtime switch of an optional feature module (Settings \u2192 modules); on unless the user turned it off. */\n"
     "    fun featureEnabled(id: String): Boolean = true\n"
     "    fun setFeatureEnabled(id: String, on: Boolean) {}\n"
     "    var hotfixRelay: String?\n")

# ---- SecureStore.kt : SharedPreferences backing ----
edit("core/src/main/java/dev/oscar/hermes/SecureStore.kt",
     "    /** Relay URL pushed by the hotfix config (overrides the default, not a user-entered value). */\n",
     "    /** Optional feature modules the user left on (default) or off, so a switch needs no restart. */\n"
     "    override fun featureEnabled(id: String): Boolean = prefs.getBoolean(\"feature.\" + id, true)\n"
     "    override fun setFeatureEnabled(id: String, on: Boolean) { prefs.edit().putBoolean(\"feature.\" + id, on).apply() }\n"
     "    /** Relay URL pushed by the hotfix config (overrides the default, not a user-entered value). */\n")

# ---- ChatController.kt : observable on/off set ----
edit("shared/src/main/kotlin/dev/oscar/hermes/ChatController.kt",
     "    val features: List<ChatFeature> = java.util.ServiceLoader.load(ChatFeature::class.java, ChatFeature::class.java.classLoader).toList()\n",
     "    val features: List<ChatFeature> = java.util.ServiceLoader.load(ChatFeature::class.java, ChatFeature::class.java.classLoader).toList()\n"
     "    /** Feature ids the user switched off (Settings \u2192 modules); observable, so their entry points vanish at once. */\n"
     "    val offFeatures = mutableStateListOf<String>().also { l -> features.forEach { if (!store.featureEnabled(it.id)) l.add(it.id) } }\n"
     "    /** Features left switched on: pages, entry points, badges and background work of the others stay off. */\n"
     "    val enabledFeatures: List<ChatFeature> get() = features.filter { it.id !in offFeatures }\n"
     "    fun featureOn(id: String): Boolean = id !in offFeatures\n"
     "    /** Turn a feature on/off at runtime (persisted); switching one off also closes its open page. */\n"
     "    fun setFeatureOn(id: String, on: Boolean) {\n"
     "        if (on) offFeatures.remove(id) else if (id !in offFeatures) offFeatures.add(id)\n"
     "        store.setFeatureEnabled(id, on)\n"
     "        if (!on) activePage?.page?.let { p -> if (features.any { it.id == id && it.pageTitleKey(p) != null }) leavePage() }\n"
     "    }\n")

edit("shared/src/main/kotlin/dev/oscar/hermes/ChatController.kt",
     "        if (kind == \"sessions\") conn.refreshSessions() else features.forEach { it.pageEntered(kind) }\n",
     "        if (kind == \"sessions\") conn.refreshSessions() else enabledFeatures.forEach { it.pageEntered(kind) }\n")

edit("shared/src/main/kotlin/dev/oscar/hermes/ChatController.kt",
     "it.title = s(features.firstNotNullOfOrNull { f -> f.pageTitleKey(kind) }",
     "it.title = s(enabledFeatures.firstNotNullOfOrNull { f -> f.pageTitleKey(kind) }")

# ---- UiFeature.kt : filter by switch + top-bar hook ----
edit("core/src/main/java/dev/oscar/hermes/UiFeature.kt",
     "    fun pageIcon(kind: String): ImageVector? = null\n",
     "    fun pageIcon(kind: String): ImageVector? = null\n"
     "    /** Extra top-bar entry point of this feature, composed next to the PC icon; nothing by default. */\n"
     "    @Composable fun TopBarAction(vm: ChatViewModel) {}\n")

edit("core/src/main/java/dev/oscar/hermes/UiFeature.kt",
     "val ChatViewModel.uiFeatures: List<UiFeature> get() = c.features.filterIsInstance<UiFeature>()\n",
     "/** UI half of the feature modules the user left switched on (Settings \u2192 modules). */\n"
     "val ChatViewModel.uiFeatures: List<UiFeature> get() = c.enabledFeatures.filterIsInstance<UiFeature>()\n")

# ---- ChatTabs.kt : icon row + badge honour the switches ----
edit("core/src/main/java/dev/oscar/hermes/ChatTabs.kt",
     "        if (vm.uiFeatures.any { it.hasPage(\"pc\") }) IconButton(onClick = { vm.c.openPage(\"pc\") }",
     "        vm.uiFeatures.forEach { it.TopBarAction(vm) }\n"
     "        if (vm.uiFeatures.any { it.hasPage(\"pc\") }) IconButton(onClick = { vm.c.openPage(\"pc\") }")

edit("core/src/main/java/dev/oscar/hermes/ChatTabs.kt",
     "vm.c.features.firstNotNullOfOrNull { f -> f.tabBadge(cid) }",
     "vm.c.enabledFeatures.firstNotNullOfOrNull { f -> f.tabBadge(cid) }")

# ---- SettingsScreen.kt : Modules group + section + admin slot ----
edit("core/src/main/java/dev/oscar/hermes/SettingsScreen.kt",
     "\"model\" to R.string.default_models, \"screens\" to R.string.vdisplay, \"upd\" to R.string.updates, \"about\" to R.string.about)",
     "\"model\" to R.string.default_models, \"screens\" to R.string.vdisplay, \"modules\" to R.string.modules,\n"
     "        \"upd\" to R.string.updates, \"about\" to R.string.about)")

edit("core/src/main/java/dev/oscar/hermes/SettingsScreen.kt",
     "                if (vm.uiFeatures.any { it.hasPage(\"pc\") }) Group(\"screens\", Icons.Default.Monitor, stringResource(R.string.screens_manage))\n",
     "                if (vm.uiFeatures.any { it.hasPage(\"pc\") }) Group(\"screens\", Icons.Default.Monitor, stringResource(R.string.screens_manage))\n"
     "                vm.uiFeatures.forEach { it.Slot(vm, \"settings.groups\") }\n"
     "                Group(\"modules\", Icons.Default.Extension, stringResource(R.string.modules_summary))\n")

edit("core/src/main/java/dev/oscar/hermes/SettingsScreen.kt",
     "            \"chat\" -> ContextSettingsGroup(vm)\n",
     "            \"modules\" -> FeatureModules(vm)\n"
     "            \"chat\" -> ContextSettingsGroup(vm)\n")

print("done")
