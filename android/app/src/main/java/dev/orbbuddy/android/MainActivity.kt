package dev.orbbuddy.android

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.ScrollView
import java.security.SecureRandom

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var pauseButton: Button
    private lateinit var orbSwitch: Switch
    private lateinit var pairStatus: TextView
    private lateinit var apiStatus: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var refreshing = false
    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = refreshStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("connection", MODE_PRIVATE)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        fun add(view: android.view.View) {
            layout.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        add(TextView(this).apply {
            text = "圆伴 OrbBuddy · 开发测试会话"
            textSize = 22f
        })
        add(TextView(this).apply {
            text = "手机主动连接你的服务器。请先填写地址和设备令牌，再手动开启无障碍服务。"
        })
        val url = EditText(this).apply {
            hint = "wss://你的域名/device?id=primary"
            setText(prefs.getString("url", ""))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        add(url)
        val token = EditText(this).apply {
            hint = "设备令牌"
            setText(prefs.getString("token", ""))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        add(token)
        status = TextView(this)
        add(status)
        add(Button(this).apply {
            text = "保存并连接"
            setOnClickListener {
                val address = url.text.toString().trim()
                if (!address.startsWith("wss://") && !(BuildConfig.DEBUG && address.startsWith("ws://"))) {
                    status.text = "正式版需要 wss:// 地址"
                    return@setOnClickListener
                }
                if (token.text.isBlank()) {
                    status.text = "请填写设备令牌"
                    return@setOnClickListener
                }
                prefs.edit().putString("url", address).putString("token", token.text.toString())
                    .putBoolean("enabled", true).putBoolean("paused", false).apply()
                startSessionKeeper()
                if (Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
                }
                sendBroadcast(Intent(OrbAccessibilityService.ACTION_RELOAD).setPackage(packageName))
                refreshStatus()
            }
        })
        add(Button(this).apply {
            text = "打开无障碍设置"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        add(Button(this).apply {
            text = "后台连接与省电设置"
            setOnClickListener { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        })
        orbSwitch = Switch(this).apply {
            text = "显示圆伴卡通悬浮球"
            isChecked = prefs.getBoolean("orb_enabled", false)
            setOnCheckedChangeListener { _, checked ->
                if (refreshing) return@setOnCheckedChangeListener
                prefs.edit().putBoolean("orb_enabled", checked).apply()
                sendBroadcast(Intent(OrbAccessibilityService.ACTION_ORB_CHANGED).setPackage(packageName))
            }
        }
        add(orbSwitch)
        pauseButton = Button(this).apply {
            setOnClickListener {
                prefs.edit().putBoolean("paused", !prefs.getBoolean("paused", false)).apply()
                sendBroadcast(Intent(OrbAccessibilityService.ACTION_ORB_CHANGED).setPackage(packageName))
                refreshStatus()
            }
        }
        add(pauseButton)
        add(TextView(this).apply {
            text = "HTTPS 临时授权：4 位口令只用于 60 秒内配对，配对后的令牌空闲 5 分钟、最长 30 分钟失效。"
        })
        pairStatus = TextView(this)
        add(pairStatus)
        apiStatus = TextView(this)
        add(apiStatus)
        add(Button(this).apply {
            text = "生成只读口令（60 秒）"
            setOnClickListener { createPairCode("viewer", "") }
        })
        val controlPackage = EditText(this).apply {
            hint = "允许控制的可调试 App 包名"
            setText(prefs.getString("control_package", ""))
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        add(controlPackage)
        add(Button(this).apply {
            text = "生成开发测试控制口令（60 秒）"
            setOnClickListener {
                val packageName = controlPackage.text.toString().trim()
                try {
                    val info = packageManager.getApplicationInfo(packageName, 0)
                    if (info.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) {
                        pairStatus.text = "该 App 不是可调试版本，只能使用只读授权"
                        return@setOnClickListener
                    }
                } catch (_: PackageManager.NameNotFoundException) {
                    pairStatus.text = "未找到该 App；请填写已安装的可调试 App 包名"
                    return@setOnClickListener
                }
                prefs.edit().putString("control_package", packageName).apply()
                createPairCode("controller", packageName)
            }
        })
        add(Button(this).apply {
            text = "立即撤销 HTTPS 授权"
            setOnClickListener {
                prefs.edit().putBoolean("api_auth_active", false).remove("api_grant_id")
                    .remove("pair_code").remove("pair_scope").remove("pair_package").apply()
                sendBroadcast(Intent(OrbAccessibilityService.ACTION_LOCAL_REVOKE).setPackage(packageName))
                refreshStatus()
            }
        })
        add(Button(this).apply {
            text = "停止测试会话"
            setOnClickListener {
                prefs.edit().putBoolean("api_auth_active", false).remove("api_grant_id").remove("pair_code").apply()
                sendBroadcast(Intent(OrbAccessibilityService.ACTION_LOCAL_REVOKE).setPackage(packageName))
                prefs.edit().putBoolean("enabled", false).putBoolean("paused", false).apply()
                stopService(Intent(this@MainActivity, SessionService::class.java))
                sendBroadcast(Intent(OrbAccessibilityService.ACTION_RELOAD).setPackage(packageName))
                refreshStatus()
            }
        })
        setContentView(ScrollView(this).apply { addView(layout) })
    }

    override fun onResume() {
        super.onResume()
        if (getSharedPreferences("connection", MODE_PRIVATE).getBoolean("enabled", false)) startSessionKeeper()
        refreshStatus()
    }

    override fun onStart() {
        super.onStart()
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(stateReceiver, IntentFilter(OrbAccessibilityService.ACTION_STATE_CHANGED), RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(stateReceiver, IntentFilter(OrbAccessibilityService.ACTION_STATE_CHANGED))
        }
    }

    override fun onStop() {
        unregisterReceiver(stateReceiver)
        super.onStop()
    }

    private fun startSessionKeeper() {
        startForegroundService(Intent(this, SessionService::class.java))
    }

    private fun createPairCode(scope: String, targetPackage: String) {
        val prefs = getSharedPreferences("connection", MODE_PRIVATE)
        if (!prefs.getBoolean("enabled", false) || prefs.getString("status", "") != "已连接") {
            pairStatus.text = "请先让圆伴连接服务器"
            return
        }
        val code = SecureRandom().nextInt(10_000).toString().padStart(4, '0')
        prefs.edit().putString("pair_code", code).putString("pair_scope", scope)
            .putString("pair_package", targetPackage)
            .putLong("pair_started_elapsed", SystemClock.elapsedRealtime()).apply()
        refreshStatus()
        handler.postDelayed({ refreshStatus() }, 60_100L)
    }

    private fun refreshStatus() {
        val prefs = getSharedPreferences("connection", MODE_PRIVATE)
        refreshing = true
        orbSwitch.isChecked = prefs.getBoolean("orb_enabled", false)
        refreshing = false
        val enabled = prefs.getBoolean("enabled", false)
        val paused = prefs.getBoolean("paused", false)
        pauseButton.isEnabled = enabled
        pauseButton.text = if (paused) "继续远程访问" else "暂停远程访问"
        status.text = if (enabled) {
            val unrestricted = (getSystemService(POWER_SERVICE) as PowerManager)
                .isIgnoringBatteryOptimizations(packageName)
            "测试会话已开启。连接状态：${if (paused) "已暂停" else prefs.getString("status", "等待无障碍服务")}。" +
                if (unrestricted) "后台省电限制已关闭" else "若锁屏后断线，请在后台连接设置中允许持续运行"
        } else "测试会话已停止"
        val started = prefs.getLong("pair_started_elapsed", -1L)
        val code = prefs.getString("pair_code", "") ?: ""
        val age = SystemClock.elapsedRealtime() - started
        pairStatus.text = if (code.isNotEmpty() && started >= 0L && age in 0..59_999L) {
            "${if (prefs.getString("pair_scope", "") == "controller") "控制" else "只读"}口令：$code（剩余约 ${(60_000L - age + 999L) / 1000L} 秒）"
        } else "当前没有有效配对口令"
        val active = prefs.getBoolean("api_auth_active", false) &&
            System.currentTimeMillis() < prefs.getLong("api_auth_expires_at", 0L) &&
            System.currentTimeMillis() - prefs.getLong("api_last_used_at", 0L) < 5 * 60_000L
        apiStatus.text = if (active) {
            "HTTPS 远程访问中：${if (prefs.getString("api_auth_scope", "") == "controller") {
                if (prefs.getBoolean("api_control_suspended", false)) "控制已结束，仍可读屏；重新配对可继续" else "控制可调试 App"
            } else "只读"}。可随时点击撤销。"
        } else "没有已授权的 HTTPS 会话。${prefs.getString("api_auth_notice", "") ?: ""}"
    }
}
