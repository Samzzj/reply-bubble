package com.example.replybubble

import android.animation.ValueAnimator
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlin.math.abs

/**
 * 悬浮球：iPhone 辅助触控（小白点）观感。
 * 按下 0.92 缩放 + 完全可见，松手回弹；闲置 3 秒降到 45% 透明度；拖动松手吸附到最近一侧。
 */
class BubbleService : Service() {

    companion object {
        private const val CHANNEL_ID = "reply_bubble"
        private const val NOTIF_ID = 42
        private const val IDLE_ALPHA = 0.45f
        private const val IDLE_DELAY = 3000L

        @Volatile
        var running: Boolean = false
            private set

        @Volatile
        private var instance: BubbleService? = null

        fun showTip(context: Context, message: String) {
            val svc = instance
            if (svc != null) {
                Handler(Looper.getMainLooper()).post { svc.showOverlayTip(message) }
            } else {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private lateinit var wm: WindowManager
    private lateinit var params: WindowManager.LayoutParams
    private var bubble: View? = null
    private var tipView: View? = null
    private var touchSlop = 0
    private var capturing = false

    private val dismissTip = Runnable { removeTipView() }

    private val idleDim = Runnable {
        bubble?.animate()?.alpha(IDLE_ALPHA)?.setDuration(350L)?.start()
        Unit
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        running = true
        wm = getSystemService(WindowManager::class.java)
        touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        createChannel()
        startAsForeground()

        if (!addBubble()) {
            Toast.makeText(this, "没有悬浮窗权限，请先授权", Toast.LENGTH_LONG).show()
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        removeTipView()
        bubble?.let {
            it.removeCallbacks(idleDim)
            try {
                wm.removeView(it)
            } catch (_: Exception) {
                // 已经被系统移除了
            }
        }
        bubble = null
        running = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun startAsForeground() {
        val type = if (Build.VERSION.SDK_INT >= 34) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), type)
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "悬浮球", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("AI回复运行中")
        .setContentText("在聊天界面点悬浮球自动生成回复，点选即填入输入框")
        .setOngoing(true)
        .setSilent(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        )
        .build()

    private fun addBubble(): Boolean {
        val view = LayoutInflater.from(this).inflate(R.layout.view_bubble, null)
        val screenH = resources.displayMetrics.heightPixels
        // 必须给死尺寸：WRAP_CONTENT 在 WindowManager 下会用 UNSPECIFIED 测量，
        // 普通 View 没有内容会直接摊成整屏（之前那个巨大的深色块就是这个原因）。
        val size = (56 * resources.displayMetrics.density).toInt()
        params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = (screenH * 0.55f).toInt()
        }
        attachDrag(view)
        return try {
            wm.addView(view, params)
            bubble = view
            view.postDelayed(idleDim, IDLE_DELAY)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun attachDrag(view: View) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false

        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                    v.removeCallbacks(idleDim)
                    v.animate().cancel()
                    v.animate().scaleX(0.92f).scaleY(0.92f).alpha(1f).setDuration(110L).start()
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        dragging = true
                    }
                    if (dragging) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        try {
                            wm.updateViewLayout(v, params)
                        } catch (_: Exception) {
                            // 忽略
                        }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(180L).start()
                    if (dragging) {
                        snapToEdge(v)
                    } else {
                        openPanel()
                    }
                    v.postDelayed(idleDim, IDLE_DELAY)
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(180L).start()
                    v.postDelayed(idleDim, IDLE_DELAY)
                    true
                }

                else -> false
            }
        }
    }

