package dev.orbbuddy.android

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.SystemClock
import java.security.SecureRandom

/** Codes are created only by a local tap, never by a relay command. */
object PairingCode {
    data class Result(val code: String? = null, val message: String)
    data class Active(val code: String, val scope: String, val secondsLeft: Long)

    fun create(context: Context, scope: String, targetPackage: String = ""): Result {
        val prefs = context.getSharedPreferences("connection", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("enabled", false) || prefs.getString("status", "") != "已连接") {
            return Result(message = "请先让圆伴连接服务器")
        }
        if (prefs.getBoolean("paused", false)) return Result(message = "请先恢复远程访问")
        if (scope == "controller") {
            if (targetPackage.isBlank()) return Result(message = "请先在圆伴主界面填写可调试 App 包名")
            try {
                val info = context.packageManager.getApplicationInfo(targetPackage, 0)
                if (info.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) {
                    return Result(message = "该 App 不是可调试版本，只能使用只读授权")
                }
            } catch (_: PackageManager.NameNotFoundException) {
                return Result(message = "未找到该 App；请在圆伴主界面设置包名")
            }
        } else if (scope != "viewer") {
            return Result(message = "不支持的授权范围")
        }
        val code = SecureRandom().nextInt(10_000).toString().padStart(4, '0')
        prefs.edit().putString("pair_code", code).putString("pair_scope", scope)
            .putString("pair_package", targetPackage)
            .putLong("pair_started_elapsed", SystemClock.elapsedRealtime()).apply()
        return Result(code, if (scope == "controller") "控制口令已生成" else "只读口令已生成")
    }

    fun active(context: Context): Active? {
        val prefs = context.getSharedPreferences("connection", Context.MODE_PRIVATE)
        val code = prefs.getString("pair_code", "") ?: ""
        val started = prefs.getLong("pair_started_elapsed", -1L)
        val age = SystemClock.elapsedRealtime() - started
        if (code.length != 4 || started < 0L || age !in 0..59_999L) return null
        return Active(code, prefs.getString("pair_scope", "viewer") ?: "viewer", (60_000L - age + 999L) / 1000L)
    }
}
