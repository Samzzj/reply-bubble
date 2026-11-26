package com.example.replybubble

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
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

    companion object {
        private const val BLUR_RADIUS = 60
        const val EXTRA_FROM_BUBBLE = "extra_from_bubble"
        const val EXTRA_ACC_ACTIVE = "extra_acc_active"
        const val EXTRA_FROM_SCREEN = "extra_from_screen"
        const val EXTRA_TARGET = "extra_target"
        const val EXTRA_HISTORY = "extra_history"
        const val EXTRA_IS_SELF_LAST = "extra_is_self_last"
        const val EXTRA_TARGET_PACKAGE = "extra_target_package"
        const val EXTRA_CAPTURE_DIAG = "extra_capture_diag"
    }

    private lateinit var binding: ActivityReplyBinding
    private val adapter = ReplyAdapter { selectReplyAndFinish(it.text) }

    private var loaded = false
    private var job: Job? = null
    private var generation = 0
    private var screenParsed: ParsedContext? = null
    private var targetPackage: String? = null
    private var screenDiagnostic: String? = null
    private var fromBubble: Boolean = false
    private var accActive: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReplyBinding.inflate(layoutInflater)
        setContentView(binding.root)

        parseIntentExtras(intent)
        setupWindow()
        playEntrance()

        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter

        binding.btnClose.setOnClickListener { finish() }
        binding.scrim.setOnClickListener { finish() }
        binding.btnGotoSettings.setOnClickListener { openSettings() }
        binding.btnSettings.setOnClickListener { openSettings() }
        binding.btnUseClipboard.setOnClickListener {
            loaded = true
            load(force = true, allowClipboardFallback = true)
        }
        binding.btnRetry.setOnClickListener {
            loaded = true
            load(force = true, allowClipboardFallback = screenParsed == null && !(fromBubble && accActive))
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        parseIntentExtras(intent)
        loaded = false
    }

    private fun parseIntentExtras(intent: Intent?) {
        fromBubble = intent?.getBooleanExtra(EXTRA_FROM_BUBBLE, false) == true
        accActive = intent?.getBooleanExtra(EXTRA_ACC_ACTIVE, false) == true ||
            ReplyAccessibilityService.isRunning()
        targetPackage = intent?.getStringExtra(EXTRA_TARGET_PACKAGE)?.takeIf { it.isNotBlank() }
        screenDiagnostic = intent?.getStringExtra(EXTRA_CAPTURE_DIAG)?.takeIf { it.isNotBlank() }
        val fromScreen = intent?.getBooleanExtra(EXTRA_FROM_SCREEN, false) == true
        val target = intent?.getStringExtra(EXTRA_TARGET).orEmpty().trim()
        if (fromScreen && target.isNotEmpty()) {
            val history = intent?.getStringArrayListExtra(EXTRA_HISTORY)?.toList().orEmpty()
            val isSelfLast = intent?.getBooleanExtra(EXTRA_IS_SELF_LAST, false) == true
            screenParsed = ParsedContext(target = target, history = history, isSelfLast = isSelfLast)
        } else {
            screenParsed = null
        }
    }

    /** iOS 那种背后模糊：Android 12+ 且系统开启跨窗口模糊时生效，否则退化成半透明遮罩 */
    private fun setupWindow() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            fallbackScrim()
            return
        }
        val wm = getSystemService(WindowManager::class.java)
        val crossBlur = runCatching { wm.isCrossWindowBlurEnabled }.getOrDefault(false)
        if (!crossBlur) {
            fallbackScrim()
            return
        }
        val ok = runCatching {
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.setBackgroundBlurRadius(BLUR_RADIUS)
        }.isSuccess
        if (ok) {
            // 模糊生效时遮罩调淡，让毛玻璃透出来
            binding.scrim.setBackgroundColor(0x33000000)
        } else {
            fallbackScrim()
        }
    }

    private fun fallbackScrim() {
        binding.scrim.setBackgroundColor(0x66000000)
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

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    override fun onResume() {
        super.onResume()
        // 从设置页回来，如果之前是空状态，重新走一遍
        if (loaded && Prefs.apiKey(this).isNotBlank() && binding.emptyState.visibility == View.VISIBLE) {
            load()
        }
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

    private fun load(force: Boolean = false, allowClipboardFallback: Boolean = false) {
        val pack = ContentStore.current(this)

        if (Prefs.apiKey(this).isBlank()) {
            showEmptyKeyState(pack.version)
            return
        }

        // 无障碍开启且从悬浮球触发时，若 Intent 还没拿到屏幕内容，再尝试现场抓取一次（避免受瞬时窗口切换影响）
        if (screenParsed == null && fromBubble && accActive && !allowClipboardFallback) {
            val snap = ReplyAccessibilityService.instance?.captureCurrentScreen()
            if (snap != null) {
                screenParsed = snap.parsed
                targetPackage = snap.packageName
            } else {
                val retryDiag = ReplyAccessibilityService.lastDiagnostic
                if (screenDiagnostic.isNullOrBlank() && !retryDiag.isNullOrBlank()) {
                    screenDiagnostic = retryDiag
                }
            }
        }

        var sourceLabel = "屏幕"
        val parsed = if (screenParsed != null && !allowClipboardFallback) {
            screenParsed!!
        } else {
            // 只要无障碍开启且是从悬浮球打开，绝不静默拿剪贴板内容覆盖屏幕读屏
            if (fromBubble && accActive && !allowClipboardFallback) {
                val clip = readClipboard()
                val hasUsableClip = !clip.isNullOrBlank() && clip != Prefs.lastCopied(this)
                val diagLine = screenDiagnostic?.takeIf { it.isNotBlank() }?.let { "\n\n诊断：$it" }.orEmpty()
                showError(
                    "未从当前屏幕识别到聊天文字。\n\n请确认当前处于聊天对话界面（且画面中有文字消息），再重新点一次悬浮球。$diagLine",
                    showClipboardOption = hasUsableClip
                )
                return
            }

            sourceLabel = "剪贴板"
            val raw = readClipboard()
            if (raw.isNullOrBlank()) {
                val tip = if (ReplyAccessibilityService.isRunning()) {
                    "未从当前屏幕读到聊天内容，且剪贴板是空的。\n\n请在聊天界面再点一次悬浮球，或先复制对方消息。"
                } else {
                    "剪贴板是空的。\n\n建议在主页开启「无障碍服务」，点球即可自动读屏并直接填入输入框；\n也可以先去聊天里复制对方消息再点球。"
                }
                showError(tip)
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

            ContextParser.parse(raw)
        }

        if (parsed.target.isBlank()) {
            showError("没读到能用的聊天内容，换一条或回到聊天界面再试试。")
            return
        }

        val contextHint = if (parsed.history.isEmpty()) "" else " · ${parsed.history.size} 条上文"
        val whoLabel = if (parsed.isSelfLast) "我" else "对方"
        binding.tvSubtitle.text = "内容包 ${pack.version} · $sourceLabel · $whoLabel：${parsed.target.replace("\n", " ").take(16)}$contextHint"

        binding.emptyState.visibility = View.GONE
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

    private fun showEmptyKeyState(packVersion: Int) {
        binding.tvSubtitle.text = "内容包 $packVersion · 还没配置 Key"
        binding.loading.visibility = View.GONE
        binding.list.visibility = View.GONE
        binding.errorScroll.visibility = View.GONE
        binding.emptyState.visibility = View.VISIBLE
        binding.btnRetry.isEnabled = false
    }

    private fun showError(message: String, showClipboardOption: Boolean = false) {
        binding.loading.visibility = View.GONE
        binding.list.visibility = View.GONE
        binding.emptyState.visibility = View.GONE
        binding.tvError.text = message
        binding.btnUseClipboard.visibility = if (showClipboardOption) View.VISIBLE else View.GONE
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

    private fun selectReplyAndFinish(text: String) {
        val acc = ReplyAccessibilityService.instance
        if (acc != null) {
            val preferredPkg = targetPackage ?: ReplyAccessibilityService.lastExternalPackage
            acc.insertReply(text, preferredPkg)
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
            return
        }

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
