package dev.oscar.hermes

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Settings; the API key is AES-GCM encrypted with a non-exportable AndroidKeyStore key. */
class SecureStore(ctx: Context) : Store {
    private val prefs = ctx.getSharedPreferences("hermes", Context.MODE_PRIVATE)
    override fun historyTurns(): Int = prefs.getInt("historyTurns", 5).coerceIn(3, 30)
    override fun setHistoryTurns(v: Int) { prefs.edit().putInt("historyTurns", v.coerceIn(3, 30)).apply() }
    override fun keyCombos(): List<String> = (prefs.getString("keyCombos", "") ?: "").split("\n").filter { it.isNotBlank() }
    override fun setKeyCombos(v: List<String>) { prefs.edit().putString("keyCombos", v.joinToString("\n")).apply() }

    override var relayUrl: String
        get() = prefs.getString("relayUrl", null) ?: hotfixRelay ?: DEFAULT_RELAY
        set(v) = prefs.edit().putString("relayUrl", v.trim().trimEnd('/')).apply()
    /** "system" | "light" | "dark" | "black" */
    override var clock: String
        get() = prefs.getString("clock", "system")!!
        set(v) = prefs.edit().putString("clock", v).apply()
    override var allowLan: Boolean
        get() = prefs.getBoolean("allow_lan_http", false)
        set(v) { prefs.edit().putBoolean("allow_lan_http", v).apply(); ApiClient.allowLanHttp = v }
    override var theme: String
        get() = prefs.getString("theme", "system")!!
        set(v) = prefs.edit().putString("theme", v).apply()
    /** "balanced" | "saver": background power use (ping interval, wake lock, polling). */
    override var powerMode: String
        get() = prefs.getString("powerMode", "balanced")!!
        set(v) = prefs.edit().putString("powerMode", v).apply()
    /** "system" | "en" | "zh" */
    /** UI text scale, 0.8..1.5 (on top of the system font size). */
    override var fontScale: Float
        get() = prefs.getFloat("fontScale", 1f)
        set(v) = prefs.edit().putFloat("fontScale", v.coerceIn(0.5f, 1.5f)).apply()
    /** Last profile list scanned from the PC: "name\tdescription" lines. */
    override var profileCache: List<Pair<String, String>>
        get() = prefs.getString("profileCache", "")!!.lines().filter { it.isNotBlank() }
            .map { it.substringBefore('\t') to it.substringAfter('\t', "") }
        set(v) = prefs.edit().putString("profileCache", v.joinToString("\n") { it.first + "\t" + it.second.replace('\n', ' ') }).apply()
    /** Last session list + project tree per profile (JSON), so the session page opens instantly. */
    override fun snap(profile: String): String? = prefs.getString("snap_$profile", null)
    override fun ctxCache(profile: String): String? = prefs.getString("ctx_$profile", null)
    override fun setCtxCache(profile: String, json: String) = prefs.edit().putString("ctx_$profile", json).apply()
    override fun setSnap(profile: String, json: String) = prefs.edit().putString("snap_$profile", json).apply()
    /** MEDIA:<path> lines already handed to the relay (key = session|path), so reloading history does not resend them. */
    override fun mediaDone(key: String): Boolean = prefs.getStringSet("media_done", emptySet())!!.contains(key)
    override fun markMedia(key: String) { val s = prefs.getStringSet("media_done", emptySet())!!.toMutableList(); s.add(key)
        prefs.edit().putStringSet("media_done", s.takeLast(500).toSet()).apply() }
    override fun unmarkMedia(key: String) { prefs.edit().putStringSet("media_done", prefs.getStringSet("media_done", emptySet())!! - key).apply() }
    /** Per-reply token/speed stats, kept per session (key = hash of the reply text) so history keeps showing them. */
    override fun replyStat(sid: String, text: String): List<Float?>? = runCatching {
        val a = org.json.JSONObject(prefs.getString("mstat_$sid", "{}")!!).optJSONArray(text.hashCode().toString()) ?: return null
        List(4) { if (a.isNull(it)) null else a.getDouble(it).toFloat() }
    }.getOrNull()
    override fun setReplyStat(sid: String, text: String, v: List<Float?>) {
        val o = runCatching { org.json.JSONObject(prefs.getString("mstat_$sid", "{}")!!) }.getOrDefault(org.json.JSONObject())
        while (o.length() >= 300) o.remove(o.keys().next())
        o.put(text.hashCode().toString(), org.json.JSONArray(v.map { it ?: org.json.JSONObject.NULL }))
        prefs.edit().putString("mstat_$sid", o.toString()).apply()
    }
    /** Every reply stat this device holds for [sid], as the relay's JSON ({key: [4 numbers|null]}). */
    override fun replyStatsJson(sid: String): org.json.JSONObject = runCatching { org.json.JSONObject(prefs.getString("mstat_$sid", "{}")!!) }.getOrDefault(org.json.JSONObject())
    /** Adds stats other devices recorded (keeps this device's own on a clash). */
    override fun mergeReplyStats(sid: String, other: org.json.JSONObject) {
        val o = replyStatsJson(sid); var changed = false
        other.keys().forEach { k -> if (!o.has(k)) { o.put(k, other.get(k)); changed = true } }
        while (o.length() > 300) o.remove(o.keys().next())
        if (changed) prefs.edit().putString("mstat_$sid", o.toString()).apply()
    }
    /** User-chosen profile order (names); profiles not listed follow alphabetically. */
    override var profileOrder: List<String>
        get() = prefs.getString("profileOrder", "")!!.lines().filter { it.isNotBlank() }
        set(v) = prefs.edit().putString("profileOrder", v.joinToString("\n")).apply()
    override var lang: String
        get() = prefs.getString("lang", "system")!!
        set(v) = prefs.edit().putString("lang", v).commit().let { }
    override var lastUpdateCheck: Long
        get() = prefs.getLong("lastUpdateCheck", 0)
        set(v) = prefs.edit().putLong("lastUpdateCheck", v).apply()
    /** Update source: "auto" (PC first, fall back to GitHub) | "github" | "pc". */
    override var updateSource: String
        get() = prefs.getString("updateSource", "auto")!!.takeIf { it in UPDATE_SOURCES } ?: "auto"
        set(v) = prefs.edit().putString("updateSource", if (v in UPDATE_SOURCES) v else "auto").apply()
    /** Optional feature modules the user left on (default) or off, so a switch needs no restart. */
    override fun featureEnabled(id: String): Boolean = prefs.getBoolean("feature." + id, true)
    override fun setFeatureEnabled(id: String, on: Boolean) { prefs.edit().putBoolean("feature." + id, on).apply() }
    /** Pinned top-bar entries, newline-separated in pin order. */
    override fun pinnedTop(): List<String> = prefs.getString("pinnedTop", "")!!.lines().filter { it.isNotBlank() }
    override fun setPinnedTop(ids: List<String>) { prefs.edit().putString("pinnedTop", ids.distinct().joinToString("\n")).apply() }
    /** Pinned sessions as "profile<TAB>session id" lines, in pin order. */
    override fun pinnedSessionKeys(): List<String> = prefs.getString("pinnedSess", "")!!.lines().filter { it.isNotBlank() }
    override fun setPinnedSessionKeys(keys: List<String>) { prefs.edit().putString("pinnedSess", keys.distinct().joinToString("\n")).apply() }
    /** Relay URL pushed by the hotfix config (overrides the default, not a user-entered value). */
    override var hotfixRelay: String?
        get() = prefs.getString("hotfixRelay", null)
        set(v) = prefs.edit().putString("hotfixRelay", v).apply()
    /** Optional LAN address of the PC relay, tried when the main (Tailscale) URL doesn't answer. */
    override var lanUrl: String
        get() = prefs.getString("lanUrl", "")!!
        set(v) { val t = v.trim().trimEnd('/'); prefs.edit().putString("lanUrl", t).apply(); if (t.isNotBlank() && t !in servers) servers = servers + t }
    override var servers: List<String>
        get() = prefs.getString("servers", null)?.split("\n")?.filter { it.isNotBlank() }
            ?: listOf(relayUrl, prefs.getString("lanUrl", "")!!).filter { it.isNotBlank() }.distinct()
        set(v) {
            val l = v.map { it.trim().trimEnd('/') }.filter { it.isNotBlank() }.distinct()
            prefs.edit().putString("servers", l.joinToString("\n")).putString("relayUrl", l.firstOrNull() ?: DEFAULT_RELAY).putString("lanUrl", l.getOrNull(1) ?: "").apply()
        }
    /** The relay URL that answered last (not persisted). */
    @Volatile override var activeRelay: String? = null
    override fun relayBase(): String = activeRelay ?: relayUrl
    override var profile: String
        get() = prefs.getString("profile", "default")!!
        set(v) = prefs.edit().putString("profile", v).apply()

