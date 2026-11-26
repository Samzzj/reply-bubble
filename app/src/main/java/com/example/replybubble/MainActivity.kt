package com.example.replybubble

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.replybubble.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var pendingStart = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupImmersiveStatusBar()

        binding.btnStart.setOnClickListener { startBubble() }
        binding.btnAccessibility.setOnClickListener { openAccessibilitySettings() }
        binding.btnStop.setOnClickListener {
            stopService(Intent(this, BubbleService::class.java))
            updateStatus()
        }
        binding.btnPreview.setOnClickListener {
            startActivity(Intent(this, ReplyActivity::class.java))
        }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    /** 状态栏透明 + 深色图标，让页面和状态栏融为一体 */
    private fun setupImmersiveStatusBar() {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.setBackgroundDrawable(ColorDrawable(0xFFF2F2F7.toInt()))
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
    }

    override fun onResume() {
        super.onResume()
        if (pendingStart && Settings.canDrawOverlays(this)) {
            pendingStart = false
            startBubble()
        }
        updateStatus()
    }

    private fun startBubble() {
        if (!Settings.canDrawOverlays(this)) {
            pendingStart = true
            Toast.makeText(this, "请先打开「显示在其他应用上层」", Toast.LENGTH_LONG).show()
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
            return
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }

        try {
            ContextCompat.startForegroundService(this, Intent(this, BubbleService::class.java))
            Toast.makeText(this, "悬浮球已开启", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "启动失败：${e.message}", Toast.LENGTH_LONG).show()
        }
        binding.tvServiceBadge.postDelayed({ updateStatus() }, 500)
        updateStatus()
    }

    private fun openAccessibilitySettings() {
        Toast.makeText(this, "请在无障碍设置中找到「回复球」并开启", Toast.LENGTH_LONG).show()
        runCatching {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }.onFailure {
            Toast.makeText(this, "无法打开无障碍设置，请到系统设置中手动开启", Toast.LENGTH_LONG).show()
        }
    }

    private fun updateStatus() {
        val overlayOk = Settings.canDrawOverlays(this)
        applyBadge(binding.tvPermBadge, if (overlayOk) "已授权" else "未授权", overlayOk)

        val running = BubbleService.running
        applyBadge(binding.tvServiceBadge, if (running) "运行中" else "未运行", running)

        val accOk = ReplyAccessibilityService.isEnabledInSettings(this)
        applyBadge(binding.tvAccessibilityBadge, if (accOk) "已开启" else "未开启", accOk)
        binding.btnAccessibility.text = if (accOk) {
            "无障碍服务已开启（点此管理）"
        } else {
            "开启无障碍（自动读屏 & 直接填入）"
        }
    }

    private fun applyBadge(tv: TextView, text: String, on: Boolean) {
        tv.text = text
        if (on) {
            tv.setBackgroundResource(R.drawable.bg_badge_green)
            tv.setTextColor(0xFF248A3D.toInt())
        } else {
            tv.setBackgroundResource(R.drawable.bg_badge_red)
            tv.setTextColor(0xFFC1121F.toInt())
        }
    }
}
