package com.example.replybubble

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.abs

data class ScreenSnapshot(
    val packageName: String,
    val parsed: ParsedContext,
    val lineCount: Int
)

data class ExtractResult(
    val snapshot: ScreenSnapshot?,
    val packageName: String,
    val totalNodes: Int,
    val rawTextNodes: Int,
    val rawDescNodes: Int,
    val afterFilterNodes: Int,
    val reason: String
)

/**
 * 从当前聊天窗口的无障碍节点树中提取对话内容。
 * 1. 过滤顶部栏、底部输入栏及紧贴输入框上方的快捷功能条（如「早上好 / 一起听」）；
 * 2. 过滤同一水平行出现的标签组、系统通知与跳转链接（如「去看看 / 互动设置」）；
 * 3. 按屏幕左右位置区分「对方（左）」和「我（右）」，过滤居中系统提示。
 */
object ScreenContextExtractor {

    private const val MAX_DEPTH = 64

    private data class CapturedNode(
        val text: String,
        val rect: Rect,
        val fromDesc: Boolean = false
    )

    private val TIME_ONLY = Regex(
        """^(\d{1,2}:\d{2}|(昨天|前天|今天|星期[一二三四五六日天]|周[一二三四五六日天]|上午|下午|晚上|凌晨)\s*\d{0,2}:?\d{0,2}|刚刚|\d+分钟前|\d+小时前|\d{4}[-/年]\d{1,2}[-/月]\d{1,2}日?(\s+\d{1,2}:\d{2})?)$"""
    )

    private val PURE_DIGITS = Regex("""^\d{1,4}\+?$""")

    private val NOISE_EXACT = setOf(
        "发送", "按住 说话", "按住说话", "松开发送", "语音", "表情", "更多功能按钮",
        "对方正在输入...", "对方正在输入", "正在输入...", "点击加载更多",
        "已读", "未读", "已发送", "撤回了一条消息", "以上是打招呼的内容",
        "微信", "通讯录", "发现", "我", "消息", "联系人", "动态", "返回",
        "关注", "已关注", "互相关注", "去看看", "互动设置", "查看详情",
        "发消息或按住说话", "输入消息...",
        "聊天信息", "切换到按住说话", "切换到键盘", "更多功能", "相册", "拍摄",
        "视频通话", "位置", "红包", "转账", "语音输入", "我的收藏", "名片", "文件", "接龙"
    )

    private val ACTION_SUFFIX = listOf("去看看", "看看>", "去商城", "互动设置", "点击查看", "立即查看")

    fun extract(
        root: AccessibilityNodeInfo,
        screenWidth: Int,
        screenHeight: Int,
        density: Float
    ): ScreenSnapshot? = extractWithDiagnostic(root, screenWidth, screenHeight, density).snapshot

    fun extractWithDiagnostic(
        root: AccessibilityNodeInfo,
        screenWidth: Int,
        screenHeight: Int,
        density: Float
    ): ExtractResult {
        val pkg = root.packageName?.toString().orEmpty()
        if (pkg.isBlank() || pkg == "com.example.replybubble" || pkg == "com.android.systemui") {
            return ExtractResult(null, pkg, 0, 0, 0, 0, "跳过自身或系统界面")
        }

        val winRect = Rect()
        root.getBoundsInScreen(winRect)
        if (winRect.width() <= 0 || winRect.height() <= 0) {
            winRect.set(0, 0, screenWidth, screenHeight)
        }

        fun dp(v: Int): Int = (v * density).toInt()

        val editRect = findBottomEditableRect(root, winRect)
        val topCut = winRect.top + maxOf(dp(96), (winRect.height() * 0.115f).toInt())
        val bottomCut = if (editRect != null) {
            editRect.top - dp(8)
        } else {
            winRect.bottom - dp(56)
        }

        val rawNodes = ArrayList<CapturedNode>()
        val stats = intArrayOf(0) // totalNodes
        collectTextNodes(root, topCut, bottomCut, 0, stats, rawNodes)
        val totalNodes = stats[0]
        val rawTextCount = rawNodes.count { !it.fromDesc }
        val rawDescCount = rawNodes.count { it.fromDesc }

        if (rawNodes.isEmpty()) {
            val reason = if (totalNodes <= 3) {
                "系统或该应用限制了无障碍读取界面"
            } else {
                "节点树中未发现可读文字节点"
            }
            return ExtractResult(null, pkg, totalNodes, 0, 0, 0, reason)
        }

        val merged = mergeTextAndDesc(rawNodes)
        val deduped = deduplicate(merged)
            .filter { !isNoise(it.text) }
            .sortedWith(compareBy<CapturedNode> { it.rect.top }.thenBy { it.rect.left })

        if (deduped.isEmpty()) {
            return ExtractResult(null, pkg, totalNodes, rawTextCount, rawDescCount, 0, "全部文本被噪声规则过滤")
        }

        // 过滤同一水平行排布的控件（快捷回复条、引力签标签组、系统提示+链接行等）
        val singleRowMessages = filterHorizontalUiRows(deduped, editRect, ::dp)
        if (singleRowMessages.isEmpty()) {
            return ExtractResult(null, pkg, totalNodes, rawTextCount, rawDescCount, 0, "同行控件过滤后为空")
        }

        val winW = winRect.width().toFloat()
        val tagged = singleRowMessages.map { node ->
            val leftMargin = (node.rect.left - winRect.left).toFloat()
            val rightMargin = (winRect.right - node.rect.right).toFloat()
            val centerX = (node.rect.centerX() - winRect.left).toFloat()

            val isLeft = (leftMargin < winW * 0.25f && centerX < winW * 0.48f) ||
                (leftMargin < winW * 0.22f && rightMargin > winW * 0.16f && leftMargin + dp(20) < rightMargin)
            val isRight = (rightMargin < winW * 0.25f && centerX > winW * 0.52f) ||
                (rightMargin < winW * 0.22f && leftMargin > winW * 0.16f && rightMargin + dp(20) < leftMargin)

            val centeredNotice = !isLeft && !isRight &&
                abs(leftMargin - rightMargin) < winW * 0.14f &&
                leftMargin > winW * 0.10f && rightMargin > winW * 0.10f &&
                node.rect.height() <= dp(38) && node.text.length <= 36

            when {
                isLeft && !isRight -> Triple("对方：${node.text}", true, false)
                isRight && !isLeft -> Triple("我：${node.text}", true, false)
                else -> Triple(node.text, false, centeredNotice)
            }
        }

        // 若已经识别到明确的左右聊天气泡，只保留气泡消息（以及长段落），丢弃夹杂的居中系统提示与短杂项
        val hasBubbles = tagged.any { it.second }
        val finalLines = if (hasBubbles) {
            tagged.filter { it.second || (!it.third && it.first.length >= 24) }.map { it.first }
        } else {
            tagged.filter { !it.third }.map { it.first }.ifEmpty { tagged.map { it.first } }
        }

        val recent = finalLines.takeLast(10)
        val parsed = buildParsedContext(recent)
        if (parsed.target.isBlank()) {
            return ExtractResult(null, pkg, totalNodes, rawTextCount, rawDescCount, finalLines.size, "最终解析目标为空")
        }

        val snap = ScreenSnapshot(
            packageName = pkg,
            parsed = parsed,
            lineCount = recent.size
        )
        return ExtractResult(snap, pkg, totalNodes, rawTextCount, rawDescCount, recent.size, "ok")
    }

