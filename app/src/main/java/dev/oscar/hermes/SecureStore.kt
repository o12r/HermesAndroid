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
    var theme: String
        get() = prefs.getString("theme", "system")!!
        set(v) = prefs.edit().putString("theme", v).apply()
    /** "system" | "en" | "zh" */
    /** UI text scale, 0.8..1.5 (on top of the system font size). */
    var fontScale: Float
        get() = prefs.getFloat("fontScale", 1f)
        set(v) = prefs.edit().putFloat("fontScale", v.coerceIn(0.8f, 1.5f)).apply()
    /** Last profile list scanned from the PC: "name\tdescription" lines. */
    var profileCache: List<Pair<String, String>>
        get() = prefs.getString("profileCache", "")!!.lines().filter { it.isNotBlank() }
            .map { it.substringBefore('\t') to it.substringAfter('\t', "") }
        set(v) = prefs.edit().putString("profileCache", v.joinToString("\n") { it.first + "\t" + it.second.replace('\n', ' ') }).apply()
    var lang: String
        get() = prefs.getString("lang", "system")!!
        set(v) = prefs.edit().putString("lang", v).commit().let { }
    var lastUpdateCheck: Long
        get() = prefs.getLong("lastUpdateCheck", 0)
        set(v) = prefs.edit().putLong("lastUpdateCheck", v).apply()
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
        /** Fallback until the PC's profile list has been fetched once. */
        val PROFILES = listOf("default")
    }
}
