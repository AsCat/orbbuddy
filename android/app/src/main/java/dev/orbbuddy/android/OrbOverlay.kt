package dev.orbbuddy.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.drawable.GradientDrawable
import android.os.PowerManager
import android.os.SystemClock
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.sin

/** An optional, locally controlled accessibility overlay. It never grants remote access itself. */
class OrbOverlay(
    private val service: OrbAccessibilityService,
    private val onPauseToggle: () -> Unit,
    private val onHide: () -> Unit,
) {
    private val manager = service.getSystemService(WindowManager::class.java)
    private val power = service.getSystemService(PowerManager::class.java)
    private val prefs = service.getSharedPreferences("connection", Context.MODE_PRIVATE)
    private val density = service.resources.displayMetrics.density
    private val size = dp(72)
    private val edge = dp(12)
    private val touchSlop = ViewConfiguration.get(service).scaledTouchSlop
    private var orb: FaceView? = null
    private var orbParams: WindowManager.LayoutParams? = null
    private var menu: LinearLayout? = null
    private var menuStatus: TextView? = null
    private var pauseAction: TextView? = null
    private var receiverRegistered = false
    private var captureHidden = false
    private var paused = false
    private var connectionStatus = "连接中"
    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    private var downAt = 0L
    private var dragging = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            orb?.invalidate()
        }
    }

    fun sync(show: Boolean, paused: Boolean, status: String) {
        this.paused = paused
        connectionStatus = status
        if (show) show() else hide()
        orb?.apply {
            this.paused = paused
            this.connected = status == "已连接"
            contentDescription = "圆伴悬浮球，${if (paused) "已暂停" else status}。点击打开会话控制，拖动可移动"
            invalidate()
        }
        menuStatus?.text = if (paused) "远程访问已暂停" else "圆伴 · $status"
        pauseAction?.text = if (paused) "继续远程访问" else "暂停远程访问"
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

    fun destroy() = hide()

    private fun show() {
        if (orb != null) return
        val screen = displaySize()
        val x = prefs.getInt("orb_x", screen.x - size - edge).coerceIn(edge, (screen.x - size - edge).coerceAtLeast(edge))
        val y = prefs.getInt("orb_y", screen.y * 2 / 3).coerceIn(edge, (screen.y - size - edge).coerceAtLeast(edge))
        val params = layoutParams(size, size, x, y)
        val view = FaceView(service)
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
        val width = dp(188)
        val x = if (params.x < screen.x / 2) params.x else params.x + size - width
        val y = if (params.y > dp(158)) params.y - dp(148) else params.y + size + dp(8)
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
        menuStatus = menuLine(if (paused) "远程访问已暂停" else "圆伴 · $connectionStatus", false).also(panel::addView)
        pauseAction = menuLine(if (paused) "继续远程访问" else "暂停远程访问", true).also {
            it.setOnClickListener {
                hideMenu()
                onPauseToggle()
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
        } catch (e: RuntimeException) {
            menuStatus = null
            pauseAction = null
            Log.w("OrbBuddy", "cannot show orb controls", e)
        }
    }

    private fun hideMenu() {
        menu?.let { runCatching { manager.removeView(it) } }
        menu = null
        menuStatus = null
        pauseAction = null
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

    private inner class FaceView(context: Context) : View(context) {
        var paused = false
        var connected = false
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val now = SystemClock.uptimeMillis()
            val breath = if (paused) 1f else (1f + 0.025f * sin(now / 610.0).toFloat())
            canvas.save()
            canvas.scale(width / 72f, height / 72f)
            canvas.scale(breath, breath, 36f, 36f)
            paint.style = Paint.Style.FILL
            paint.color = if (paused) Color.rgb(165, 179, 190) else Color.rgb(69, 194, 241)
            canvas.drawCircle(36f, 36f, 31f, paint)
            paint.color = if (paused) Color.rgb(197, 207, 215) else Color.rgb(135, 224, 250)
            canvas.drawCircle(28f, 22f, 12f, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3f
            paint.color = when {
                paused -> Color.rgb(115, 125, 137)
                connected -> Color.rgb(49, 173, 107)
                else -> Color.rgb(242, 170, 65)
            }
            canvas.drawCircle(36f, 36f, 33f, paint)
            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(27, 72, 105)
            val blinking = !paused && now % 4400L < 150L
            if (blinking) {
                canvas.drawRoundRect(21f, 32f, 29f, 34f, 2f, 2f, paint)
                canvas.drawRoundRect(43f, 32f, 51f, 34f, 2f, 2f, paint)
            } else {
                canvas.drawCircle(25f, 32f, 3.5f, paint)
                canvas.drawCircle(47f, 32f, 3.5f, paint)
            }
            paint.color = Color.rgb(255, 144, 157)
            canvas.drawCircle(17f, 41f, 4f, paint)
            canvas.drawCircle(55f, 41f, 4f, paint)
            paint.color = Color.rgb(27, 72, 105)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2.4f
            canvas.drawArc(29f, 35f, 43f, 49f, 20f, 140f, false, paint)
            canvas.restore()
            if (!paused && power.isInteractive && isAttachedToWindow) postInvalidateDelayed(50L)
        }
    }
}
