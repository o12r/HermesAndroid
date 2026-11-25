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
}
