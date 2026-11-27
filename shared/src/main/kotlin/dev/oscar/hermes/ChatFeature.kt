package dev.oscar.hermes

/**
 * An optional feature (virtual display, PC files, PC stats ...). Feature modules list their implementation in
 * META-INF/services/dev.oscar.hermes.ChatFeature; the hub discovers whatever is on the classpath, so leaving a
 * module out of the build simply removes the feature. Every hook has a no-op default.
 */
interface ChatFeature {
    val id: String
    /** Called once, after the core controllers exist and before the first page opens. */
    fun attach(hub: ChatController) {}
    /** Title string key (Str.*) of the page tab [kind] if this feature owns it. */
    fun pageTitleKey(kind: String): String? = null
    /** The user entered the page tab [kind] with refresh requested. */
    fun pageEntered(kind: String) {}
    /** Prefix shown on the chat tab [chatId] (e.g. an emoji), or null. */
    fun tabBadge(chatId: String): String? = null
    fun close() {}
}
