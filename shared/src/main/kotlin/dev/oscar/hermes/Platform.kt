package dev.oscar.hermes

/** What the chat engine needs from the host OS; Android and desktop each implement it. */
interface Platform {
    /** The app is on screen (drives notification suppression and background-poll cadence). */
    val visible: Boolean
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
    /** Downloads [name] from the PC outbox into the user's Downloads; returns the saved file's uri. */
    suspend fun saveInbox(name: String, progress: (Int) -> Unit): String
    /** Copies [file] into the user's Downloads as [name]; returns its uri. */
    fun saveToDownloads(file: java.io.File, name: String): String
    /** Modified/added time (ms) of a saved file, or Long.MAX_VALUE when unknown. */
    fun fileTime(uri: String): Long
}
