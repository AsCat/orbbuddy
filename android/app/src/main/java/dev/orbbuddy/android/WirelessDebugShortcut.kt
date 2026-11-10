package dev.orbbuddy.android

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/** A phone-local, user-triggered recipe. It never routes system-setting taps through the relay. */
class WirelessDebugShortcut(
    private val service: AccessibilityService,
    private val handler: Handler,
    private val report: (String) -> Unit,
    private val beforeGesture: () -> Unit,
    private val afterGesture: () -> Unit,
) {
    private val experience = service.getSharedPreferences("shortcut_experience", Context.MODE_PRIVATE)
    private val endpointPattern = Regex("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}:[0-9]{2,5}")
    private var active = false
    private val selected = mutableMapOf<String, String>()

    fun start() {
        if (active) {
            report("无线调试快捷动作正在运行")
            return
        }
        active = true
        selected.clear()
        report("正在查找无线调试设置")
        if (!service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)) {
            fail("无法返回桌面")
            return
        }
        waitFor("桌面搜索", 6_000L) {
            val root = service.rootInActiveWindow ?: return@waitFor false
            val editor = find(root, "search") { it.isEditable }
            if (editor != null) {
                setSearchText(editor)
                return@waitFor true
            }
            val search = find(root, "search") {
                val label = subtreeText(it, 4)
                it.isClickable && label.contains("搜索") && label.length <= 24
            } ?: return@waitFor false
            click(search, "search") { waitForEditor() }
            true
        }
    }

    fun cancel() {
        active = false
    }

    private fun waitForEditor() {
        waitFor("搜索输入框", 5_000L) {
            val root = service.rootInActiveWindow ?: return@waitFor false
            val editor = find(root, "editor") { it.isEditable } ?: return@waitFor false
            setSearchText(editor)
            true
        }
    }

    private fun setSearchText(editor: AccessibilityNodeInfo) {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "无线调试")
        }
        if (!editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            fail("桌面搜索不接受输入")
            return
        }
        waitFor("无线调试搜索结果", 7_000L) {
            val root = service.rootInActiveWindow ?: return@waitFor false
            val result = find(root, "result") {
                it.isClickable && subtreeText(it, 12).contains("无线调试") &&
                    subtreeText(it, 12).contains("开发者选项")
            } ?: return@waitFor false
            click(result, "result") { waitForSettings() }
            true
        }
    }

    private fun waitForSettings() {
        waitFor("无线调试页面", 7_000L) {
            val root = service.rootInActiveWindow ?: return@waitFor false
            if (!isWirelessSettings(root)) return@waitFor false
            val endpoint = endpoint(root)
            if (endpoint != null) {
                succeed(endpoint)
                return@waitFor true
            }
            val switch = find(root, "switch") {
                (it.isCheckable || it.className?.toString()?.endsWith("Switch") == true) &&
                    (subtreeText(it, 8).contains("无线调试") ||
                        it.parent?.let { parent -> subtreeText(parent, 12).contains("无线调试") } == true)
            } ?: return@waitFor false
            if (switch.isChecked) {
                waitForEndpoint()
            } else {
                click(switch, "switch") { waitForEndpoint() }
            }
            true
        }
    }

    private fun waitForEndpoint() {
        waitFor("无线调试连接地址", 6_000L) {
            val root = service.rootInActiveWindow ?: return@waitFor false
            if (isWirelessSettings(root)) {
                endpoint(root)?.let {
                    succeed(it)
                    return@waitFor true
                }
            }
            if (!root.packageName?.toString().orEmpty().contains("settings", ignoreCase = true)) {
                return@waitFor false
            }
            val confirm = find(root, "confirm") {
                it.isClickable && localText(it).trim() in setOf("允许", "确定", "开启")
            }
            if (confirm != null) {
                click(confirm, "confirm") { waitForEndpointAfterConfirm() }
                return@waitFor true
            }
            false
        }
    }

    private fun waitForEndpointAfterConfirm() {
        waitFor("确认后的连接地址", 6_000L) {
            val root = service.rootInActiveWindow ?: return@waitFor false
            if (!isWirelessSettings(root)) return@waitFor false
            val endpoint = endpoint(root) ?: return@waitFor false
            succeed(endpoint)
            true
        }
    }

    private fun isWirelessSettings(root: AccessibilityNodeInfo): Boolean {
        val pkg = root.packageName?.toString().orEmpty()
        return pkg.contains("settings", ignoreCase = true) &&
            subtreeText(root, 100).contains("无线调试")
    }

    private fun endpoint(root: AccessibilityNodeInfo): String? =
        endpointPattern.find(subtreeText(root, 150))?.value

    private fun succeed(address: String) {
        if (!active) return
        active = false
        val edit = experience.edit()
        selected.forEach { (stage, id) -> edit.putString("wireless_$stage", id) }
        edit.putInt("wireless_successes", experience.getInt("wireless_successes", 0) + 1)
            .putString("wireless_last_endpoint", address).apply()
        report("无线调试已开启：$address")
    }

    private fun fail(stage: String) {
        if (!active) return
        Log.i("OrbBuddy", "wireless shortcut failed: $stage")
        active = false
        experience.edit().putInt("wireless_failures", experience.getInt("wireless_failures", 0) + 1).apply()
        runCatching {
            service.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        report("$stage；已打开开发者设置，请手动完成")
    }

    private fun waitFor(stage: String, timeoutMs: Long, step: () -> Boolean) {
        Log.i("OrbBuddy", "wireless shortcut waiting: $stage")
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        val task = object : Runnable {
            override fun run() {
                if (!active) return
                if (step()) return
                if (SystemClock.elapsedRealtime() >= deadline) fail("未找到$stage")
                else handler.postDelayed(this, 250L)
            }
        }
        handler.post(task)
    }

    private fun click(node: AccessibilityNodeInfo, stage: String, next: () -> Unit) {
        selected[stage] = node.viewIdResourceName.orEmpty()
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            next()
            return
        }
        val bounds = Rect().also(node::getBoundsInScreen)
        if (bounds.isEmpty) {
            fail("无法点击$stage")
            return
        }
        val path = Path().apply { moveTo(bounds.exactCenterX(), bounds.exactCenterY()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60L)).build()
        beforeGesture()
        val accepted = runCatching { service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                afterGesture()
                next()
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                afterGesture()
                fail("点击${stage}被取消")
            }
        }, handler) }.getOrDefault(false)
        if (!accepted) {
            afterGesture()
            fail("无法点击$stage")
        }
    }

    private fun find(root: AccessibilityNodeInfo, stage: String,
                     predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val matches = mutableListOf<AccessibilityNodeInfo>()
        var visited = 0
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 18 || ++visited > 500) return
            val rect = Rect().also(node::getBoundsInScreen)
            if (node.isEnabled && rect.width() > 0 && rect.height() > 0 && predicate(node)) matches.add(node)
            for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it, depth + 1) }
        }
        visit(root, 0)
        val learned = experience.getString("wireless_$stage", "").orEmpty()
        return matches.minWithOrNull(compareBy<AccessibilityNodeInfo> {
            if (learned.isNotEmpty() && it.viewIdResourceName == learned) 0 else 1
        }.thenBy {
            val rect = Rect().also(it::getBoundsInScreen)
            rect.width().toLong() * rect.height()
        })
    }

    private fun localText(node: AccessibilityNodeInfo) = listOfNotNull(
        node.text?.toString(), node.contentDescription?.toString()
    ).joinToString(" ")

    private fun subtreeText(node: AccessibilityNodeInfo, maxNodes: Int): String {
        val parts = mutableListOf<String>()
        var visited = 0
        fun visit(current: AccessibilityNodeInfo, depth: Int) {
            if (parts.size >= maxNodes || depth > 12 || ++visited > 500) return
            localText(current).takeIf { it.isNotBlank() }?.let(parts::add)
            for (i in 0 until current.childCount) current.getChild(i)?.let { visit(it, depth + 1) }
        }
        visit(node, 0)
        return parts.joinToString(" ")
    }
}
