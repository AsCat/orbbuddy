package dev.orbbuddy.android

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
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
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
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
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.math.min

class OrbAccessibilityService : AccessibilityService() {
    companion object {
        const val ACTION_RELOAD = "dev.orbbuddy.android.RELOAD"
        const val ACTION_ORB_CHANGED = "dev.orbbuddy.android.ORB_CHANGED"
        const val ACTION_STATE_CHANGED = "dev.orbbuddy.android.STATE_CHANGED"
        const val ACTION_LOCAL_REVOKE = "dev.orbbuddy.android.LOCAL_REVOKE"
    }

    private val handler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder().pingInterval(25, TimeUnit.SECONDS).build()
    private var socket: WebSocket? = null
    private var receiverRegistered = false
    private var networkCallbackRegistered = false
    private var sawDefaultNetwork = false
    private var reconnectDelayMs = 1000L
    private var revision = 1L
    private var lastServerMessageAt = 0L
    private var watchdogStarted = false
    private var destroyed = false
    private var overlay: OrbOverlay? = null
    private var wirelessShortcut: WirelessDebugShortcut? = null

    private val watchdog = object : Runnable {
        override fun run() {
            if (prefs().getBoolean("enabled", false) && socket != null &&
                SystemClock.elapsedRealtime() - lastServerMessageAt > 75_000L) {
                Log.w("OrbBuddy", "server heartbeat missing; reconnecting")
                reconnect()
            }
            handler.postDelayed(this, 30_000L)
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            // The first callback describes the network already used by reconnect().
            if (sawDefaultNetwork || socket == null) reconnect()
            sawDefaultNetwork = true
        }

        override fun onLost(network: Network) {
            reconnect()
        }
    }

