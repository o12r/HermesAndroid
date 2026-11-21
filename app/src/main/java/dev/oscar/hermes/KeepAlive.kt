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

    fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(ctx, 0,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** [force]: also while the app is open (the event is for a chat that isn't on screen). */
    fun alert(ctx: Context, title: String, text: String, force: Boolean = false) {
        if (visible && !force) return
        channels(ctx)
        val n = Notification.Builder(ctx, CH_ALERTS)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title).setContentText(text.take(300))
            .setStyle(Notification.BigTextStyle().bigText(text.take(1000)))
            .setContentIntent(openApp(ctx)).setAutoCancel(true).build()
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(nextId++, n) }
    }
}
