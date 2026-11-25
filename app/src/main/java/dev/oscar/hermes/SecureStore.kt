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
class SecureStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("hermes", Context.MODE_PRIVATE)

    var relayUrl: String
        get() = prefs.getString("relayUrl", null) ?: hotfixRelay ?: DEFAULT_RELAY
        set(v) = prefs.edit().putString("relayUrl", v.trim().trimEnd('/')).apply()
    /** "system" | "light" | "dark" | "black" */
    var clock: String
        get() = prefs.getString("clock", "system")!!
        set(v) = prefs.edit().putString("clock", v).apply()
    var allowLan: Boolean
        get() = prefs.getBoolean("allow_lan_http", false)
        set(v) { prefs.edit().putBoolean("allow_lan_http", v).apply(); ApiClient.allowLanHttp = v }
    var theme: String
        get() = prefs.getString("theme", "system")!!
        set(v) = prefs.edit().putString("theme", v).apply()
    /** "balanced" | "saver": background power use (ping interval, wake lock, polling). */
    var powerMode: String
        get() = prefs.getString("powerMode", "balanced")!!
        set(v) = prefs.edit().putString("powerMode", v).apply()
    /** "system" | "en" | "zh" */
    /** UI text scale, 0.8..1.5 (on top of the system font size). */
    var fontScale: Float
        get() = prefs.getFloat("fontScale", 1f)
        set(v) = prefs.edit().putFloat("fontScale", v.coerceIn(0.5f, 1.5f)).apply()
    /** Last profile list scanned from the PC: "name\tdescription" lines. */
    var profileCache: List<Pair<String, String>>
        get() = prefs.getString("profileCache", "")!!.lines().filter { it.isNotBlank() }
            .map { it.substringBefore('\t') to it.substringAfter('\t', "") }
        set(v) = prefs.edit().putString("profileCache", v.joinToString("\n") { it.first + "\t" + it.second.replace('\n', ' ') }).apply()
    /** Last session list + project tree per profile (JSON), so the session page opens instantly. */
    fun snap(profile: String): String? = prefs.getString("snap_$profile", null)
    fun ctxCache(profile: String): String? = prefs.getString("ctx_$profile", null)
    fun setCtxCache(profile: String, json: String) = prefs.edit().putString("ctx_$profile", json).apply()
    fun setSnap(profile: String, json: String) = prefs.edit().putString("snap_$profile", json).apply()
    /** MEDIA:<path> lines already handed to the relay (key = session|path), so reloading history does not resend them. */
    fun mediaDone(key: String): Boolean = prefs.getStringSet("media_done", emptySet())!!.contains(key)
    fun markMedia(key: String) { val s = prefs.getStringSet("media_done", emptySet())!!.toMutableList(); s.add(key)
        prefs.edit().putStringSet("media_done", s.takeLast(500).toSet()).apply() }
    fun unmarkMedia(key: String) { prefs.edit().putStringSet("media_done", prefs.getStringSet("media_done", emptySet())!! - key).apply() }
    /** Per-reply token/speed stats, kept per session (key = hash of the reply text) so history keeps showing them. */
    fun replyStat(sid: String, text: String): List<Float?>? = runCatching {
        val a = org.json.JSONObject(prefs.getString("mstat_$sid", "{}")!!).optJSONArray(text.hashCode().toString()) ?: return null
        List(4) { if (a.isNull(it)) null else a.getDouble(it).toFloat() }
    }.getOrNull()
    fun setReplyStat(sid: String, text: String, v: List<Float?>) {
        val o = runCatching { org.json.JSONObject(prefs.getString("mstat_$sid", "{}")!!) }.getOrDefault(org.json.JSONObject())
        while (o.length() >= 300) o.remove(o.keys().next())
        o.put(text.hashCode().toString(), org.json.JSONArray(v.map { it ?: org.json.JSONObject.NULL }))
        prefs.edit().putString("mstat_$sid", o.toString()).apply()
    }
    /** User-chosen profile order (names); profiles not listed follow alphabetically. */
    var profileOrder: List<String>
        get() = prefs.getString("profileOrder", "")!!.lines().filter { it.isNotBlank() }
        set(v) = prefs.edit().putString("profileOrder", v.joinToString("\n")).apply()
    var lang: String
        get() = prefs.getString("lang", "system")!!
        set(v) = prefs.edit().putString("lang", v).commit().let { }
    var lastUpdateCheck: Long
        get() = prefs.getLong("lastUpdateCheck", 0)
        set(v) = prefs.edit().putLong("lastUpdateCheck", v).apply()
    /** Update source: "auto" (PC first, fall back to GitHub) | "github" | "pc". */
    var updateSource: String
        get() = prefs.getString("updateSource", "auto")!!.takeIf { it in UPDATE_SOURCES } ?: "auto"
        set(v) = prefs.edit().putString("updateSource", if (v in UPDATE_SOURCES) v else "auto").apply()
    /** Relay URL pushed by the hotfix config (overrides the default, not a user-entered value). */
    var hotfixRelay: String?
        get() = prefs.getString("hotfixRelay", null)
        set(v) = prefs.edit().putString("hotfixRelay", v).apply()
    /** Optional LAN address of the PC relay, tried when the main (Tailscale) URL doesn't answer. */
    var lanUrl: String
        get() = prefs.getString("lanUrl", "")!!
        set(v) = prefs.edit().putString("lanUrl", v.trim().trimEnd('/')).apply()
    /** The relay URL that answered last (not persisted). */
    @Volatile var activeRelay: String? = null
    fun relayBase(): String = activeRelay ?: relayUrl
    var profile: String
        get() = prefs.getString("profile", "default")!!
        set(v) = prefs.edit().putString("profile", v).apply()

    /** "provider|model" or "" = server default; stored per profile. */
    fun modelFor(profile: String): String = prefs.getString("model.$profile", "")!!
    fun setModelFor(profile: String, v: String) = prefs.edit().putString("model.$profile", v).apply()
    /** Files saved from the Inbox into a session: (name, content Uri). Kept so they show again when the chat reloads. */
    fun sessionFiles(id: String): List<Triple<String, String, Long>> = runCatching {
        val a = org.json.JSONArray(prefs.getString("files.$id", "[]")!!)
        (0 until a.length()).map { a.getJSONObject(it).let { o -> Triple(o.getString("name"), o.getString("uri"), o.optLong("ts", 0L)) } }
    }.getOrDefault(emptyList())
    /** Add or replace (by name) a file in a session. An empty [uri] means the file is still on the PC. */
    fun upsertSessionFile(id: String, name: String, uri: String) {
        val a = runCatching { org.json.JSONArray(prefs.getString("files.$id", "[]")!!) }.getOrDefault(org.json.JSONArray())
        val out = org.json.JSONArray()
        var ts = System.currentTimeMillis()   // when the file arrived; kept when the entry is replaced so the card stays where it was
        for (i in 0 until a.length()) a.getJSONObject(i).let { if (it.getString("name") != name) out.put(it) else if (it.optLong("ts", 0L) > 0) ts = it.getLong("ts") }
        out.put(org.json.JSONObject().put("name", name).put("uri", uri).put("ts", ts))
        prefs.edit().putString("files.$id", out.toString()).apply()
    }
    /** Outbox entries already handled on this phone, so a file is shown once. */
    fun inboxSeen(key: String): Boolean = prefs.getStringSet("inboxSeen", emptySet())!!.contains(key)
    fun markInboxSeen(key: String) {
        val seen = prefs.getStringSet("inboxSeen", emptySet())!!.toMutableSet()
        seen.add(key)
        while (seen.size > 300) seen.remove(seen.first())
        prefs.edit().putStringSet("inboxSeen", seen).apply()
    }
    fun sessionModel(id: String): String? = prefs.getString("smodel.$id", null)
    fun setSessionModel(id: String, v: String) = prefs.edit().putString("smodel.$id", v).apply()
    fun sessionEffort(id: String): String? = prefs.getString("seffort.$id", null)
    fun setSessionEffort(id: String, v: String) = prefs.edit().putString("seffort.$id", v).apply()
    fun effortFor(profile: String): String = prefs.getString("effort.$profile", "")!!
    fun setEffortFor(profile: String, v: String) = prefs.edit().putString("effort.$profile", v).apply()

    var apiKey: String?
        get() = prefs.getString("apiKeyEnc", null)?.let { runCatching { decrypt(it) }.getOrNull() }
        set(v) {
            if (v.isNullOrBlank()) prefs.edit().remove("apiKeyEnc").apply()
            else prefs.edit().putString("apiKeyEnc", encrypt(v.trim())).apply()
        }
    val hasKey get() = prefs.contains("apiKeyEnc")

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
        val EFFORTS = listOf("", "none", "minimal", "low", "medium", "high", "xhigh", "max")
        val UPDATE_SOURCES = listOf("auto", "github", "pc")
        /** Fallback until the PC's profile list has been fetched once. */
        val PROFILES = listOf("default")
    }
}
