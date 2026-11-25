package com.example.replybubble

import android.animation.ValueAnimator
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
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
    }

    private lateinit var wm: WindowManager
    private lateinit var params: WindowManager.LayoutParams
    private var bubble: View? = null
    private var touchSlop = 0

    private val idleDim = Runnable {
        bubble?.animate()?.alpha(IDLE_ALPHA)?.setDuration(350L)?.start()
        Unit
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
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
        .setContentTitle("回复球运行中")
        .setContentText("复制对方消息后，点悬浮球生成回复")
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
        try {
            startActivity(
                Intent(this, ReplyActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
        } catch (e: Exception) {
            Toast.makeText(this, "打不开面板：${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
