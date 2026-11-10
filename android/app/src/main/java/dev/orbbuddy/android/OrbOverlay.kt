package dev.orbbuddy.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

/** An optional, locally controlled accessibility overlay. Codes still require server pairing. */
class OrbOverlay(
    private val service: OrbAccessibilityService,
    private val onPauseToggle: () -> Unit,
    private val onAPIRevoke: () -> Unit,
    private val onHide: () -> Unit,
    private val onPairCodeRequest: (String) -> PairingCode.Result,
    private val onWirelessDebugRequest: () -> Unit,
) {
    private val manager = service.getSystemService(WindowManager::class.java)
    private val prefs = service.getSharedPreferences("connection", Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())
    private val density = service.resources.displayMetrics.density
    private val size = dp(72)
    private val edge = dp(12)
    private val touchSlop = ViewConfiguration.get(service).scaledTouchSlop
    private var orb: OrbFaceView? = null
    private var orbParams: WindowManager.LayoutParams? = null
    private var menu: LinearLayout? = null
    private var menuStatus: TextView? = null
    private var pauseAction: TextView? = null
    private var codeLine: TextView? = null
    private var menuNotice = ""
    private var receiverRegistered = false
    private var captureHidden = false
    private var paused = false
    private var connectionStatus = "连接中"
    private var apiActive = false
    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    private var downAt = 0L
    private var dragging = false
    private val codeTicker = object : Runnable {
        override fun run() {
            if (menu == null) return
            refreshCodeLine()
            handler.postDelayed(this, 1000L)
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            orb?.invalidate()
        }
    }

    fun sync(show: Boolean, paused: Boolean, status: String, apiActive: Boolean) {
        this.paused = paused
        connectionStatus = status
        this.apiActive = apiActive
        if (show) show() else hide()
        orb?.apply {
            this.paused = paused
            this.connected = status == "已连接"
            this.character = OrbCharacter.from(prefs.getString("orb_character", null))
            contentDescription = "圆伴悬浮球，${if (paused) "已暂停" else if (apiActive) "HTTPS 远程访问中" else status}。点击打开会话控制，长按开启无线调试，拖动可移动"
            invalidate()
        }
        menuStatus?.text = statusLabel()
        pauseAction?.text = if (paused) "继续远程访问" else "暂停远程访问"
        refreshCodeLine()
    }

    fun hideForCapture() {
        if (orb != null) {
            hideMenu()
            captureHidden = true
            orb?.visibility = View.INVISIBLE
        }
    }

    fun restoreAfterCapture() {
        if (captureHidden) {
            captureHidden = false
            orb?.visibility = View.VISIBLE
        }
    }

    fun react(reaction: OrbReaction, direction: Float = 1f) {
        if (!paused && !captureHidden) orb?.react(reaction, direction)
    }

    fun showMessage(message: String) {
        Toast.makeText(service, message, Toast.LENGTH_LONG).show()
    }

    fun destroy() = hide()

    private fun statusLabel() = when {
        paused && apiActive -> "HTTPS 已授权 · 暂停中"
        paused -> "远程访问已暂停"
        apiActive -> "HTTPS 远程访问中"
        else -> "圆伴 · $connectionStatus"
    }

    private fun show() {
        if (orb != null) return
        val screen = displaySize()
        val x = prefs.getInt("orb_x", screen.x - size - edge).coerceIn(edge, (screen.x - size - edge).coerceAtLeast(edge))
        val y = prefs.getInt("orb_y", screen.y * 2 / 3).coerceIn(edge, (screen.y - size - edge).coerceAtLeast(edge))
        val params = layoutParams(size, size, x, y)
        val view = OrbFaceView(service).apply {
            character = OrbCharacter.from(prefs.getString("orb_character", null))
        }
        view.setOnTouchListener { _, event -> onTouch(event) }
        try {
            manager.addView(view, params)
            orb = view
            orbParams = params
            if (!receiverRegistered) {
                val filter = IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_SCREEN_OFF)
                }
                if (Build.VERSION.SDK_INT >= 33) {
                    service.registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    @Suppress("DEPRECATION")
                    service.registerReceiver(screenReceiver, filter)
                }
                receiverRegistered = true
            }
        } catch (e: RuntimeException) {
            Log.w("OrbBuddy", "cannot show floating orb", e)
        }
    }

    private fun hide() {
        hideMenu()
        orb?.let { runCatching { manager.removeView(it) } }
        orb = null
        orbParams = null
        captureHidden = false
        if (receiverRegistered) {
            runCatching { service.unregisterReceiver(screenReceiver) }
            receiverRegistered = false
        }
    }

    private fun onTouch(event: MotionEvent): Boolean {
        val params = orbParams ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                startX = params.x
                startY = params.y
                downAt = event.eventTime
                dragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(event.rawX - downX) > touchSlop || abs(event.rawY - downY) > touchSlop) dragging = true
                if (dragging) {
                    val screen = displaySize()
                    params.x = (startX + event.rawX - downX).toInt().coerceIn(0, (screen.x - size).coerceAtLeast(0))
                    params.y = (startY + event.rawY - downY).toInt().coerceIn(0, (screen.y - size).coerceAtLeast(0))
                    orb?.let { manager.updateViewLayout(it, params) }
                    hideMenu()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val endedAway = abs(event.rawX - downX) > touchSlop || abs(event.rawY - downY) > touchSlop
                if (dragging && endedAway && event.eventTime - downAt >= 100L) {
                    val screen = displaySize()
                    params.x = if (params.x + size / 2 < screen.x / 2) edge else screen.x - size - edge
                    orb?.let { manager.updateViewLayout(it, params) }
                    prefs.edit().putInt("orb_x", params.x).putInt("orb_y", params.y).apply()
                } else if (!dragging && !endedAway && event.eventTime - downAt >= ViewConfiguration.getLongPressTimeout()) {
                    onWirelessDebugRequest()
                } else {
                    params.x = startX
                    params.y = startY
                    orb?.let { manager.updateViewLayout(it, params) }
                    toggleMenu()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> return true
        }
        return false
    }

    private fun toggleMenu() {
        if (menu != null) {
            hideMenu()
            return
        }
        val params = orbParams ?: return
        val screen = displaySize()
        val width = dp(236)
        val x = (if (params.x < screen.x / 2) params.x else params.x + size - width)
            .coerceIn(0, (screen.x - width).coerceAtLeast(0))
        val hasRevokeAction = apiActive || PairingCode.active(service) != null
        val menuHeight = dp(if (hasRevokeAction) 413 else 365)
        val y = (if (params.y > menuHeight + dp(10)) params.y - menuHeight else params.y + size + dp(8))
            .coerceIn(0, (screen.y - menuHeight).coerceAtLeast(0))
        val panel = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(9), dp(14), dp(9))
            background = GradientDrawable().apply {
                setColor(Color.rgb(248, 252, 255))
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), Color.rgb(191, 228, 241))
            }
            elevation = dp(8).toFloat()
        }
        menuStatus = menuLine(statusLabel(), false).also(panel::addView)
        codeLine = TextView(service).apply {
            textSize = 20f
            setTextColor(Color.rgb(17, 80, 112))
            typeface = android.graphics.Typeface.MONOSPACE
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(45)
        }.also(panel::addView)
        refreshCodeLine()
        menuLine("生成只读授权码", true).also {
            it.setOnClickListener { generateCode("viewer") }
            panel.addView(it)
        }
        menuLine("生成测试控制码", true).also {
            it.setOnClickListener { generateCode("controller") }
            panel.addView(it)
        }
        menuLine("开启无线调试", true).also {
            it.setOnClickListener {
                hideMenu()
                onWirelessDebugRequest()
            }
            panel.addView(it)
        }
        pauseAction = menuLine(if (paused) "继续远程访问" else "暂停远程访问", true).also {
            it.setOnClickListener {
                hideMenu()
                onPauseToggle()
            }
            panel.addView(it)
        }
        if (hasRevokeAction) menuLine(if (apiActive) "撤销 HTTPS 授权" else "清除当前授权码", true).also {
            it.setOnClickListener {
                hideMenu()
                onAPIRevoke()
            }
            panel.addView(it)
        }
        menuLine("隐藏悬浮球", true).also {
            it.setOnClickListener {
                hideMenu()
                onHide()
            }
            panel.addView(it)
        }
        try {
            manager.addView(panel, layoutParams(width, WindowManager.LayoutParams.WRAP_CONTENT, x, y))
            menu = panel
            handler.postDelayed(codeTicker, 1000L)
        } catch (e: RuntimeException) {
            menuStatus = null
            pauseAction = null
            codeLine = null
            Log.w("OrbBuddy", "cannot show orb controls", e)
        }
    }

    private fun generateCode(scope: String) {
        hideMenu()
        val result = onPairCodeRequest(scope)
        menuNotice = if (result.code == null) result.message else ""
        toggleMenu()
    }

    private fun refreshCodeLine() {
        val active = PairingCode.active(service)
        codeLine?.textSize = if (active == null) 13f else 20f
        codeLine?.text = when {
            active != null -> "${if (active.scope == "controller") "控制" else "只读"} ${active.code} · ${active.secondsLeft} 秒"
            menuNotice.isNotEmpty() -> menuNotice
            else -> "当前无有效授权码"
        }
    }

    private fun hideMenu() {
        handler.removeCallbacks(codeTicker)
        menu?.let { runCatching { manager.removeView(it) } }
        menu = null
        menuStatus = null
        pauseAction = null
        codeLine = null
        menuNotice = ""
    }

    private fun menuLine(label: String, action: Boolean) = TextView(service).apply {
        text = label
        textSize = if (action) 16f else 13f
        setTextColor(if (action) Color.rgb(17, 80, 112) else Color.rgb(78, 108, 123))
        gravity = Gravity.CENTER_VERTICAL
        minHeight = dp(if (action) 48 else 34)
        if (action) {
            isClickable = true
            isFocusable = true
            contentDescription = label
        }
    }

    private fun layoutParams(width: Int, height: Int, x: Int, y: Int) = WindowManager.LayoutParams(
        width, height, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        this.x = x
        this.y = y
    }

    private fun displaySize() = Point().also { manager.defaultDisplay.getRealSize(it) }
    private fun dp(value: Int) = (value * density).toInt()

}
