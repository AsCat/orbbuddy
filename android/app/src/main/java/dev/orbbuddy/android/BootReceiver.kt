package dev.orbbuddy.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Restarts only a testing session the user left enabled before reboot or update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!context.getSharedPreferences("connection", Context.MODE_PRIVATE)
                .getBoolean("enabled", false)) return
        try {
            context.startForegroundService(Intent(context, SessionService::class.java))
        } catch (e: RuntimeException) {
            Log.w("OrbBuddy", "cannot restore testing session", e)
        }
    }
}
