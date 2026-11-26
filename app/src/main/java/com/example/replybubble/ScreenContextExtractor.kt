package com.example.replybubble

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.abs

data class ScreenSnapshot(
    val packageName: String,
    val parsed: ParsedContext,
    val lineCount: Int
)

/**
 * 从当前聊天窗口的无障碍节点树中提取对话内容。
 * 按屏幕左右位置区分「对方（左）」和「我（右）」，过滤顶部栏、底部输入栏和时间戳。
 */
object ScreenContextExtractor {

    private data class CapturedNode(
        val text: String,
        val rect: Rect
    )

    private val TIME_ONLY = Regex(
        """^(\d{1,2}:\d{2}|(昨天|前天|今天|星期[一二三四五六日天]|周[一二三四五六日天]|上午|下午|晚上|凌晨)\s*\d{0,2}:?\d{0,2}|刚刚|\d+分钟前|\d{4}[-/年]\d{1,2}[-/月]\d{1,2}日?(\s+\d{1,2}:\d{2})?)$"""
    )

    private val PURE_DIGITS = Regex("""^\d{1,4}\+?$""")

    private val NOISE_EXACT = setOf(
        "发送", "按住 说话", "按住说话", "松开发送", "语音", "表情", "更多功能按钮",
        "对方正在输入...", "对方正在输入", "正在输入...", "点击加载更多",
        "已读", "未读", "已发送", "撤回了一条消息", "以上是打招呼的内容",
        "微信", "通讯录", "发现", "我", "消息", "联系人", "动态", "返回"
    )

    fun extract(
        root: AccessibilityNodeInfo,
        screenWidth: Int,
        screenHeight: Int,
        density: Float
    ): ScreenSnapshot? {
        val pkg = root.packageName?.toString().orEmpty()
        if (pkg.isBlank() || pkg == "com.example.replybubble" || pkg == "com.android.systemui") {
            return null
        }

        val winRect = Rect()
        root.getBoundsInScreen(winRect)
        if (winRect.width() <= 0 || winRect.height() <= 0) {
            winRect.set(0, 0, screenWidth, screenHeight)
        }

        fun dp(v: Int): Int = (v * density).toInt()

        val editRect = findBottomEditableRect(root, winRect)
        val topCut = winRect.top + minOf(dp(88), (winRect.height() * 0.11f).toInt())
        val bottomCut = if (editRect != null) {
            editRect.top - dp(8)
        } else {
            winRect.bottom - dp(56)
        }

        val rawNodes = ArrayList<CapturedNode>()
        collectTextNodes(root, pkg, topCut, bottomCut, rawNodes)

        if (rawNodes.isEmpty()) return null

        val deduped = deduplicate(rawNodes)
            .sortedWith(compareBy<CapturedNode> { it.rect.top }.thenBy { it.rect.left })

        val messages = deduped.filter { !isNoise(it.text) }
        if (messages.isEmpty()) return null

        val winW = winRect.width().toFloat()
        val tagged = messages.map { node ->
            val leftMargin = (node.rect.left - winRect.left).toFloat()
            val rightMargin = (winRect.right - node.rect.right).toFloat()
            val centerX = (node.rect.centerX() - winRect.left).toFloat()

            val isLeft = (leftMargin < winW * 0.25f && centerX < winW * 0.48f) ||
                (leftMargin < winW * 0.22f && rightMargin > winW * 0.16f && leftMargin + dp(20) < rightMargin)
            val isRight = (rightMargin < winW * 0.25f && centerX > winW * 0.52f) ||
                (rightMargin < winW * 0.22f && leftMargin > winW * 0.16f && rightMargin + dp(20) < leftMargin)

            when {
                isLeft && !isRight -> "对方：${node.text}"
                isRight && !isLeft -> "我：${node.text}"
                else -> node.text
            }
        }

        val recent = tagged.takeLast(10)
        val parsed = buildParsedContext(recent)
        if (parsed.target.isBlank()) return null

        return ScreenSnapshot(
            packageName = pkg,
            parsed = parsed,
            lineCount = recent.size
        )
    }

    private fun buildParsedContext(lines: List<String>): ParsedContext {
        if (lines.isEmpty()) return ParsedContext("", emptyList())
        val last = lines.last()
        val isSelfLast = last.startsWith("我：")
        val target = when {
            last.startsWith("对方：") -> last.removePrefix("对方：").trim()
            last.startsWith("我：") -> last.removePrefix("我：").trim()
            else -> last.trim()
        }
        val history = lines.dropLast(1).takeLast(8)
        return ParsedContext(
            target = target,
            history = history,
            isSelfLast = isSelfLast
        )
    }

    private fun findBottomEditableRect(root: AccessibilityNodeInfo, winRect: Rect): Rect? {
        var best: Rect? = null
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            if (node.isVisibleToUser) {
                val isInput = node.isEditable ||
                    node.className?.toString()?.contains("EditText", ignoreCase = true) == true
                if (isInput) {
                    val r = Rect()
                    node.getBoundsInScreen(r)
                    if (r.height() > 0 && r.centerY() > winRect.top + winRect.height() * 0.35f) {
                        if (best == null || r.bottom > best.bottom) {
                            best = r
                        }
                    }
                }
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { stack.add(it) }
                }
            }
        }
        return best
    }

    private fun collectTextNodes(
        node: AccessibilityNodeInfo,
        targetPkg: String,
        topCut: Int,
        bottomCut: Int,
        out: MutableList<CapturedNode>
    ) {
        if (!node.isVisibleToUser) return
        val nodePkg = node.packageName?.toString()
        if (nodePkg == "com.example.replybubble" || nodePkg == "com.android.systemui") return
        if (nodePkg != null && nodePkg != targetPkg) return
        if (node.isEditable) return

        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.width() <= 0 || rect.height() <= 0) return

        val text = node.text?.toString()?.trim()
        if (!text.isNullOrEmpty() && rect.bottom > topCut && rect.centerY() < bottomCut) {
            out.add(CapturedNode(text, rect))
        }

        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                collectTextNodes(child, targetPkg, topCut, bottomCut, out)
            }
        }
    }

    private fun deduplicate(nodes: List<CapturedNode>): List<CapturedNode> {
        val out = ArrayList<CapturedNode>(nodes.size)
        for (item in nodes) {
            val dup = out.any { existing ->
                existing.text == item.text &&
                    abs(existing.rect.top - item.rect.top) < 12 &&
                    abs(existing.rect.left - item.rect.left) < 12
            }
            if (!dup) out.add(item)
        }
        return out
    }

    private fun isNoise(text: String): Boolean {
        if (text.isBlank()) return true
        if (text in NOISE_EXACT) return true
        if (TIME_ONLY.matches(text)) return true
        if (PURE_DIGITS.matches(text)) return true
        if (text.length == 1 && !text[0].isLetterOrDigit()) return true
        return false
    }
}
