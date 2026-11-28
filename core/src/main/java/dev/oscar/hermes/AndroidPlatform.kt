package dev.oscar.hermes

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import java.io.File

/** Android implementation of [Platform]: notifications, keep-alive service, connectivity, MediaStore. */
class AndroidPlatform(private val app: Application, private val store: Store) : Platform {
    override val visible get() = Notify.visible
    override fun clearAlerts(session: String?, vararg kinds: String) = Notify.clear(app, session, *kinds)
    override fun elapsedMs() = android.os.SystemClock.elapsedRealtime()
    override val hasAllFiles: Boolean get() = FileStaging.hasAllFiles()
    override fun openFile(path: String): AttachmentSource? = PhoneFileSource.open(path)
    override fun log(tag: String, msg: String) { android.util.Log.w(tag, msg) }
    override val deviceId: String by lazy { android.provider.Settings.Secure.getString(app.contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: "phone" }
    override val cacheDir: File get() = app.cacheDir
    private val res get() = L10n.wrap(app, store.lang)
    override fun string(key: String, vararg a: Any): String = res.getString(res.resources.getIdentifier(key, "string", res.packageName), *a)
    override fun notifyReply(profile: String, session: String, title: String, text: String) {
        if (session.isBlank() || !store.replyNotifyOn()) {
            Notify.alert(app, title, text, force = true, profile = profile, session = session, kind = "reply")
        } else {
            ReplyNotify.post(app, profile, session, title, text)
        }
    }
    override fun alert(title: String, text: String, force: Boolean, profile: String?, session: String?, kind: String) =
        Notify.alert(app, title, text, force = force, profile = profile, session = session, kind = kind)
    override fun localNet(): Pair<String, String?>? = localNetImpl()
    /** (own IPv4, default gateway) of the LAN-ish network: Wi-Fi, Ethernet, or WSA/emulator's virtual NIC; never VPN or cellular. */
    private fun localNetImpl(): Pair<String, String?>? = runCatching {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        cm.allNetworks.mapNotNull { n ->
            val caps = cm.getNetworkCapabilities(n) ?: return@mapNotNull null
            if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) || caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)) return@mapNotNull null
            val lp = cm.getLinkProperties(n) ?: return@mapNotNull null
            val ip = lp.linkAddresses.map { it.address }.firstOrNull { it is java.net.Inet4Address && it.isSiteLocalAddress }?.hostAddress ?: return@mapNotNull null
            val gw = lp.routes.firstOrNull { it.isDefaultRoute && it.gateway is java.net.Inet4Address }?.gateway?.hostAddress
            (if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) 0 else 1) to (ip to gw)
        }.minByOrNull { it.first }?.second
    }.getOrNull() ?: hotspotNet()
    /** This phone is the hotspot (the PC joined it): its own AP interface isn't a Network, so read it directly. */
    private fun hotspotNet(): Pair<String, String?>? = runCatching {
        // USB tethering (this phone is the host: rndis0/usb0/ncm0), the hotspot AP (ap0/swlan0/wlan1) or any other local NIC. Cellular/VPN never.
        val nics = java.net.NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback && !it.name.startsWith("tun") && !it.name.startsWith("rmnet") && !it.name.startsWith("ccmni") }
        fun rank(n: java.net.NetworkInterface) = if (n.name.startsWith("rndis") || n.name.startsWith("usb") || n.name.startsWith("ncm")) 0 else 1
        nics.sortedBy { rank(it) }.firstNotNullOfOrNull { n ->
            n.inetAddresses.toList().firstOrNull { it is java.net.Inet4Address && it.isSiteLocalAddress }?.hostAddress
        }?.let { it to null }
    }.getOrNull()

    private var netCb: ConnectivityManager.NetworkCallback? = null
    override fun watchNetwork(onAvailable: () -> Unit, onLost: () -> Unit) {
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onAvailable()
            override fun onLost(network: Network) = onLost()
        }
        netCb = cb
        app.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(cb)
    }
    override fun unwatchNetwork() {
        netCb?.let { cb -> runCatching { app.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(cb) } }
        netCb = null
    }
    override fun isSaver() = KeepAliveService.isSaver(app)
    override fun keepAliveStart() { KeepAliveService.start(app) }
    override fun keepAliveStop() { KeepAliveService.stop(app) }
    override fun is24h() = android.text.format.DateFormat.is24HourFormat(app.applicationContext)
    override val updater: Updater by lazy { UpdateManager(app, store) }
    override suspend fun fetchInbox(name: String, key: String, progress: (Int) -> Unit): String = Inbox.fetchToCache(app, store, name, key, progress).toString()
    override fun saveFromCache(uri: String, name: String): String =
        saveToDownloads(MediaCache.fileOf(app, uri) ?: throw ApiException("The file is no longer in the app cache"), name)
    override fun saveToDownloads(file: File, name: String): String {
        val cr = app.contentResolver
        val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name); put(android.provider.MediaStore.Downloads.MIME_TYPE, mime)
            put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = cr.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw ApiException("Couldn't create the file in Downloads")
        try {
            cr.openOutputStream(uri)!!.use { o -> file.inputStream().use { it.copyTo(o) } }
            cr.update(uri, android.content.ContentValues().apply { put(android.provider.MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) { cr.delete(uri, null, null); throw e }
        return uri.toString()
    }
    override fun fileTime(uri: String): Long = runCatching {
        if (uri.isBlank()) return Long.MAX_VALUE
        MediaCache.fileOf(app, uri)?.let { return it.lastModified() }
        val u = Uri.parse(uri)
        app.contentResolver.query(u, null, null, null, null)?.use { q ->
            if (!q.moveToFirst()) return@use null
            val ms = q.getColumnIndex("last_modified").takeIf { it >= 0 }?.let { q.getLong(it) }?.takeIf { it > 0 }
            val sec = listOf("date_modified", "date_added").map { q.getColumnIndex(it) }.firstOrNull { it >= 0 }?.let { q.getLong(it) * 1000 }?.takeIf { it > 0 }
            ms ?: sec
        }
    }.getOrNull() ?: Long.MAX_VALUE
}
