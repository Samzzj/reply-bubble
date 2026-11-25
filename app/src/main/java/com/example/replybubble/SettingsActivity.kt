package com.example.replybubble

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.replybubble.databinding.ActivitySettingsBinding
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.etUrl.setText(Prefs.apiUrl(this))
        binding.etModel.setText(Prefs.model(this))
        binding.etKey.setText(Prefs.apiKey(this))
        binding.etPersona.setText(Prefs.persona(this))
        binding.etContentUrl.setText(ContentStore.contentUrl(this))
        binding.tvContentVersion.text = "当前内容包：" + ContentStore.versionLabel(this)

        binding.btnSave.setOnClickListener {
            val key = readAndCheckKey() ?: return@setOnClickListener
            persist(key)
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
            finish()
        }

        binding.btnTest.setOnClickListener {
            val key = readAndCheckKey() ?: return@setOnClickListener
            persist(key)
            binding.tvTest.visibility = View.VISIBLE
            binding.tvTest.text = "正在测试…"
            binding.btnTest.isEnabled = false
            lifecycleScope.launch {
                binding.tvTest.text = try {
                    ReplyEngine.test(this@SettingsActivity)
                } catch (e: Exception) {
                    e.message ?: "测试失败"
                }
                binding.btnTest.isEnabled = true
            }
        }

        binding.btnUpdateContent.setOnClickListener {
            val url = binding.etContentUrl.text.toString().trim()
            binding.btnUpdateContent.isEnabled = false
            binding.tvContentVersion.text = "正在下载…"
            lifecycleScope.launch {
                binding.tvContentVersion.text = try {
                    ContentStore.update(this@SettingsActivity, url)
                } catch (e: Exception) {
                    "更新失败：" + (e.message ?: "未知错误")
                }
                binding.btnUpdateContent.isEnabled = true
            }
        }
    }

    /**
     * 手机键盘很容易把 "sk-" 打成全角 "sk－"，或者粘贴时带进换行。
     * 这两类错误在输入框里几乎看不出来，必须显式拦掉。
     */
    private fun readAndCheckKey(): String? {
        val key = binding.etKey.text.toString().trim()
        if (key.isEmpty()) {
            toast("先填 API Key")
            return null
        }
        if (key.any { it.code > 127 }) {
            toast("Key 里有中文或全角字符（比如全角的 － ）。请清空后整个重新粘贴，不要手打。")
            return null
        }
        if (key.any { it.isWhitespace() }) {
            toast("Key 里混进了空格或换行，请清空后重新粘贴。")
            return null
        }
        if (!key.startsWith("sk-")) {
            toast("提醒：这个 Key 不是以 sk- 开头，很可能不是 DeepSeek 的 Key。当前：${mask(key)}")
        }
        return key
    }

    private fun mask(key: String): String =
        if (key.length <= 10) "$key（共 ${key.length} 位）"
        else "${key.take(5)}…${key.takeLast(4)}（共 ${key.length} 位）"

    private fun persist(key: String) {
        Prefs.setApiUrl(this, binding.etUrl.text.toString().trim())
        Prefs.setModel(this, binding.etModel.text.toString().trim().ifBlank { Prefs.DEFAULT_MODEL })
        Prefs.setApiKey(this, key)
        Prefs.setPersona(this, binding.etPersona.text.toString().trim())
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
