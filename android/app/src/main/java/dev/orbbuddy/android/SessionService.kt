package dev.orbbuddy.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.PowerManager

/** Keeps a user-started testing session visible while the accessibility socket runs. */
class SessionService : Service() {
    private val channelId = "orbbuddy-session"
    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private val renewWakeLock = object : Runnable {
        override fun run() {
            wakeLock?.acquire(10 * 60 * 1000L)
            getSystemService(NotificationManager::class.java).notify(1, buildNotification())
            handler.postDelayed(this, 5 * 60 * 1000L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!getSharedPreferences("connection", MODE_PRIVATE).getBoolean("enabled", false)) {
            stopSelf()
            return START_NOT_STICKY
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(channelId, "圆伴测试会话", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notification)
        }
        if (wakeLock == null) {
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OrbBuddy:TestingSession")
                .apply { setReferenceCounted(false) }
        }
        handler.removeCallbacks(renewWakeLock)
        handler.post(renewWakeLock)
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val prefs = getSharedPreferences("connection", MODE_PRIVATE)
        val apiActive = prefs.getBoolean("api_auth_active", false) &&
            System.currentTimeMillis() < prefs.getLong("api_auth_expires_at", 0L) &&
            System.currentTimeMillis() - prefs.getLong("api_last_used_at", 0L) < 5 * 60_000L
        val keepAwake = prefs.getLong("keep_awake_until", 0L) > System.currentTimeMillis()
        val builder = Notification.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(if (keepAwake) "圆伴临时保持亮屏" else if (apiActive) "圆伴 HTTPS 远程访问中" else "圆伴测试会话已开启")
            .setContentText(if (keepAwake) "最长 30 分钟自动结束；点击打开圆伴，或点操作立即结束" else if (apiActive) "点击打开圆伴，可立即撤销授权" else "后台保持连接会增加耗电；点击可停止会话")
            .setContentIntent(openApp)
            .setOngoing(true)
        if (keepAwake) {
            val stop = PendingIntent.getBroadcast(
                this, 1,
                Intent(OrbAccessibilityService.ACTION_STOP_KEEP_AWAKE).setPackage(packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(Notification.Action.Builder(
                android.R.drawable.ic_menu_close_clear_cancel, "结束亮屏", stop
            ).build())
        }
        return builder.build()
    }

    override fun onDestroy() {
        handler.removeCallbacks(renewWakeLock)
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }
}
