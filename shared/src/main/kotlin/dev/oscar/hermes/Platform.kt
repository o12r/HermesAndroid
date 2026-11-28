package dev.oscar.hermes

/** What the chat engine needs from the host OS; Android and desktop each implement it. */
interface Platform {
    /** The app is on screen (drives notification suppression and background-poll cadence). */
    val visible: Boolean
    /** All-files access granted (Android: MANAGE_EXTERNAL_STORAGE; desktop is always true). */
    val hasAllFiles: Boolean get() = true
    /** Dismiss alerts of [kinds] (all when empty) for [session]. */
    fun clearAlerts(session: String?, vararg kinds: String)
    /** Monotonic milliseconds (Android: SystemClock.elapsedRealtime). */
    fun elapsedMs(): Long
    /** Diagnostic warning; never pass message content. */
    fun log(tag: String, msg: String)
    val deviceId: String
    /** App-private cache directory (temp files). */
    val cacheDir: java.io.File
    /** String resource by name, in the user's chosen language. */
    fun string(key: String, vararg a: Any): String
    fun alert(title: String, text: String, force: Boolean = false, profile: String? = null, session: String? = null, kind: String = "other")
    /** A reply finished while the app is in the background. Android posts it with an inline Reply action; the default is a plain alert. */
    fun notifyReply(profile: String, session: String, title: String, text: String) = alert(title, text, profile = profile, session = session, kind = "reply")
    /** (own IPv4, default gateway) of the LAN-ish network, or null. */
    fun localNet(): Pair<String, String?>?
    fun watchNetwork(onAvailable: () -> Unit, onLost: () -> Unit)
    fun unwatchNetwork()
    /** Battery-saver / power policy (drives the ping interval) and the keep-alive service. */
    fun isSaver(): Boolean
    fun keepAliveStart()
    fun keepAliveStop()
    fun is24h(): Boolean
    val updater: Updater
    /** Downloads [name] from the PC outbox into the app cache (no Downloads copy; the PC keeps the file); returns the cached file's uri. */
    /** [key] identifies the outbox entry (name|time) so two files with the same name never share a cache file. */
    suspend fun fetchInbox(name: String, key: String, progress: (Int) -> Unit): String
    /** Copies a cached file (uri from [fetchInbox]) into the user's Downloads as [name]; returns the saved file's uri. */
    fun saveFromCache(uri: String, name: String): String
    /** Copies [file] into the user's Downloads as [name]; returns its uri. */
    fun saveToDownloads(file: java.io.File, name: String): String
    /** Modified/added time (ms) of a saved file, or Long.MAX_VALUE when unknown. */
    fun fileTime(uri: String): Long
    /** Opens the phone file at absolute [path] for upload; null when it does not exist or this app cannot read it. */
    fun openFile(path: String): AttachmentSource?
}
