package com.example.replybubble

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class Reply(
    val group: String,
    val tag: String,
    val text: String
)

object ReplyEngine {

    /**
     * 一次请求返回 6 条（A 组 3 条短句 + B 组 3 条军师）。
     * 一次请求比两次并发更快、更便宜，结构也更稳。
     */
    private const val SYSTEM = """你是一个中文微信聊天回复助手。用户会给你对方刚发来的一条消息，你要给出 6 条可以直接发送的回复，分两组。

A 组 · 短句直球（3 条）
- 一句话，不超过 28 个字，口语，像在微信上随手打的
- 先给结论，不解释；可以自嘲、可以轻轻反问，但不阴阳怪气
- 不跪舔、不说教、不撒鸡汤，不用「宝贝」「亲爱的」这类腻词
- 不用括号写动作或心理活动，最多 1 个 emoji
- 例：「行，那你先忙，我不打扰了。」

B 组 · 军师接话（3 条）
- 2 到 3 句，总长 40 到 80 字
- 结构：先接住对方这句话背后的情绪（一句）→ 给一个具体的小动作或提议 → 留一个话口让对方好接
- 真诚、具体、不套路；不评价对方人品，不把对方心思猜得太满

共同要求：
1. 每条都能直接发出去，不要出现「你可以说」「建议回复」这类说明
2. 6 条的开头不要重复，角度要不一样
3. 不要提 AI、模型、分析过程
4. 只输出 JSON，不要 markdown 代码块，不要任何解释：
{"replies":[{"group":"A","tag":"短句","text":"..."},{"group":"A","tag":"自嘲","text":"..."},{"group":"A","tag":"反问","text":"..."},{"group":"B","tag":"共情","text":"..."},{"group":"B","tag":"接话","text":"..."},{"group":"B","tag":"推进","text":"..."}]}"""

    suspend fun generate(context: Context, message: String): List<Reply> =
        withContext(Dispatchers.IO) {
            val url = Prefs.apiUrl(context)
            val key = Prefs.apiKey(context)
            val model = Prefs.model(context)
            val persona = Prefs.persona(context)

            if (key.isBlank()) throw IOException("还没填 API Key，点右上角「设置」")
            if (url.isBlank()) throw IOException("还没填接口地址")

            val user = buildString {
                append("对方发来的消息：「")
                append(message.take(300))
                append("」")
                if (persona.isNotBlank()) {
                    append("\n\n背景（只用来判断关系，不要写进回复）：\n")
                    append(persona.take(300))
                }
            }

            val body = JSONObject().apply {
                put("model", model)
                put("temperature", 0.9)
                put("max_tokens", 900)
                put(
                    "messages",
                    JSONArray().apply {
                        put(JSONObject().put("role", "system").put("content", SYSTEM))
                        put(JSONObject().put("role", "user").put("content", user))
                    }
                )
            }

            parseReplies(post(url, key, body.toString()))
        }

    /**
     * 设置页的「测试连接」：发一个最小请求，只关心能不能通。
     */
    suspend fun test(context: Context): String = withContext(Dispatchers.IO) {
        val url = Prefs.apiUrl(context)
        val key = Prefs.apiKey(context)
        val model = Prefs.model(context)
        if (key.isBlank()) throw IOException("Key 是空的")

        val body = JSONObject().apply {
            put("model", model)
            put("max_tokens", 8)
            put(
                "messages",
                JSONArray().apply {
                    put(JSONObject().put("role", "user").put("content", "ping"))
                }
            )
        }
        val raw = post(url, key, body.toString())
        "连接正常 ✓\n模型 $model 已响应（返回 ${raw.length} 字节）"
    }

    private fun post(url: String, key: String, body: String): String {
        val host = runCatching { URL(url).host }.getOrDefault(url)
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 40_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Authorization", "Bearer $key")
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IOException(friendlyError(code, text, host))
            return text
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 服务端的原话对普通用户没有可操作性，这里翻译成能照着做的提示。
     */
    private fun friendlyError(code: Int, body: String, host: String): String {
        val serverMsg = runCatching {
            JSONObject(body).getJSONObject("error").optString("message")
        }.getOrNull().orEmpty().ifBlank { body.take(200) }

        return when (code) {
            401 -> """
                Key 被服务端拒绝了（401）。
                • 确认这个 Key 是 $host 对应平台生成的
                • 清空输入框后整个重新粘贴，别手打（容易把 "sk-" 打成全角 "sk－"）
                • 刚生成的 Key 有时要等一两分钟生效
                服务端原话：$serverMsg
            """.trimIndent()

            402, 403 -> """
                账号状态有问题（$code），常见原因是余额不足或该 Key 没有这个模型的权限。
                服务端原话：$serverMsg
            """.trimIndent()

            404 -> """
                接口地址不对（404）。
                DeepSeek 应为：https://api.deepseek.com/chat/completions
                服务端原话：$serverMsg
            """.trimIndent()

            429 -> """
                请求太频繁（429），等十几秒再试。
                服务端原话：$serverMsg
            """.trimIndent()

            else -> "接口返回 $code。\n服务端原话：$serverMsg"
        }
    }

    private fun parseReplies(raw: String): List<Reply> {
        try {
            val content = JSONObject(raw)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")

            val start = content.indexOf('{')
            val end = content.lastIndexOf('}')
            val json = if (start >= 0 && end > start) content.substring(start, end + 1) else content

            val arr = JSONObject(json).getJSONArray("replies")
            val out = ArrayList<Reply>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val text = o.optString("text").trim()
                if (text.isEmpty()) continue
                val isB = o.optString("group", "A").uppercase().startsWith("B")
                val fallbackTag = if (isB) "军师" else "直球"
                out.add(
                    Reply(
                        group = if (isB) "B" else "A",
                        tag = o.optString("tag").trim().ifBlank { fallbackTag },
                        text = text
                    )
                )
            }
            if (out.isEmpty()) throw IOException("没有解析出回复")
            return out
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("返回格式不对：${e.message}")
        }
    }
}
