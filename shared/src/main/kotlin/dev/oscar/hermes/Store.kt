package dev.oscar.hermes

import org.json.JSONObject

/** Persistent settings and caches the controller needs; Android backs it with SharedPreferences + Keystore, desktop with its own store. */
interface Store {
    var profile: String
    var relayUrl: String
    var lanUrl: String
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
