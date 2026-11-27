package dev.oscar.hermes

import org.json.JSONObject

/** A downloadable build. `source` is "pc" or "github". */
data class UpdateInfo(val versionCode: Int, val versionName: String, val notes: String, val apkUrl: String, val source: String)

/** Server-side config fetched without reinstalling (hotfix). */
data class Hotfix(
    val bannerEn: String?, val bannerZh: String?, val minVersionCode: Int, val relayUrl: String?,
    val attachments: Boolean, val serverSlash: Boolean,
) {
    fun banner(zh: Boolean) = (if (zh) bannerZh ?: bannerEn else bannerEn ?: bannerZh)?.takeIf { it.isNotBlank() }
    companion object {
        val NONE = Hotfix(null, null, 0, null, attachments = true, serverSlash = true)
        fun parse(o: JSONObject): Hotfix {
            val b = o.optJSONObject("banner"); val f = o.optJSONObject("features") ?: JSONObject()
            fun s(x: JSONObject?, k: String) = x?.takeIf { !it.isNull(k) }?.optString(k)?.takeIf { it.isNotBlank() }
            return Hotfix(s(b, "en"), s(b, "zh"), o.optInt("min_version_code", 0), s(o, "relay_url"),
                f.optBoolean("attachments", true), f.optBoolean("server_slash", true))
        }
    }
}

/** In-app update source (Android: UpdateManager; desktop: its own). */
interface Updater {
    val currentCode: Int
    val currentName: String
    suspend fun check(source: String = "auto"): UpdateInfo?
    suspend fun hotfix(): Hotfix
    suspend fun install(u: UpdateInfo, progress: (Int) -> Unit)
    fun downloadedSize(): Long
    fun deleteFiles()
}
