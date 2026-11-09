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
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var pauseButton: Button
    private lateinit var orbSwitch: Switch
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
        add(Button(this).apply {
            text = "停止测试会话"
            setOnClickListener {
                prefs.edit().putBoolean("enabled", false).putBoolean("paused", false).apply()
                stopService(Intent(this@MainActivity, SessionService::class.java))
                sendBroadcast(Intent(OrbAccessibilityService.ACTION_RELOAD).setPackage(packageName))
                refreshStatus()
            }
        })
        setContentView(layout)
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
    }
}
