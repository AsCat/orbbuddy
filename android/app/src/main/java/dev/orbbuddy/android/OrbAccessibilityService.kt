package dev.orbbuddy.android

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.math.min

class OrbAccessibilityService : AccessibilityService() {
    companion object {
        const val ACTION_RELOAD = "dev.orbbuddy.android.RELOAD"
    }

    private val handler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder().pingInterval(25, TimeUnit.SECONDS).build()
    private var socket: WebSocket? = null
    private var receiverRegistered = false
    private var reconnectDelayMs = 1000L
    private var revision = 1L

    private val reloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_RELOAD) reconnect()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (!receiverRegistered) {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(reloadReceiver, IntentFilter(ACTION_RELOAD), RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(reloadReceiver, IntentFilter(ACTION_RELOAD))
            }
            receiverRegistered = true
        }
        reconnect()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event != null) revision++
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (receiverRegistered) unregisterReceiver(reloadReceiver)
        socket?.close(1000, "service stopped")
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        super.onDestroy()
    }

    private fun prefs() = getSharedPreferences("connection", MODE_PRIVATE)

    private fun setStatus(value: String) {
        prefs().edit().putString("status", value).apply()
    }

    private fun reconnect() {
        socket?.close(1000, "reconfigure")
        socket = null
        val prefs = prefs()
        if (!prefs.getBoolean("enabled", false)) {
            setStatus("已停止")
            return
        }
        val url = prefs.getString("url", "") ?: ""
        val token = prefs.getString("token", "") ?: ""
        if ((!url.startsWith("wss://") && !(BuildConfig.DEBUG && url.startsWith("ws://"))) || token.isEmpty()) {
            setStatus("等待配置")
            return
        }
        setStatus("连接中")
        val request = Request.Builder().url(url).header("Authorization", "Bearer $token").build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                handler.post {
                    if (socket === webSocket) {
                        reconnectDelayMs = 1000L
                        setStatus("已连接")
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handler.post { if (socket === webSocket) handleCommand(webSocket, text) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                handler.post { scheduleReconnect(webSocket) }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                handler.post { scheduleReconnect(webSocket) }
            }
        })
    }

    private fun scheduleReconnect(webSocket: WebSocket) {
        if (socket !== webSocket || !prefs().getBoolean("enabled", false)) return
        setStatus("断线，重连中")
        val delay = reconnectDelayMs
        reconnectDelayMs = min(reconnectDelayMs * 2, 30_000L)
        handler.postDelayed({ if (socket === webSocket) reconnect() }, delay)
    }

    private fun handleCommand(webSocket: WebSocket, text: String) {
        var id = ""
        try {
            val command = JSONObject(text)
            id = command.getString("id")
            val params = command.optJSONObject("params") ?: JSONObject()
            when (command.getString("method")) {
                "get_screen" -> getScreen(webSocket, id)
                "tap_element" -> tapElement(webSocket, id, params)
                "tap" -> gesture(webSocket, id, params.getInt("x"), params.getInt("y"), null, null, 60L)
                "swipe" -> gesture(webSocket, id, params.getInt("x1"), params.getInt("y1"),
                    params.getInt("x2"), params.getInt("y2"), params.getLong("durationMs"))
                "input_text" -> inputText(webSocket, id, params.getString("text"))
                "press_key" -> pressKey(webSocket, id, params.getString("key"))
                "get_app_info" -> getAppInfo(webSocket, id, params.getString("packageName"))
                else -> replyError(webSocket, id, "unknown method")
            }
        } catch (e: Exception) {
            if (id.isNotEmpty()) replyError(webSocket, id, e.message ?: "invalid command")
        }
    }

    private fun getScreen(webSocket: WebSocket, id: String) {
        val size = displaySize()
        val tree = rootInActiveWindow?.let { nodeTree(it, "0", intArrayOf(0), 0) } ?: JSONObject.NULL
        val result = JSONObject().put("revision", revision).put("width", size.x).put("height", size.y)
            .put("tree", tree)
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                try {
                    val buffer = screenshot.hardwareBuffer
                    val source = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                    buffer.close()
                    if (source == null) throw IllegalStateException("empty screenshot")
                    val software = source.copy(Bitmap.Config.ARGB_8888, false)
                        ?: throw IllegalStateException("cannot copy screenshot bitmap")
                    source.recycle()
                    val scale = min(1.0, 1280.0 / maxOf(software.width, software.height))
                    val width = (software.width * scale).toInt().coerceAtLeast(1)
                    val height = (software.height * scale).toInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    Canvas(bitmap).drawBitmap(software, null, Rect(0, 0, width, height), Paint(Paint.FILTER_BITMAP_FLAG))
                    software.recycle()
                    val bytes = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 70, bytes)
                    bitmap.recycle()
                    result.put("imageBase64", Base64.getEncoder().encodeToString(bytes.toByteArray()))
                } catch (e: Exception) {
                    result.put("imageError", e.message ?: "screenshot conversion failed")
                }
                replyOK(webSocket, id, result)
            }

            override fun onFailure(errorCode: Int) {
                result.put("imageError", "Android screenshot error $errorCode")
                replyOK(webSocket, id, result)
            }
        })
    }

    private fun nodeTree(node: AccessibilityNodeInfo, path: String, count: IntArray, depth: Int): JSONObject {
        count[0]++
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val json = JSONObject().put("path", path).put("className", node.className?.toString() ?: "")
            .put("packageName", node.packageName?.toString() ?: "")
            .put("text", node.text?.toString() ?: "")
            .put("description", node.contentDescription?.toString() ?: "")
            .put("viewId", node.viewIdResourceName ?: "")
            .put("clickable", node.isClickable).put("enabled", node.isEnabled)
            .put("editable", node.isEditable)
            .put("bounds", JSONObject().put("left", bounds.left).put("top", bounds.top)
                .put("right", bounds.right).put("bottom", bounds.bottom))
        val children = JSONArray()
        if (depth < 20 && count[0] < 300) {
            for (i in 0 until node.childCount) {
                if (count[0] >= 300) break
                val child = node.getChild(i) ?: continue
                children.put(nodeTree(child, "$path.$i", count, depth + 1))
            }
        }
        return json.put("children", children)
    }

    private fun tapElement(webSocket: WebSocket, id: String, params: JSONObject) {
        if (params.getLong("revision") != revision) {
            replyError(webSocket, id, "screen changed; call get_screen again")
            return
        }
        val path = params.getString("path")
        if (!path.matches(Regex("0(\\.[0-9]+)*"))) {
            replyError(webSocket, id, "invalid element path")
            return
        }
        var node = rootInActiveWindow
        for (part in path.split('.').drop(1)) node = node?.getChild(part.toInt())
        if (node == null || !node.isEnabled) {
            replyError(webSocket, id, "element unavailable; call get_screen again")
            return
        }
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            replyOK(webSocket, id, JSONObject().put("done", true))
            return
        }
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        gesture(webSocket, id, bounds.centerX(), bounds.centerY(), null, null, 60L)
    }

    private fun gesture(webSocket: WebSocket, id: String, x1: Int, y1: Int, x2: Int?, y2: Int?, duration: Long) {
        val size = displaySize()
        if (x1 !in 0 until size.x || y1 !in 0 until size.y ||
            (x2 != null && x2 !in 0 until size.x) || (y2 != null && y2 !in 0 until size.y) ||
            duration !in 50L..5000L) {
            replyError(webSocket, id, "invalid gesture coordinates or duration")
            return
        }
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            if (x2 != null && y2 != null) lineTo(x2.toFloat(), y2.toFloat())
        }
        val description = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build()
        val accepted = dispatchGesture(description, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                replyOK(webSocket, id, JSONObject().put("done", true))
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                replyError(webSocket, id, "gesture cancelled")
            }
        }, handler)
        if (!accepted) replyError(webSocket, id, "gesture rejected")
    }

    private fun inputText(webSocket: WebSocket, id: String, text: String) {
        if (text.length > 1000) {
            replyError(webSocket, id, "text too long")
            return
        }
        val field = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (field == null || !field.isEditable) {
            replyError(webSocket, id, "no focused editable field")
            return
        }
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            replyOK(webSocket, id, JSONObject().put("done", true))
        } else replyError(webSocket, id, "field rejected text input")
    }

    private fun pressKey(webSocket: WebSocket, id: String, key: String) {
        val action = when (key) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            else -> {
                replyError(webSocket, id, "key must be back or home")
                return
            }
        }
        if (performGlobalAction(action)) replyOK(webSocket, id, JSONObject().put("done", true))
        else replyError(webSocket, id, "global action failed")
    }

    private fun getAppInfo(webSocket: WebSocket, id: String, packageName: String) {
        try {
            @Suppress("DEPRECATION")
            val info = packageManager.getPackageInfo(packageName, 0)
            val result = JSONObject().put("packageName", packageName)
                .put("versionName", info.versionName ?: "")
                .put("versionCode", info.longVersionCode)
                .put("label", info.applicationInfo?.loadLabel(packageManager)?.toString() ?: "")
            replyOK(webSocket, id, result)
        } catch (_: PackageManager.NameNotFoundException) {
            replyError(webSocket, id, "package not found or hidden by Android package visibility")
        }
    }

    private fun displaySize(): Point {
        val display = (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay
        return Point().also { display.getRealSize(it) }
    }

    private fun replyOK(webSocket: WebSocket, id: String, result: JSONObject) {
        webSocket.send(JSONObject().put("id", id).put("ok", true).put("result", result).toString())
    }

    private fun replyError(webSocket: WebSocket, id: String, error: String) {
        webSocket.send(JSONObject().put("id", id).put("ok", false).put("error", error).toString())
    }
}
