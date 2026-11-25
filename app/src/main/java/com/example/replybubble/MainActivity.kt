package com.example.replybubble

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.replybubble.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var pendingStart = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnStart.setOnClickListener { startBubble() }
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
            ContextCompat.startForegroundService(
                this,
                Intent(this, BubbleService::class.java)
            )
            Toast.makeText(this, "悬浮球已开启", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "启动失败：${e.message}", Toast.LENGTH_LONG).show()
        }
        // Service 的 onCreate 是异步的，稍等一下再刷新状态，否则会短暂显示"未运行"
        binding.tvStatus.postDelayed({ updateStatus() }, 500)
        updateStatus()
    }

    private fun updateStatus() {
        val overlay = if (Settings.canDrawOverlays(this)) "已授权" else "未授权"
        val running = if (BubbleService.running) "运行中" else "未运行"
        binding.tvStatus.text = "悬浮窗权限：$overlay\n服务状态：$running"
    }
}
