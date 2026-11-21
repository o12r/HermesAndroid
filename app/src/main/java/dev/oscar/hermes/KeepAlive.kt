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
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) { stopSelf() }

    companion object {
        fun start(ctx: Context) = runCatching { ctx.startForegroundService(Intent(ctx, KeepAliveService::class.java)) }
        fun stop(ctx: Context) = runCatching { ctx.stopService(Intent(ctx, KeepAliveService::class.java)) }
    }
}

/** Alerts shown only while the app isn't on screen. */
object Notify {
    const val CH_SERVICE = "keepalive"
    const val CH_ALERTS = "alerts"
    @Volatile var visible = false
    private var nextId = 100

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

    fun alert(ctx: Context, title: String, text: String, force: Boolean = false, profile: String? = null, session: String? = null) {
        if (visible && !force) return
        channels(ctx)
        alertWith(ctx, title, text, openApp(ctx, profile, session))
    }

    private fun alertWith(ctx: Context, title: String, text: String, tap: PendingIntent) {
        val n = Notification.Builder(ctx, CH_ALERTS)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title).setContentText(text.take(300))
            .setStyle(Notification.BigTextStyle().bigText(text.take(1000)))
            .setContentIntent(tap).setAutoCancel(true).build()
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(nextId++, n) }
    }
}
