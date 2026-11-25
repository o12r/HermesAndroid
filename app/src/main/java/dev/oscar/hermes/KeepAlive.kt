package dev.oscar.hermes

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/** Foreground service that keeps the process (and the ViewModel's relay WebSocket) alive in the
 *  background. Runs while the app's task exists; swiping the app away stops it. */
class KeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Notify.channels(this)
        val n = Notification.Builder(this, Notify.CH_SERVICE)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(L10n.ctx(this).getString(R.string.keepalive_title))
            .setContentText(L10n.ctx(this).getString(R.string.keepalive_text))
            .setContentIntent(Notify.openApp(this))
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
        else startForeground(1, n)
        // Screen locked: keep the CPU awake so the gateway socket (and its pings) stays up. No Wi-Fi high-perf lock (big drain).
        // Saver mode: no wake lock at all; the exempted app still gets network in Doze maintenance windows.
        applyWake()
        if (psReceiver == null) {   // "follow system" mode: re-evaluate when the system battery saver toggles
            psReceiver = object : android.content.BroadcastReceiver() { override fun onReceive(c: Context?, i: Intent?) { applyWake() } }
            registerReceiver(psReceiver, android.content.IntentFilter(android.os.PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
        }
        return START_NOT_STICKY
    }

    private var psReceiver: android.content.BroadcastReceiver? = null
    private fun applyWake() {
        val saver = isSaver(this)
        if (saver) { runCatching { wake?.release() }; wake = null }
        else if (wake == null) wake = getSystemService(android.os.PowerManager::class.java)
            .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "hermes:keepalive").apply { setReferenceCounted(false); acquire() }
        GatewayWs.pingSec = if (saver) 60L else 30L
    }

    private var wake: android.os.PowerManager.WakeLock? = null
    override fun onDestroy() { runCatching { wake?.release() }; wake = null; psReceiver?.let { runCatching { unregisterReceiver(it) } }; psReceiver = null; super.onDestroy() }

    override fun onTaskRemoved(rootIntent: Intent?) { stopSelf() }

    companion object {
        /** Saver mode, or "system" while Android's own battery saver is on. */
        fun isSaver(ctx: Context): Boolean {
            val m = ctx.getSharedPreferences("hermes", Context.MODE_PRIVATE).getString("powerMode", "balanced")
            return m == "saver" || (m == "system" && ctx.getSystemService(android.os.PowerManager::class.java).isPowerSaveMode)
        }
        fun start(ctx: Context) = runCatching { ctx.startForegroundService(Intent(ctx, KeepAliveService::class.java)) }

        /** Doze still cuts the network unless the app is exempt from battery optimisation: ask once. */
        @android.annotation.SuppressLint("BatteryLife")
        fun askBatteryExemption(ctx: android.app.Activity) {
            val pm = ctx.getSystemService(android.os.PowerManager::class.java)
            val prefs = ctx.getSharedPreferences("keepalive", Context.MODE_PRIVATE)
            if (pm.isIgnoringBatteryOptimizations(ctx.packageName) || prefs.getBoolean("asked", false)) return
            prefs.edit().putBoolean("asked", true).apply()
            runCatching { ctx.startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:" + ctx.packageName))) }
        }
        fun stop(ctx: Context) = runCatching { ctx.stopService(Intent(ctx, KeepAliveService::class.java)) }
    }
}

/** Alerts shown only while the app isn't on screen. */
object Notify {
    const val CH_SERVICE = "keepalive"
    const val CH_ALERTS = "alerts"
    @Volatile var visible = false
    private var nextId = 100
    /** "$session|$kind" -> notification ids, so answered prompts / seen replies can be cleared (on any device). */
    private val shown = java.util.concurrent.ConcurrentHashMap<String, MutableSet<Int>>()

    /** Cancel [session]'s notifications of [kinds] (all kinds when empty). */
    fun clear(ctx: Context, session: String?, vararg kinds: String) {
        if (session == null) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        for (k in shown.keys.filter { it.startsWith("$session|") && (kinds.isEmpty() || it.substringAfter('|') in kinds) })
            shown.remove(k)?.forEach { runCatching { nm.cancel(it) } }
    }

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val c = L10n.ctx(ctx)
        nm.createNotificationChannel(NotificationChannel(CH_SERVICE, c.getString(R.string.ch_connection), NotificationManager.IMPORTANCE_MIN))
        nm.createNotificationChannel(NotificationChannel(CH_ALERTS, c.getString(R.string.ch_alerts), NotificationManager.IMPORTANCE_HIGH))
    }

    const val EXTRA_PROFILE = "open_profile"
    const val EXTRA_SESSION = "open_session"

    /** Tap opens the app; with [profile]/[session] it jumps straight to that chat. */
    fun openApp(ctx: Context, profile: String? = null, session: String? = null): PendingIntent = PendingIntent.getActivity(ctx,
        session?.hashCode() ?: 0,      // one PendingIntent per chat, so extras don't overwrite each other
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            .apply { if (session != null) putExtra(EXTRA_PROFILE, profile).putExtra(EXTRA_SESSION, session) },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** [force]: also while the app is open (the event is for a chat that isn't on screen). */
    /** Update downloaded while the app was in the background: tap to open the system install screen. */
    fun installReady(ctx: Context, confirm: Intent) {
        channels(ctx)
        val pi = PendingIntent.getActivity(ctx, 7, confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alertWith(ctx, ctx.getString(R.string.n_update_ready), ctx.getString(R.string.n_update_ready_text), pi)
    }

    /** [kind]: "reply" / "approval" / "clarify" / "error", used by [clear]. */
    fun alert(ctx: Context, title: String, text: String, force: Boolean = false, profile: String? = null, session: String? = null,
              kind: String = "other") {
        if (visible && !force) return
        channels(ctx)
        alertWith(ctx, title, text, openApp(ctx, profile, session), session?.let { "$it|$kind" })
    }

    private fun alertWith(ctx: Context, title: String, text: String, tap: PendingIntent, key: String? = null) {
        val n = Notification.Builder(ctx, CH_ALERTS)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title).setContentText(text.take(300))
            .setStyle(Notification.BigTextStyle().bigText(text.take(1000)))
            .setContentIntent(tap).setAutoCancel(true).build()
        val id = nextId++
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(id, n) }
        key?.let { shown.getOrPut(it) { java.util.concurrent.ConcurrentHashMap.newKeySet() }.add(id) }
    }
}
