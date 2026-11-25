package com.example.replybubble

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.replybubble.databinding.ActivityReplyBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class ReplyActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReplyBinding
    private val adapter = ReplyAdapter { copyAndFinish(it.text) }

    private var loaded = false
    private var job: Job? = null
    private var generation = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReplyBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupWindow()
        playEntrance()

        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter

        binding.btnClose.setOnClickListener { finish() }
        binding.scrim.setOnClickListener { finish() }
        binding.btnRetry.setOnClickListener {
            loaded = true
            load(force = true)
        }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    /** iOS 那种背后模糊；不支持就退化成半透明遮罩 */
    private fun setupWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { window.setBackgroundBlurRadius(60) }
        }
    }

    private fun playEntrance() {
        val offset = 260f * resources.displayMetrics.density
        binding.panel.translationY = offset
        binding.panel.animate()
            .translationY(0f)
            .setDuration(280L)
            .setInterpolator(DecelerateInterpolator())
            .start()

        binding.scrim.alpha = 0f
        binding.scrim.animate().alpha(1f).setDuration(220L).start()
    }

    /**
     * 剪贴板在 Android 10+ 只对「有焦点」的应用开放。
     * 等窗口真正拿到焦点再读，比在 onCreate 里读稳。
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !loaded) {
            loaded = true
            load()
        }
    }

    private fun load(force: Boolean = false) {
        val raw = readClipboard()
        if (raw.isNullOrBlank()) {
            showError("剪贴板是空的。\n\n先去聊天里长按对方那条消息 → 复制，再点悬浮球。\n也可以多选几条一起复制，我会读成上下文。")
            return
        }

        if (!force && raw == Prefs.lastCopied(this)) {
            showError(
                "剪贴板里还是你上次复制的那条回复。\n\n" +
                    "先回聊天里复制对方的新消息，再点悬浮球；\n" +
                    "想用同一段再生成一批，点下面「换一批」。"
            )
            return
        }

        val parsed = ContextParser.parse(raw)
        if (parsed.target.isBlank()) {
            showError("没从剪贴板里读到能用的内容，换一条试试。")
            return
        }

        val contextHint = if (parsed.history.isEmpty()) "" else "（含 ${parsed.history.size} 条上文）"
        binding.tvSource.text = "内容包 ${ContentStore.current(this).version} · 对方：$contextHint" +
            parsed.target.replace("\n", " ").take(40)
        binding.tvSource.visibility = View.VISIBLE
        binding.errorScroll.visibility = View.GONE
        binding.list.visibility = View.GONE
        binding.loading.visibility = View.VISIBLE
        binding.btnRetry.isEnabled = false

        job?.cancel()
        val gen = ++generation
        job = lifecycleScope.launch {
            try {
                val replies = ReplyEngine.generate(this@ReplyActivity, parsed)
                if (gen != generation) return@launch
                adapter.submit(replies)
                binding.list.visibility = View.VISIBLE
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen != generation) return@launch
                showError(e.message ?: "请求失败，检查下网络或接口配置")
            } finally {
                if (gen == generation) {
                    binding.loading.visibility = View.GONE
                    binding.btnRetry.isEnabled = true
                }
            }
        }
    }

    private fun showError(message: String) {
        binding.loading.visibility = View.GONE
        binding.list.visibility = View.GONE
        binding.tvError.text = message
        binding.errorScroll.visibility = View.VISIBLE
        binding.btnRetry.isEnabled = true
    }

    private fun readClipboard(): String? = try {
        val cm = getSystemService(ClipboardManager::class.java)
        val clip = cm.primaryClip
        if (clip == null || clip.itemCount == 0) {
            null
        } else {
            clip.getItemAt(0).coerceToText(this)?.toString()?.trim()
        }
    } catch (e: Exception) {
        null
    }

    private fun copyAndFinish(text: String) {
        try {
            val cm = getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newPlainText("reply", text))
            Prefs.setLastCopied(this, text)
            Toast.makeText(this, "已复制，回聊天窗口长按粘贴", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "复制失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
