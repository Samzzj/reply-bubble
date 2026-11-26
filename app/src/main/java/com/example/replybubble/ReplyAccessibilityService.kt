package com.example.replybubble

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Display
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

        @Volatile
        var lastDiagnostic: String? = null
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
        val candidates = collectTargetRoots(preferredPackage = lastExternalPackage)
        if (candidates.isEmpty()) {
            val activePkg = rootInActiveWindow?.packageName?.toString().orEmpty().ifBlank { "null" }
            val winSummary = describeWindows()
            lastDiagnostic = "无障碍未拿到外部窗口（active=$activePkg, last=$lastExternalPackage, $winSummary）"
            return null
        }

        var bestFailure: ExtractResult? = null
        for (root in candidates) {
            runCatching { root.refresh() }
            val res = ScreenContextExtractor.extractWithDiagnostic(
                root = root,
                screenWidth = dm.widthPixels,
                screenHeight = dm.heightPixels,
                density = dm.density
            )
            if (res.snapshot != null) {
                lastDiagnostic = "ok:${res.snapshot.packageName}"
                return res.snapshot
            }
            if (bestFailure == null ||
                (res.packageName == lastExternalPackage && bestFailure.packageName != lastExternalPackage) ||
                res.totalNodes > bestFailure.totalNodes
            ) {
                bestFailure = res
            }
        }

        lastDiagnostic = bestFailure?.let { f ->
            "窗口=${f.packageName} · 节点=${f.totalNodes} · 文本=${f.rawTextNodes}+${f.rawDescNodes}（${f.reason}）"
        }
        return null
    }

    /** Android 11+ 通过无障碍截屏 + 离线中文 OCR 提取屏幕聊天内容 */
    fun captureScreenByOcr(onDone: (ScreenSnapshot?, String?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            onDone(null, "OCR兜底需Android 11及以上")
            return
        }
        val imeTop = findImeTop()
        val dm = resources.displayMetrics
        val pkgHint = lastExternalPackage?.takeIf { it.isNotBlank() } ?: "ocr.screen"
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        val hwBuffer = screenshot.hardwareBuffer
                        var softBitmap: Bitmap? = null
                        try {
                            val hwBitmap = Bitmap.wrapHardwareBuffer(hwBuffer, screenshot.colorSpace)
                            softBitmap = hwBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                            hwBitmap?.recycle()
                        } catch (e: Exception) {
                            onDone(null, "截屏转换失败：${e.message}")
                            return
                        } finally {
                            hwBuffer.close()
                        }

                        if (softBitmap == null) {
                            onDone(null, "截屏位图为空")
                            return
                        }

                        ScreenOcrHelper.recognize(softBitmap) { blocks, err ->
                            if (err != null) {
                                onDone(null, "OCR失败：$err")
                                return@recognize
                            }
                            val res = ScreenContextExtractor.extractFromOcrBlocks(
                                blocks = blocks,
                                packageName = pkgHint,
                                screenWidth = dm.widthPixels,
                                screenHeight = dm.heightPixels,
                                density = dm.density,
                                imeTop = imeTop
                            )
                            if (res.snapshot != null) {
                                lastDiagnostic = "ok:ocr($pkgHint)"
                                onDone(res.snapshot, null)
                            } else {
                                onDone(null, res.reason)
                            }
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        val msg = when (errorCode) {
                            ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS ->
                                "未授予截屏权限（请在系统无障碍设置中关闭再重开「回复球」）"
                            ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT ->
                                "截屏过于频繁，请 1 秒后再试"
                            ERROR_TAKE_SCREENSHOT_SECURE_WINDOW ->
                                "当前页面禁止截屏(FLAG_SECURE)"
                            else -> "截屏失败(code=$errorCode)"
                        }
                        onDone(null, msg)
                    }
                }
            )
        } catch (e: Exception) {
            onDone(null, "截屏异常：${e.message}")
        }
    }

    private fun findImeTop(): Int? {
        val ime = windows.orEmpty().firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            ?: return null
        val r = Rect()
        ime.getBoundsInScreen(r)
        return if (r.height() > 0) r.top else null
    }

    private fun describeWindows(): String {
        val list = windows.orEmpty()
        if (list.isEmpty()) return "windows=0"
        val items = list.joinToString(",") { w ->
            val pkg = runCatching { w.root?.packageName?.toString() }.getOrNull()
            "T${w.type}:${pkg ?: "nullRoot"}"
        }
        return "windows=${list.size}[$items]"
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

    private fun collectTargetRoots(preferredPackage: String?): List<AccessibilityNodeInfo> {
        val roots = ArrayList<AccessibilityNodeInfo>()
        val seenWindowIds = HashSet<Int>()

        fun addIfValid(node: AccessibilityNodeInfo?) {
            if (node == null) return
            val pkg = node.packageName?.toString().orEmpty()
            if (pkg.isBlank() || pkg == SELF_PKG || pkg == SYSUI_PKG) return
            if (seenWindowIds.add(node.windowId)) {
                roots.add(node)
            }
        }

        val active = rootInActiveWindow
        val activePkg = active?.packageName?.toString().orEmpty()
        if (preferredPackage.isNullOrBlank() || activePkg == preferredPackage) {
            addIfValid(active)
        }

        windows.orEmpty()
            .filter {
                it.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD &&
                    it.type != AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER &&
                    it.type != AccessibilityWindowInfo.TYPE_MAGNIFICATION_OVERLAY
            }
            .mapNotNull { w -> w.root?.let { r -> Triple(w, r, r.packageName?.toString().orEmpty()) } }
            .sortedWith(
                compareByDescending<Triple<AccessibilityWindowInfo, AccessibilityNodeInfo, String>> { (_, _, pkg) ->
                    !preferredPackage.isNullOrBlank() && pkg == preferredPackage
                }
                    .thenByDescending { (w, _, _) -> w.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                    .thenByDescending { (w, _, _) -> w.isFocused }
                    .thenByDescending { (w, _, _) -> w.isActive }
                    .thenByDescending { (w, _, _) -> w.layer }
            )
            .forEach { (_, r, _) -> addIfValid(r) }

        addIfValid(active)
        return roots
    }

    private fun findTargetRoot(preferredPackage: String?): AccessibilityNodeInfo? =
        collectTargetRoots(preferredPackage).firstOrNull()

    private fun findBestInputNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { focused ->
            if (isUsableInput(focused)) return focused
        }

        val candidates = ArrayList<Pair<AccessibilityNodeInfo, Rect>>()
        val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        stack.add(root to 0)
        while (stack.isNotEmpty()) {
            val (node, depth) = stack.removeLast()
            if (depth > 64) continue
            if (isUsableInput(node)) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                if (rect.width() > 0 && rect.height() > 0) {
                    candidates.add(node to rect)
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.add(it to depth + 1) }
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
