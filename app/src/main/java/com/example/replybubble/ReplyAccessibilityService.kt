package com.example.replybubble

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 无障碍服务：
 * 1. 点击悬浮球时读取当前聊天屏幕消息；
 * 2. 选择回复后等待原聊天窗口恢复焦点，直接将文本填入输入框。
 */
class ReplyAccessibilityService : AccessibilityService() {

    companion object {
        private const val SELF_PKG = "com.example.replybubble"
        private const val SYSUI_PKG = "com.android.systemui"

        @Volatile
        var instance: ReplyAccessibilityService? = null
            private set

        @Volatile
        var lastExternalPackage: String? = null
            private set

        fun isRunning(): Boolean = instance != null

        fun isEnabledInSettings(context: Context): Boolean {
            if (instance != null) return true
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
            val full = "${context.packageName}/${ReplyAccessibilityService::class.java.name}"
            val shortName = "${context.packageName}/.${ReplyAccessibilityService::class.java.simpleName}"
            return enabled.split(':').any {
                it.equals(full, ignoreCase = true) || it.equals(shortName, ignoreCase = true)
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var insertJob: Job? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString().orEmpty()
            if (pkg.isNotBlank() && pkg != SELF_PKG && pkg != SYSUI_PKG) {
                lastExternalPackage = pkg
            }
        }
    }

    override fun onInterrupt() {
        // 系统中断时无需额外处理
    }

    override fun onDestroy() {
        insertJob?.cancel()
        scope.cancel()
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }

    /** 抓取当前前台外部聊天窗口的屏幕内容 */
    fun captureCurrentScreen(): ScreenSnapshot? {
        val dm = resources.displayMetrics
        val root = findTargetRoot(preferredPackage = null) ?: return null
        return ScreenContextExtractor.extract(
            root = root,
            screenWidth = dm.widthPixels,
            screenHeight = dm.heightPixels,
            density = dm.density
        )
    }

    /**
     * 在 ReplyActivity 关闭后，等待原聊天窗口回到前台并直接填入文本。
     * 优先 ACTION_SET_TEXT，失败时降级为聚焦 + ACTION_PASTE；仍找不到输入框则复制到剪贴板兜底。
     */
    fun insertReply(text: String, preferredPackage: String?) {
        insertJob?.cancel()
        insertJob = scope.launch {
            delay(160L)
            repeat(15) {
                val root = findTargetRoot(preferredPackage)
                if (root != null) {
                    val input = findBestInputNode(root)
                    if (input != null && performInsert(input, text)) {
                        Toast.makeText(this@ReplyAccessibilityService, "已填入输入框", Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                }
                delay(100L)
            }
            copyFallback(text)
            Toast.makeText(
                this@ReplyAccessibilityService,
                "未检测到文字输入框，已复制到剪贴板",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private suspend fun performInsert(node: AccessibilityNodeInfo, text: String): Boolean {
        if (!node.isFocused) {
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        }

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            val sel = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, text.length)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, text.length)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
            return true
        }

        // 兜底：部分自定义输入框不支持 ACTION_SET_TEXT，改用剪贴板 + ACTION_PASTE
        copyFallback(text)
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        delay(80L)
        return node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }

    private fun copyFallback(text: String) {
        runCatching {
            val cm = getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newPlainText("reply", text))
            Prefs.setLastCopied(this, text)
        }
    }

    private fun findTargetRoot(preferredPackage: String?): AccessibilityNodeInfo? {
        val active = rootInActiveWindow
        val activePkg = active?.packageName?.toString().orEmpty()
        if (active != null && activePkg.isNotBlank() && activePkg != SELF_PKG && activePkg != SYSUI_PKG) {
            if (preferredPackage.isNullOrBlank() || activePkg == preferredPackage) {
                return active
            }
        }

        val appRoots = windows.orEmpty()
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .sortedWith(
                compareByDescending<AccessibilityWindowInfo> { it.isFocused }
                    .thenByDescending { it.isActive }
                    .thenByDescending { it.layer }
            )
            .mapNotNull { it.root }
            .filter { r ->
                val p = r.packageName?.toString().orEmpty()
                p.isNotBlank() && p != SELF_PKG && p != SYSUI_PKG
            }

        if (!preferredPackage.isNullOrBlank()) {
            appRoots.firstOrNull { it.packageName?.toString() == preferredPackage }?.let { return it }
        }
        return appRoots.firstOrNull() ?: if (activePkg != SELF_PKG && activePkg != SYSUI_PKG) active else null
    }

    private fun findBestInputNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { focused ->
            if (isUsableInput(focused)) return focused
        }

        val candidates = ArrayList<Pair<AccessibilityNodeInfo, Rect>>()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            if (!node.isVisibleToUser) continue
            if (isUsableInput(node)) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                if (rect.width() > 0 && rect.height() > 0) {
                    candidates.add(node to rect)
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.add(it) }
            }
        }

        if (candidates.isEmpty()) return null
        return candidates.firstOrNull { it.first.isFocused }?.first
            ?: candidates.maxByOrNull { it.second.bottom }?.first
    }

    private fun isUsableInput(node: AccessibilityNodeInfo): Boolean {
        if (!node.isVisibleToUser || !node.isEnabled) return false
        if (node.isEditable) return true
        return node.className?.toString()?.contains("EditText", ignoreCase = true) == true
    }
}
