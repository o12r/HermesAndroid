package dev.oscar.hermes

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject

/** Inline-reply notifications for finished replies while the app is in the background. */
object ReplyNotify {
    const val KEY_TEXT = "reply_text"
    const val EXTRA_TITLE = "reply_title"
    private const val QUEUE_KEY = "replyQueue"
    private const val SUMMARY_CHARS = 200

    /** Set by the live ChatViewModel (main thread); null when this process has no controller. */
    @Volatile var deliver: ((String, String, String) -> Unit)? = null

    /** One notification per chat: a newer reply replaces the older one. */
    fun id(profile: String, session: String): Int = ("reply|" + profile + "|" + session).hashCode()

    /** Post or update the reply notification of a chat. With [status] it is a plain status line (no reply box). */
    fun post(ctx: Context, profile: String, session: String, title: String, text: String, status: String? = null, timeoutMs: Long = 0L) {
        Notify.channels(ctx)
        val res = L10n.ctx(ctx)
        val id = id(profile, session)
        val b = Notification.Builder(ctx, Notify.CH_REPLIES)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentIntent(Notify.openApp(ctx, profile, session))
            .setAutoCancel(true)
        if (status == null) {
            val summary = text.take(SUMMARY_CHARS) + (if (text.length > SUMMARY_CHARS) "…" else "")
            val reply = Intent(ctx, ReplyReceiver::class.java)
                .putExtra(Notify.EXTRA_PROFILE, profile).putExtra(Notify.EXTRA_SESSION, session).putExtra(EXTRA_TITLE, title)
            // FLAG_MUTABLE: RemoteInput writes the typed text into this intent (required on API 31+).
            val pi = PendingIntent.getBroadcast(ctx, id, reply, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val input = RemoteInput.Builder(KEY_TEXT).setLabel(res.getString(R.string.reply_hint)).build()
            val action = Notification.Action.Builder(android.R.drawable.ic_menu_send, res.getString(R.string.reply_action), pi)
                .addRemoteInput(input).setAllowGeneratedReplies(false).build()
            b.setContentText(summary).setStyle(Notification.BigTextStyle().bigText(summary)).addAction(action)
        } else {
            b.setContentText(status)
        }
        if (timeoutMs > 0) b.setTimeoutAfter(timeoutMs)
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(id, b.build()) }
        Notify.track("$session|reply", id)
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("hermes", Context.MODE_PRIVATE)

    /** A reply typed in a notification while no controller is alive: kept until the app opens. */
    fun enqueue(ctx: Context, profile: String, session: String, text: String) {
        val a = runCatching { JSONArray(prefs(ctx).getString(QUEUE_KEY, "[]")) }.getOrDefault(JSONArray())
        a.put(JSONObject().put("p", profile).put("s", session).put("t", text))
        prefs(ctx).edit().putString(QUEUE_KEY, a.toString()).apply()
    }

    /** Called once the controller exists: sends the replies queued while there was none. */
    fun flushQueued(ctx: Context, send: (String, String, String) -> Unit) {
        val raw = prefs(ctx).getString(QUEUE_KEY, null) ?: return
        prefs(ctx).edit().remove(QUEUE_KEY).apply()
        val a = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            send(o.optString("p"), o.optString("s"), o.optString("t"))
        }
    }
}

/** Receives the inline reply: sends it into that chat, or queues it and opens the app when no controller is alive. */
class ReplyReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(ReplyNotify.KEY_TEXT)?.toString()?.trim().orEmpty()
        val profile = intent.getStringExtra(Notify.EXTRA_PROFILE).orEmpty()
        val session = intent.getStringExtra(Notify.EXTRA_SESSION).orEmpty()
        if (text.isEmpty() || session.isEmpty()) return
        val title = intent.getStringExtra(ReplyNotify.EXTRA_TITLE).orEmpty().ifBlank { "Hermes" }
        val live = ReplyNotify.deliver
        val sent = live != null && runCatching { live(profile, session, text) }.isSuccess
        if (sent) {
            ReplyNotify.post(ctx, profile, session, title, text, status = L10n.ctx(ctx).getString(R.string.reply_sent), timeoutMs = 4000L)
        } else {
            ReplyNotify.enqueue(ctx, profile, session, text)
            ReplyNotify.post(ctx, profile, session, title, text, status = L10n.ctx(ctx).getString(R.string.reply_queued))
            // Starting the activity is what creates a controller; the tap on the notification does the same if Android blocks it.
            runCatching {
                ctx.startActivity(Intent(ctx, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    .putExtra(Notify.EXTRA_PROFILE, profile).putExtra(Notify.EXTRA_SESSION, session))
            }
        }
    }
}
