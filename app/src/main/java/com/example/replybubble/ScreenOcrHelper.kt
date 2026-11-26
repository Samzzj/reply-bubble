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
    val bubbleSide: Int // 0=非纯色气泡(如表情包/图片/背景), 1=对方白/灰气泡, 2=我方绿气泡
)

/**
 * 基于 ML Kit 离线中文 OCR 的屏幕文字提取兜底。
 * 只保留微信实色文字气泡（绿气泡 / 白或深灰气泡），自动忽略表情包、图片内嵌文字。
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
                                    bubbleSide = classifyWeChatBubble(bitmap, rect, density)
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

    /** 通过文字框周围气泡填充色区分「我方绿气泡(2)」「对方纯色气泡(1)」「表情包/图片杂色(0)」 */
    private fun classifyWeChatBubble(bitmap: Bitmap, rect: Rect, density: Float): Int {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return 0

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
        var whiteHits = 0
        var darkGrayHits = 0
        for ((x, y) in points) {
            val c = bitmap.getPixel(x, y)
            val r = Color.red(c)
            val g = Color.green(c)
            val b = Color.blue(c)
            val spread = maxOf(r, g, b) - minOf(r, g, b)
            when {
                g >= 90 && g - r >= 18 && g - b >= 22 -> greenHits++
                r >= 242 && g >= 242 && b >= 242 && spread <= 14 -> whiteHits++
                r in 32..68 && g in 32..68 && b in 32..68 && spread <= 14 -> darkGrayHits++
            }
        }
        if (greenHits >= 4) return 2
        if (rect.left < w * 0.28f && (whiteHits >= 5 || darkGrayHits >= 5)) return 1
        return 0
    }
}
