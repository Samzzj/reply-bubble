package com.example.replybubble

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions

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
        onComplete: (blocks: List<Pair<String, Rect>>, error: String?) -> Unit
    ) {
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnCompleteListener { task ->
                try {
                    if (task.isSuccessful) {
                        val blocks = task.result?.textBlocks.orEmpty().mapNotNull { block ->
                            val rect = block.boundingBox ?: return@mapNotNull null
                            val text = block.text.trim()
                            if (text.isEmpty() || rect.width() <= 0 || rect.height() <= 0) null
                            else text to rect
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
}