    /** "provider|model" or "" = server default; stored per profile. */
    override fun modelFor(profile: String): String = prefs.getString("model.$profile", "")!!
    override fun setModelFor(profile: String, v: String) = prefs.edit().putString("model.$profile", v).apply()
    /** Files saved from the Inbox into a session: (name, content Uri). Kept so they show again when the chat reloads. */
    override fun sessionFiles(id: String): List<Triple<String, String, Long>> = runCatching {
        val a = org.json.JSONArray(prefs.getString("files.$id", "[]")!!)
        (0 until a.length()).map { a.getJSONObject(it).let { o -> Triple(o.getString("name"), o.getString("uri"), o.optLong("ts", 0L)) } }
    }.getOrDefault(emptyList())
    /** Add or replace (by name) a file in a session. An empty [uri] means the file is still on the PC. */
    override fun upsertSessionFile(id: String, name: String, uri: String, at: Long) {
        val a = runCatching { org.json.JSONArray(prefs.getString("files.$id", "[]")!!) }.getOrDefault(org.json.JSONArray())
        val out = org.json.JSONArray()
        var ts = if (at > 0) at else System.currentTimeMillis()   // when the file arrived; kept when the entry is replaced so the card stays where it was
        for (i in 0 until a.length()) a.getJSONObject(i).let { if (it.getString("name") != name) out.put(it) else if (it.optLong("ts", 0L) > 0) ts = it.getLong("ts") }
        out.put(org.json.JSONObject().put("name", name).put("uri", uri).put("ts", ts))
        prefs.edit().putString("files.$id", out.toString()).apply()
    }
    /** Outbox entries already handled on this phone, so a file is shown once. */
    override fun inboxSeen(key: String): Boolean = prefs.getStringSet("inboxSeen", emptySet())!!.contains(key)
    override fun markInboxSeen(key: String) {
        val seen = prefs.getStringSet("inboxSeen", emptySet())!!.toMutableSet()
        seen.add(key)
        while (seen.size > 300) seen.remove(seen.first())
        prefs.edit().putStringSet("inboxSeen", seen).apply()
    }
    override fun sessionModel(id: String): String? = prefs.getString("smodel.$id", null)
    override fun setSessionModel(id: String, v: String) = prefs.edit().putString("smodel.$id", v).apply()
    override fun sessionEffort(id: String): String? = prefs.getString("seffort.$id", null)
    override fun setSessionEffort(id: String, v: String) = prefs.edit().putString("seffort.$id", v).apply()
    /** A blank per-profile effort means "not chosen": such a profile follows the global default effort. */
    override fun effortFor(profile: String): String = prefs.getString("effort.$profile", "").takeIf { !it.isNullOrBlank() } ?: defaultEffort()
    override fun defaultEffort(): String = prefs.getString("effortDefault", "")!!.takeIf { it in EFFORTS } ?: ""
    override fun setDefaultEffort(v: String) = prefs.edit().putString("effortDefault", v).apply()
    override fun setEffortFor(profile: String, v: String) = prefs.edit().putString("effort.$profile", v).apply()

