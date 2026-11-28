package dev.oscar.hermes

import org.json.JSONObject

/** Persistent settings and caches the controller needs; Android backs it with SharedPreferences + Keystore, desktop with its own store. */
interface Store {
    var profile: String
    var relayUrl: String
    var lanUrl: String
    /** Ordered relay addresses (first = main). Replaces the old relay + LAN pair. */
    var servers: List<String>
    var allowLan: Boolean
    var apiKey: String?
    val hasKey: Boolean
    var activeRelay: String?
    fun relayBase(): String
    var clock: String
    var theme: String
    var powerMode: String
    var fontScale: Float
    var lang: String
    var lastUpdateCheck: Long
    var updateSource: String
    /** Runtime switch of an optional feature module (Settings → modules); on unless the user turned it off. */
    fun featureEnabled(id: String): Boolean = true
    fun setFeatureEnabled(id: String, on: Boolean) {}
    /** Reply notifications with an inline Reply box when a reply finishes in the background (Settings → connection). On by default. */
    fun replyNotifyOn(): Boolean = true
    fun setReplyNotifyOn(on: Boolean) {}
    /** Turns loaded on open and per older batch when scrolling up (Settings > Appearance), 3..30. */
    fun historyTurns(): Int = 5
    fun setHistoryTurns(v: Int) {}
    /** Ids of Settings groups / feature entries pinned to the top bar, in pin order (see TopPins). */
    fun pinnedTop(): List<String> = emptyList()
    fun setPinnedTop(ids: List<String>) {}
    /** Sessions pinned to the top of the session list, as "profile<TAB>session id" keys in pin order. */
    fun pinnedSessionKeys(): List<String> = emptyList()
    fun setPinnedSessionKeys(keys: List<String>) {}
    /** Saved PC key chords ("ctrl+alt+f10") for the PC page buttons. */
    fun keyCombos(): List<String> = emptyList()
    fun setKeyCombos(v: List<String>) {}
    var hotfixRelay: String?
    var profileCache: List<Pair<String, String>>
    var profileOrder: List<String>
    fun snap(profile: String): String?
    fun setSnap(profile: String, json: String)
    fun ctxCache(profile: String): String?
    fun setCtxCache(profile: String, json: String)
    fun mediaDone(key: String): Boolean
    fun markMedia(key: String)
    fun unmarkMedia(key: String)
    fun replyStat(sid: String, text: String): List<Float?>?
    fun setReplyStat(sid: String, text: String, v: List<Float?>)
    fun replyStatsJson(sid: String): JSONObject
    fun mergeReplyStats(sid: String, other: JSONObject)
    fun modelFor(profile: String): String
    fun setModelFor(profile: String, v: String)
    fun effortFor(profile: String): String
    fun setEffortFor(profile: String, v: String)
    /** One global default thinking effort; used by a profile/chat with no effort of its own. "" = server default. */
    fun defaultEffort(): String = ""
    fun setDefaultEffort(v: String) {}
    fun sessionModel(id: String): String?
    fun setSessionModel(id: String, v: String)
    fun sessionEffort(id: String): String?
    fun setSessionEffort(id: String, v: String)
    fun sessionFiles(id: String): List<Triple<String, String, Long>>
    fun upsertSessionFile(id: String, name: String, uri: String, at: Long = 0L)
    fun inboxSeen(key: String): Boolean
    fun markInboxSeen(key: String)
    companion object {
        val EFFORTS = listOf("", "none", "minimal", "low", "medium", "high", "xhigh", "max")
    }
}