    private val reloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_RELOAD) reconnect()
            if (intent?.action == ACTION_ORB_CHANGED) syncOverlay()
            if (intent?.action == ACTION_LOCAL_REVOKE) {
                socket?.send(JSONObject().put("event", "auth_revoke").toString())
                syncOverlay()
                notifyStateChanged()
                refreshSessionNotification()
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i("OrbBuddy", "accessibility service connected")
        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(ACTION_RELOAD)
                addAction(ACTION_ORB_CHANGED)
                addAction(ACTION_LOCAL_REVOKE)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(reloadReceiver, filter, RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(reloadReceiver, filter)
            }
            receiverRegistered = true
        }
        if (overlay == null) {
            wirelessShortcut = WirelessDebugShortcut(this, handler,
                report = { overlay?.showMessage(it) },
                beforeGesture = { overlay?.hideForCapture() },
                afterGesture = { overlay?.restoreAfterCapture() })
            overlay = OrbOverlay(this, onPauseToggle = {
                val paused = !prefs().getBoolean("paused", false)
                prefs().edit().putBoolean("paused", paused).apply()
                syncOverlay()
                notifyStateChanged()
            }, onAPIRevoke = {
                clearAPIGrant()
                socket?.send(JSONObject().put("event", "auth_revoke").toString())
            }, onHide = {
                prefs().edit().putBoolean("orb_enabled", false).apply()
                syncOverlay()
                notifyStateChanged()
            }, onPairCodeRequest = { scope ->
                val target = if (scope == "controller") prefs().getString("control_package", "") ?: "" else ""
                val result = PairingCode.create(this, scope, target)
                notifyStateChanged()
                result
            }, onWirelessDebugRequest = { wirelessShortcut?.start() })
        }
        syncOverlay()
        reconnect()
        if (!networkCallbackRegistered) {
            (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager)
                .registerDefaultNetworkCallback(networkCallback, handler)
            networkCallbackRegistered = true
        }
        if (!watchdogStarted) {
            handler.postDelayed(watchdog, 30_000L)
            watchdogStarted = true
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Showing or hiding our own orb must not invalidate paths in the target app.
        if (event != null && event.packageName?.toString() != packageName) revision++
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        Log.i("OrbBuddy", "accessibility service destroyed")
        destroyed = true
        val oldSocket = socket
        socket = null
        overlay?.destroy()
        overlay = null
        wirelessShortcut?.cancel()
        wirelessShortcut = null
        if (receiverRegistered) unregisterReceiver(reloadReceiver)
        handler.removeCallbacksAndMessages(null)
        if (networkCallbackRegistered) {
            (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager)
                .unregisterNetworkCallback(networkCallback)
        }
        oldSocket?.close(1000, "service stopped")
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        super.onDestroy()
    }

    private fun prefs() = getSharedPreferences("connection", MODE_PRIVATE)

    private fun setStatus(value: String) {
        prefs().edit().putString("status", value).apply()
        syncOverlay()
        notifyStateChanged()
    }

    private fun notifyStateChanged() {
        sendBroadcast(Intent(ACTION_STATE_CHANGED).setPackage(packageName))
    }

    private fun syncOverlay() {
        val settings = prefs()
        overlay?.sync(
            settings.getBoolean("enabled", false) && settings.getBoolean("orb_enabled", false),
            settings.getBoolean("paused", false),
            settings.getString("status", "连接中") ?: "连接中",
            settings.getBoolean("api_auth_active", false) &&
                System.currentTimeMillis() < settings.getLong("api_auth_expires_at", 0L) &&
                System.currentTimeMillis() - settings.getLong("api_last_used_at", 0L) < 5 * 60_000L
        )
    }

    private fun reconnect() {
        if (destroyed) return
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
        lastServerMessageAt = SystemClock.elapsedRealtime()
        val request = Request.Builder().url(url).header("Authorization", "Bearer $token").build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                handler.post {
                    if (socket === webSocket) {
                        reconnectDelayMs = 1000L
                        lastServerMessageAt = SystemClock.elapsedRealtime()
                        // HTTP API grants belong to one relay connection.
                        prefs().edit().putBoolean("api_auth_active", false).remove("api_grant_id").apply()
                        refreshSessionNotification()
                        Log.i("OrbBuddy", "device socket connected")
                        setStatus("已连接")
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handler.post {
                    if (socket === webSocket) {
                        lastServerMessageAt = SystemClock.elapsedRealtime()
                        handleCommand(webSocket, text)
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w("OrbBuddy", "device socket failed: ${t.message}")
                handler.post { scheduleReconnect(webSocket) }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i("OrbBuddy", "device socket closed: $code")
                handler.post { scheduleReconnect(webSocket) }
            }
        })
    }

    private fun scheduleReconnect(webSocket: WebSocket) {
        if (destroyed || socket !== webSocket || !prefs().getBoolean("enabled", false)) return
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
            val method = command.getString("method")
            if (method != "heartbeat" && method != "auth_revoke" && method != "auth_lockout" && prefs().getBoolean("paused", false)) {
                replyError(webSocket, id, "remote access is paused on the phone")
                return
            }
            val apiGrantId = params.optString("apiGrantId", "")
            if (apiGrantId.isNotEmpty() && !validAPIGrant(apiGrantId)) {
                replyError(webSocket, id, "remote API authorization revoked")
                return
            }
            if (apiGrantId.isNotEmpty() && method in listOf("tap", "swipe", "press_key") && !controlPackageAllowed()) {
                replyError(webSocket, id, "control is limited to the authorized debuggable app")
                return
            }
            when (method) {
                "heartbeat" -> replyOK(webSocket, id, JSONObject().put("alive", true)
                    .put("paused", prefs().getBoolean("paused", false))
                    .put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL))
                "auth_pair" -> pairAPI(webSocket, id, params.optString("code", ""))
                "auth_lockout" -> {
                    prefs().edit().putString("api_auth_notice", "配对错误过多，请稍后再试").apply()
                    notifyStateChanged()
                    replyOK(webSocket, id, JSONObject().put("done", true))
                }
                "auth_revoke" -> {
                    clearAPIGrant()
                    replyOK(webSocket, id, JSONObject().put("done", true))
                }
                "get_screen" -> getScreen(webSocket, id, apiGrantId.ifEmpty { null }, params)
                "get_lock_state" -> replyOK(webSocket, id, lockState())
                "wake_screen" -> wakeScreen(webSocket, id)
                "tap_element" -> tapElement(webSocket, id, params)
                "tap" -> gesture(webSocket, id, params.getInt("x"), params.getInt("y"), null, null, 60L)
                "swipe" -> gesture(webSocket, id, params.getInt("x1"), params.getInt("y1"),
                    params.getInt("x2"), params.getInt("y2"), params.getLong("durationMs"))
                "input_text" -> inputText(webSocket, id, params.getString("text"))
                "press_key" -> pressKey(webSocket, id, params.getString("key"), apiGrantId.isNotEmpty())
                "get_app_info" -> getAppInfo(webSocket, id, params.getString("packageName"))
                "open_wireless_debugging_settings" -> openWirelessDebuggingSettings(webSocket, id)
                else -> replyError(webSocket, id, "unknown method")
            }
        } catch (e: Exception) {
            if (id.isNotEmpty()) replyError(webSocket, id, e.message ?: "invalid command")
        }
    }

    private fun validAPIGrant(grantId: String): Boolean {
        val settings = prefs()
        val valid = settings.getBoolean("api_auth_active", false) &&
            settings.getString("api_grant_id", "") == grantId &&
            System.currentTimeMillis() < settings.getLong("api_auth_expires_at", 0L) &&
            System.currentTimeMillis() - settings.getLong("api_last_used_at", 0L) < 5 * 60_000L
        if (valid) settings.edit().putLong("api_last_used_at", System.currentTimeMillis()).apply()
        return valid
    }

    private fun controlPackageAllowed(): Boolean {
        val settings = prefs()
        if (settings.getString("api_auth_scope", "") != "controller") return false
        if (settings.getBoolean("api_control_suspended", false)) return false
        val allowed = settings.getString("api_auth_package", "") ?: ""
        return allowed.isNotEmpty() && rootInActiveWindow?.packageName?.toString() == allowed
    }

    private fun pairAPI(webSocket: WebSocket, id: String, code: String) {
        val settings = prefs()
        val started = settings.getLong("pair_started_elapsed", -1L)
        val elapsed = SystemClock.elapsedRealtime()
        val expected = settings.getString("pair_code", "") ?: ""
        if (code.length != 4 || code != expected || started < 0L || elapsed < started || elapsed - started >= 60_000L) {
            replyError(webSocket, id, "invalid or expired pairing code")
            return
        }
        val scope = settings.getString("pair_scope", "") ?: ""
        val allowedPackage = settings.getString("pair_package", "") ?: ""
        if (scope != "viewer" && scope != "controller") {
            replyError(webSocket, id, "pairing scope unavailable")
            return
        }
        if (scope == "controller") {
            try {
                val info = packageManager.getApplicationInfo(allowedPackage, 0)
                if (info.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) {
                    replyError(webSocket, id, "control requires a debuggable app")
                    return
                }
            } catch (_: PackageManager.NameNotFoundException) {
                replyError(webSocket, id, "debuggable app not found")
                return
            }
        }
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        val grantId = bytes.joinToString("") { "%02x".format(it) }
        settings.edit().remove("pair_code").remove("pair_scope").remove("pair_package")
            .putBoolean("api_auth_active", true).putString("api_grant_id", grantId)
            .putString("api_auth_scope", scope).putString("api_auth_package", allowedPackage)
            .putBoolean("api_control_suspended", false)
            .putLong("api_auth_expires_at", System.currentTimeMillis() + 30 * 60_000L)
            .putLong("api_last_used_at", System.currentTimeMillis())
            .remove("api_auth_notice").apply()
        syncOverlay()
        notifyStateChanged()
        refreshSessionNotification()
        replyOK(webSocket, id, JSONObject().put("scope", scope).put("grantId", grantId)
            .put("allowedPackage", allowedPackage))
    }

    private fun clearAPIGrant() {
        prefs().edit().putBoolean("api_auth_active", false).remove("api_grant_id")
            .putBoolean("api_control_suspended", false)
            .remove("pair_code").remove("pair_scope").remove("pair_package").apply()
        syncOverlay()
        notifyStateChanged()
        refreshSessionNotification()
    }

    private fun refreshSessionNotification() {
        try {
            startService(Intent(this, SessionService::class.java))
        } catch (e: RuntimeException) {
            Log.w("OrbBuddy", "could not refresh session notification: ${e.message}")
        }
    }

    private fun getScreen(webSocket: WebSocket, id: String, apiGrantId: String?, params: JSONObject) {
        val includeImage = params.optBoolean("includeImage", true)
        val maxImageEdge = params.optInt("maxImageEdge", 1280)
        if (includeImage && maxImageEdge !in 320..1280) {
            replyError(webSocket, id, "maxImageEdge must be 320..1280")
            return
        }
        val orb = overlay
        if (includeImage) orb?.hideForCapture()
        handler.postDelayed({
            if (destroyed) return@postDelayed
            try {
                val size = displaySize()
                val tree = rootInActiveWindow?.let { nodeTree(it, "0", intArrayOf(0), 0) } ?: JSONObject.NULL
                val result = JSONObject().put("revision", revision).put("width", size.x).put("height", size.y)
                    .put("tree", tree)
                fun complete() {
                    if (includeImage) orb?.restoreAfterCapture()
                    if (prefs().getBoolean("paused", false)) replyError(webSocket, id, "remote access is paused on the phone")
                    else if (apiGrantId != null && !validAPIGrant(apiGrantId)) replyError(webSocket, id, "remote API authorization revoked")
                    else {
                        if (result.has("imageBase64")) orb?.react(OrbReaction.LOOK)
                        replyOK(webSocket, id, result)
                    }
                }
                if (!includeImage) {
                    complete()
                    return@postDelayed
                }
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
                            val scale = min(1.0, maxImageEdge.toDouble() / maxOf(software.width, software.height))
                            val width = (software.width * scale).toInt().coerceAtLeast(1)
                            val height = (software.height * scale).toInt().coerceAtLeast(1)
                            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                            Canvas(bitmap).drawBitmap(software, null, Rect(0, 0, width, height), Paint(Paint.FILTER_BITMAP_FLAG))
                            software.recycle()
                            val bytes = ByteArrayOutputStream()
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 70, bytes)
                            bitmap.recycle()
                            result.put("imageBase64", Base64.getEncoder().encodeToString(bytes.toByteArray()))
                            result.put("capturedAt", Instant.now().toString())
                        } catch (e: Exception) {
                            result.put("imageError", e.message ?: "screenshot conversion failed")
                        }
                        complete()
                    }

                    override fun onFailure(errorCode: Int) {
                        result.put("imageError", "Android screenshot error $errorCode")
                        complete()
                    }
                })
            } catch (e: RuntimeException) {
                if (includeImage) orb?.restoreAfterCapture()
                replyError(webSocket, id, e.message ?: "screenshot failed")
            }
        }, if (orb == null || !includeImage) 0L else 50L)
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
            .put("checkable", node.isCheckable).put("checked", node.isChecked)
            .put("stateDescription", node.stateDescription?.toString() ?: "")
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
            overlay?.react(OrbReaction.TAP)
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
        val orb = overlay
        orb?.hideForCapture()
        val accepted = try {
            dispatchGesture(description, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    orb?.restoreAfterCapture()
                    if (x2 != null && y2 != null) {
                        val direction = if (kotlin.math.abs(x2 - x1) >= kotlin.math.abs(y2 - y1)) x2 - x1 else y2 - y1
                        orb?.react(OrbReaction.SWIPE, direction.toFloat())
                    } else orb?.react(OrbReaction.TAP)
                    replyOK(webSocket, id, JSONObject().put("done", true))
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    orb?.restoreAfterCapture()
                    replyError(webSocket, id, "gesture cancelled")
                }
            }, handler)
        } catch (e: RuntimeException) {
            orb?.restoreAfterCapture()
            throw e
        }
        if (!accepted) {
            orb?.restoreAfterCapture()
            replyError(webSocket, id, "gesture rejected")
        }
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
            overlay?.react(OrbReaction.TAP)
            replyOK(webSocket, id, JSONObject().put("done", true))
        } else replyError(webSocket, id, "field rejected text input")
    }

    private fun openWirelessDebuggingSettings(webSocket: WebSocket, id: String) {
        val intent = Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }
            .recoverCatching {
                startActivity(
                    Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            .onSuccess { replyOK(webSocket, id, JSONObject().put("opened", true)) }
            .onFailure { replyError(webSocket, id, it.message ?: "wireless debugging settings unavailable") }
    }

    private fun lockState(): JSONObject {
        val power = getSystemService(PowerManager::class.java)
        val keyguard = getSystemService(KeyguardManager::class.java)
        return JSONObject()
            .put("interactive", power.isInteractive)
            .put("keyguardShowing", keyguard.isKeyguardLocked)
            .put("deviceLocked", keyguard.isDeviceLocked)
            .put("deviceSecure", keyguard.isDeviceSecure)
    }

    private fun wakeScreen(webSocket: WebSocket, id: String) {
        val power = getSystemService(PowerManager::class.java)
        if (power.isInteractive) {
            replyOK(webSocket, id, lockState().put("wakeRequested", false))
            return
        }
        try {
            // A remote accessibility service cannot rely on starting a background Activity.
            // Keep this deprecated wake flag isolated and time bounded for current Android devices.
            @Suppress("DEPRECATION")
            val wakeLock = power.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "OrbBuddy:WakeScreen"
            )
            wakeLock.acquire(3_000L)
            handler.postDelayed({ if (wakeLock.isHeld) wakeLock.release() }, 1_500L)
            handler.postDelayed({
                replyOK(webSocket, id, lockState().put("wakeRequested", true))
            }, 750L)
        } catch (e: RuntimeException) {
            replyError(webSocket, id, e.message ?: "screen wake failed")
        }
    }

    private fun pressKey(webSocket: WebSocket, id: String, key: String, apiControl: Boolean) {
        val action = when (key) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            else -> {
                replyError(webSocket, id, "key must be back or home")
                return
            }
        }
        if (performGlobalAction(action)) {
            if (apiControl) prefs().edit().putBoolean("api_control_suspended", true).apply()
            overlay?.react(OrbReaction.SWIPE, -1f)
            replyOK(webSocket, id, JSONObject().put("done", true))
        }
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
