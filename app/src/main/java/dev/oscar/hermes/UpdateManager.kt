package dev.oscar.hermes

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

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

/**
 * In-app updates + hotfix config. Sources, in order:
 *  1. the PC relay: GET {relay}/app/hermes-android/latest.json, .../apk, .../hotfix.json (Bearer key)
 *  2. GitHub: releases/latest of [GITHUB_REPO] (asset *.apk, versionCode in the asset name) and hotfix.json on main.
 */
class UpdateManager(private val ctx: Context, private val store: SecureStore) {
    private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true).build()

    val currentCode: Int = ctx.packageManager.getPackageInfo(ctx.packageName, 0).let {
        if (Build.VERSION.SDK_INT >= 28) it.longVersionCode.toInt() else @Suppress("DEPRECATION") it.versionCode }
    val currentName: String = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"

    private fun relayReq(path: String): Request? {
        val key = store.apiKey ?: return null
        val base = store.relayBase().trimEnd('/')
        if (!ApiClient.isTailscaleHost(java.net.URI(base).host ?: "") && base.startsWith("http:")) return null
        return Request.Builder().url("$base/app/$APP_ID$path").header("Authorization", "Bearer $key").build()
    }

    private fun getJson(r: Request): JSONObject? = runCatching {
        http.newCall(r).execute().use { if (it.isSuccessful) JSONObject(it.body!!.string()) else null }
    }.getOrNull()

    /** Newest available build from any source, or null if none is newer than this one. */
    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        val pc = relayReq("/latest.json")?.let { getJson(it) }?.let {
            UpdateInfo(it.getInt("version_code"), it.optString("version_name"), it.optString("notes"),
                store.relayBase().trimEnd('/') + "/app/$APP_ID/apk", "pc")
        }
        val gh = if (pc != null && pc.versionCode > currentCode) null else github()
        listOfNotNull(pc, gh).filter { it.versionCode > currentCode }.maxByOrNull { it.versionCode }
    }

    private fun github(): UpdateInfo? {
        val o = getJson(Request.Builder().url("https://api.github.com/repos/$GITHUB_REPO/releases/latest")
            .header("Accept", "application/vnd.github+json").build()) ?: return null
        val assets = o.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i); val name = a.optString("name")
            val code = Regex("""-(\d+)\.apk$""").find(name)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            return UpdateInfo(code, o.optString("tag_name").removePrefix("v"), o.optString("body"),
                a.getString("browser_download_url"), "github")
        }
        return null
    }

    suspend fun hotfix(): Hotfix = withContext(Dispatchers.IO) {
        val o = relayReq("/hotfix.json")?.let { getJson(it) }
            ?: getJson(Request.Builder().url("https://raw.githubusercontent.com/$GITHUB_REPO/main/hotfix.json").build())
        o?.let { runCatching { Hotfix.parse(it) }.getOrNull() } ?: Hotfix.NONE
    }

    /** Download the APK and hand it to the system installer (the user confirms). */
    suspend fun install(u: UpdateInfo, progress: (Int) -> Unit) = withContext(Dispatchers.IO) {
        val file = File(ctx.cacheDir, "update-${u.versionCode}.apk")
        ctx.cacheDir.listFiles()?.filter { it.name.startsWith("update") && it != file }?.forEach { it.delete() }
        // Keep the CPU and Wi-Fi awake while downloading, so going to the background doesn't stall the socket.
        val pm = ctx.getSystemService(android.os.PowerManager::class.java)
        val wake = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "hermes:update").apply { acquire(10 * 60_000L) }
        @Suppress("DEPRECATION")
        val wifi = ctx.applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
            ?.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "hermes:update")?.apply { acquire() }
        try {
            // Resumable: on a dropped connection, retry with Range from where it stopped (up to 8 tries, backoff).
            var total = -1L; var attempt = 0
            while (true) {
                val have = file.length()
                if (total > 0 && have >= total) break
                // Re-resolve every try: the relay address may have switched (Tailscale <-> Wi-Fi).
                val base = if (u.source == "pc") relayReq("/apk") ?: throw ApiException("PC relay not reachable") else Request.Builder().url(u.apkUrl).build()
                val req = base.newBuilder().apply { if (have > 0) header("Range", "bytes=$have-") }.build()
                try {
                    http.newCall(req).execute().use { resp ->
                        if (resp.code == 416) { total = have; return@use }
                        if (!resp.isSuccessful) throw ApiException("Download failed (HTTP ${resp.code})")
                        val body = resp.body!!
                        val append = resp.code == 206 && have > 0
                        if (!append) file.delete()
                        val start = if (append) have else 0L
                        if (body.contentLength() > 0) total = start + body.contentLength()
                        java.io.FileOutputStream(file, append).use { out ->
                            val src = body.byteStream(); val buf = ByteArray(64 * 1024); var done = start; var n: Int
                            while (src.read(buf).also { n = it } >= 0) {
                                out.write(buf, 0, n); done += n
                                if (total > 0) progress((done * 100 / total).toInt())
                            }
                        }
                    }
                    if (total <= 0) break        // unknown length: one full read is all we can do
                    attempt = 0
                } catch (e: java.io.IOException) {
                    if (++attempt > 8) throw ApiException("Download interrupted: ${e.message}")
                    kotlinx.coroutines.delay(minOf(30_000L, 1500L shl (attempt - 1)))
                }
            }
        } finally { runCatching { wake.release() }; runCatching { wifi?.release() } }
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            file.inputStream().use { input -> s.openWrite("base.apk", 0, file.length()).use { input.copyTo(it); s.fsync(it) } }
            val intent = Intent(ctx, InstallReceiver::class.java)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            s.commit(PendingIntent.getBroadcast(ctx, id, intent, flags).intentSender)
        }
        file.delete()
    }

    companion object {
        const val APP_ID = "hermes-android"
        const val GITHUB_REPO = "o12r/HermesAndroid"
    }
}

/** Receives installer status; launches the system confirmation screen when needed. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else intent.getParcelableExtra(Intent.EXTRA_INTENT)) ?: return
                // Android blocks starting the installer from the background: then post a tap-to-install notification.
                if (Notify.visible) context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                else Notify.installReady(context, confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> {}
            else -> android.widget.Toast.makeText(context,
                intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Install failed", android.widget.Toast.LENGTH_LONG).show()
        }
    }
}