    override var apiKey: String?
        get() = prefs.getString("apiKeyEnc", null)?.let { runCatching { decrypt(it) }.getOrNull() }
        set(v) {
            if (v.isNullOrBlank()) prefs.edit().remove("apiKeyEnc").apply()
            else prefs.edit().putString("apiKeyEnc", encrypt(v.trim())).apply()
        }
    override val hasKey get() = prefs.contains("apiKeyEnc")
    /** Reply notifications with an inline Reply box (default on); off = the plain "replied" alert. */
    override fun replyNotifyOn(): Boolean = prefs.getBoolean("replyNotify", true)
    override fun setReplyNotifyOn(on: Boolean) { prefs.edit().putBoolean("replyNotify", on).apply() }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val ct = c.doFinal(plain.toByteArray())
        return Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
    }

    private fun decrypt(s: String): String {
        val (iv, ct) = s.split(":").map { Base64.decode(it, Base64.NO_WRAP) }
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
        return String(c.doFinal(ct))
    }

    companion object {
        const val ALIAS = "hermes_api_key"
        const val DEFAULT_RELAY = "http://100.92.121.115:8643"
        val EFFORTS = Store.EFFORTS
        val UPDATE_SOURCES = listOf("auto", "github", "pc")
        /** Fallback until the PC's profile list has been fetched once. */
        val PROFILES = listOf("default")
    }
}