    /** 松手后吸附到最近的一侧，像 iOS 小白点那样贴边 */
    private fun snapToEdge(view: View) {
        val screenW = resources.displayMetrics.widthPixels
        val target = if (params.x + view.width / 2 < screenW / 2) 0 else screenW - view.width
        ValueAnimator.ofInt(params.x, target).apply {
            duration = 220L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                params.x = it.animatedValue as Int
                runCatching { wm.updateViewLayout(view, params) }
            }
            start()
        }
    }

    private fun openPanel() {
        if (capturing) return
        try {
            val acc = ReplyAccessibilityService.instance
            val accActive = acc != null
            val snapshot = acc?.captureCurrentScreen()
            if (snapshot != null || acc == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                launchReplyActivity(
                    accActive = accActive,
                    snapshot = snapshot,
                    diag = ReplyAccessibilityService.lastDiagnostic
                )
                return
            }

            // 无障碍节点树拿不到时（例如系统限制读取微信），走 Android 11+ 无障碍截屏 + 离线中文 OCR 兜底
            val accDiag = ReplyAccessibilityService.lastDiagnostic.orEmpty()
            capturing = true
            val view = bubble
            view?.removeCallbacks(idleDim)
            view?.animate()?.cancel()
            view?.alpha = 0f

            val startOcr = Runnable {
                acc.captureScreenByOcr { ocrSnap, ocrDiag ->
                    capturing = false
                    view?.alpha = 1f
                    view?.postDelayed(idleDim, IDLE_DELAY)
                    val combinedDiag = listOfNotNull(
                        accDiag.takeIf { it.isNotBlank() },
                        ocrDiag?.takeIf { it.isNotBlank() }
                    ).joinToString(" | ")
                    launchReplyActivity(
                        accActive = true,
                        snapshot = ocrSnap,
                        diag = combinedDiag
                    )
                }
            }
            if (view != null) {
                view.post(startOcr)
            } else {
                startOcr.run()
            }
        } catch (e: Exception) {
            capturing = false
            bubble?.alpha = 1f
            Toast.makeText(this, "打不开面板：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showOverlayTip(message: String) {
        removeTipView()
        val d = resources.displayMetrics.density
        val tv = TextView(this).apply {
            text = message
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13.5f
            setPadding((16 * d).toInt(), (10 * d).toInt(), (16 * d).toInt(), (10 * d).toInt())
            background = GradientDrawable().apply {
                setColor(0xDD1C1C1E.toInt())
                cornerRadius = 20f * d
            }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (96 * d).toInt()
        }
        runCatching {
            wm.addView(tv, lp)
            tipView = tv
            tv.postDelayed(dismissTip, 2200L)
        }.onFailure {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun removeTipView() {
        tipView?.let {
            it.removeCallbacks(dismissTip)
            runCatching { wm.removeView(it) }
        }
        tipView = null
    }

    private fun launchReplyActivity(
        accActive: Boolean,
        snapshot: ScreenSnapshot?,
        diag: String?
    ) {
        try {
            val intent = Intent(this, ReplyActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .apply {
                    putExtra(ReplyActivity.EXTRA_FROM_BUBBLE, true)
                    putExtra(ReplyActivity.EXTRA_ACC_ACTIVE, accActive)
                    if (snapshot != null) {
                        putExtra(ReplyActivity.EXTRA_FROM_SCREEN, true)
                        putExtra(ReplyActivity.EXTRA_TARGET, snapshot.parsed.target)
                        putStringArrayListExtra(
                            ReplyActivity.EXTRA_HISTORY,
                            ArrayList(snapshot.parsed.history)
                        )
                        putExtra(ReplyActivity.EXTRA_IS_SELF_LAST, snapshot.parsed.isSelfLast)
                        putExtra(ReplyActivity.EXTRA_TARGET_PACKAGE, snapshot.packageName)
                    } else {
                        putExtra(
                            ReplyActivity.EXTRA_TARGET_PACKAGE,
                            ReplyAccessibilityService.lastExternalPackage.orEmpty()
                        )
                        putExtra(
                            ReplyActivity.EXTRA_CAPTURE_DIAG,
                            diag.orEmpty()
                        )
                    }
                }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "打不开面板：${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
