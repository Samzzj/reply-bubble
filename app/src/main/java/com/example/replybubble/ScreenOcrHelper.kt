package com.example.replybubble

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions

data class OcrBlock(
    val text: String,
    val rect: Rect,
    val isMyBubble: Boolean
)

/**
 * 基于 ML Kit 离线中文 OCR 的屏幕文字提取兜底。
 * 当系统或目标应用（如微信）限制无障碍节点树读取时启用。
 */
object ScreenOcrHelper {

    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    fun recognize(
        bitmap: Bitmap,
        density: Float,
        onComplete: (blocks: List<OcrBlock>, error: String?) -> Unit
    ) {
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnCompleteListener { task ->
                try {
                    if (task.isSuccessful) {
                        val blocks = task.result?.textBlocks.orEmpty().mapNotNull { block ->
                            val rect = block.boundingBox ?: return@mapNotNull null
                            val text = block.text.trim()
                            if (text.isEmpty() || rect.width() <= 0 || rect.height() <= 0) {
                                null
                            } else {
                                OcrBlock(
                                    text = text,
                                    rect = rect,
                                    isMyBubble = isGreenWeChatBubble(bitmap, rect, density)
                                )
                            }
                        }
                        onComplete(blocks, null)
                    } else {
                        val e = task.exception
                        onComplete(emptyList(), e?.message ?: e?.javaClass?.simpleName ?: "OCR失败")
                    }
                } finally {
                    if (!bitmap.isRecycled) {
                        bitmap.recycle()
                    }
                }
            }
    }

    /** 通过文字框周围气泡填充色判定是否为微信绿色气泡（即「我」发送的消息） */
    private fun isGreenWeChatBubble(bitmap: Bitmap, rect: Rect, density: Float): Boolean {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return false

        val pad = (3f * density).toInt().coerceAtLeast(2)
        val left = (rect.left - pad).coerceIn(0, w - 1)
        val right = (rect.right + pad).coerceIn(0, w - 1)
        val top = (rect.top - pad).coerceIn(0, h - 1)
        val bottom = (rect.bottom + pad).coerceIn(0, h - 1)
        val q1x = (rect.left + rect.width() / 4).coerceIn(0, w - 1)
        val midX = rect.centerX().coerceIn(0, w - 1)
        val q3x = (rect.left + rect.width() * 3 / 4).coerceIn(0, w - 1)
        val midY = rect.centerY().coerceIn(0, h - 1)

        val points = arrayOf(
            q1x to top,
            midX to top,
            q3x to top,
            q1x to bottom,
            midX to bottom,
            q3x to bottom,
            left to midY,
            right to midY
        )

        var greenHits = 0
        for ((x, y) in points) {
            val c = bitmap.getPixel(x, y)
            val r = Color.red(c)
            val g = Color.green(c)
            val b = Color.blue(c)
            if (g >= 90 && g - r >= 18 && g - b >= 22) {
                greenHits++
            }
        }
        return greenHits >= 3
    }
}