    /**
     * 聊天消息气泡在竖直方向逐条排列，不会有多个元素并排在同一水平线上。
     * 把同一水平线（centerY 相近）上的多项元素识别为快捷功能条 / 标签行 / 系统通知+按钮并过滤掉。
     */
    private fun filterHorizontalUiRows(
        nodes: List<CapturedNode>,
        editRect: Rect?,
        dp: (Int) -> Int
    ): List<CapturedNode> {
        val yThreshold = dp(14)
        val rows = ArrayList<MutableList<CapturedNode>>()
        for (node in nodes) {
            val cy = node.rect.centerY()
            val row = rows.lastOrNull()?.takeIf {
                abs(it.first().rect.centerY() - cy) <= yThreshold
            }
            if (row != null) {
                row.add(node)
            } else {
                rows.add(mutableListOf(node))
            }
        }

        val out = ArrayList<CapturedNode>(nodes.size)
        for (row in rows) {
            if (row.size == 1) {
                out.add(row[0])
                continue
            }

            // 同行 >= 3 个元素：一定是快捷功能栏（如「早上好 / 交换答案 / 桌球 / 礼物 / 一起听」）或标签组
            if (row.size >= 3) continue

            // 同行 2 个元素：若都很短（标签/头栏），或包含跳转操作后缀（如「去看看」「互动设置」），整行丢弃
            val allShort = row.all { it.text.length <= 12 }
            val hasAction = row.any { n -> ACTION_SUFFIX.any { n.text.endsWith(it) || n.text == it } }
            val nearInputBar = editRect != null && row.any { it.rect.bottom >= editRect.top - dp(68) }
            if (allShort || hasAction || nearInputBar) continue

            // 若是长文本 + 附带小标签（极少见），只保留较长那条
            row.maxByOrNull { it.text.length }?.takeIf { it.text.length >= 12 }?.let { out.add(it) }
        }
        return out
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
        val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        stack.add(root to 0)
        while (stack.isNotEmpty()) {
            val (node, depth) = stack.removeLast()
            if (depth > MAX_DEPTH) continue
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
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.add(it to depth + 1) }
            }
        }
        return best
    }

    private fun collectTextNodes(
        node: AccessibilityNodeInfo,
        topCut: Int,
        bottomCut: Int,
        depth: Int,
        stats: IntArray,
        out: MutableList<CapturedNode>
    ) {
        if (depth > MAX_DEPTH) return
        val nodePkg = node.packageName?.toString()
        if (nodePkg == "com.example.replybubble" || nodePkg == "com.android.systemui") return
        if (node.isEditable) return

        stats[0]++

        if (node.isVisibleToUser) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (rect.width() > 0 && rect.height() > 0 && rect.bottom > topCut && rect.centerY() < bottomCut) {
                val directText = node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                if (directText != null) {
                    out.add(CapturedNode(directText, rect, fromDesc = false))
                } else {
                    val desc = node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                    if (desc != null && isUsableDesc(node, desc)) {
                        out.add(CapturedNode(desc, rect, fromDesc = true))
                    }
                }
            }
        }

        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                collectTextNodes(child, topCut, bottomCut, depth + 1, stats, out)
            }
        }
    }

    private fun isUsableDesc(node: AccessibilityNodeInfo, desc: String): Boolean {
        if (desc.endsWith("头像") || desc.endsWith("的头像")) return false
        val cls = node.className?.toString().orEmpty()
        if (cls.endsWith(".ImageView") || cls.endsWith(".ImageButton")) return false
        return true
    }

    private fun mergeTextAndDesc(nodes: List<CapturedNode>): List<CapturedNode> {
        val textNodes = nodes.filter { !it.fromDesc }
        if (textNodes.isEmpty()) return nodes
        val descNodes = nodes.filter { d ->
            d.fromDesc && textNodes.none { t -> rectsOverlap(d.rect, t.rect) }
        }
        return if (descNodes.isEmpty()) textNodes else textNodes + descNodes
    }

    private fun rectsOverlap(a: Rect, b: Rect): Boolean =
        a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom

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
