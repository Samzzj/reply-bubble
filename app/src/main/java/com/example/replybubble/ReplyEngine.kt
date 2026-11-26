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
    val level: String,
    val text: String
)

data class ParsedContext(
    val target: String,
    val history: List<String>,
    val isSelfLast: Boolean = false
)

/**
 * 剪贴板可能是一整段对话。
 * 有「我：」「她：」这样的说话人前缀就整段当上下文；没有就把最后一行当目标。
 */
object ContextParser {

    private val SPEAKER = Regex("""^\s*([\p{IsHan}A-Za-z0-9_]{1,6})\s*[：:]\s*(.+)$""")

    fun parse(raw: String): ParsedContext {
        val lines = raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return ParsedContext("", emptyList())

        val withSpeaker = lines.count { SPEAKER.matches(it) }
        return when {
            withSpeaker >= 2 -> {
                val normalized = lines.map { line ->
                    val m = SPEAKER.find(line)
                    if (m != null) "${m.groupValues[1]}：${m.groupValues[2].trim()}" else line
                }
                val last = normalized.last()
                val target = SPEAKER.find(last)?.groupValues?.get(2)?.trim() ?: last
                ParsedContext(target, normalized.dropLast(1).takeLast(8))
            }

            lines.size == 1 -> ParsedContext(lines[0], emptyList())

            else -> ParsedContext(lines.last(), lines.dropLast(1).takeLast(8))
        }
    }
}

object ReplyEngine {

    suspend fun generate(context: Context, parsed: ParsedContext): List<Reply> =
        withContext(Dispatchers.IO) {
            if (parsed.target.isBlank()) throw IOException("没读到有效内容")

            val url = Prefs.apiUrl(context)
            val key = Prefs.apiKey(context)
            val model = Prefs.model(context)
            val persona = Prefs.persona(context)
            val pack = ContentStore.current(context)

            if (key.isBlank()) throw IOException("还没填 API Key，点右上角「设置」")
            if (url.isBlank()) throw IOException("还没填接口地址")

            val cases = pickCases(pack, parsed.target + "\n" + parsed.history.joinToString("\n"))

            val system = buildSystem(pack, cases)
            val user = buildUser(parsed, persona)

            val body = JSONObject().apply {
                put("model", model)
                put("temperature", 1.0)
                put("max_tokens", 700)
                put(
                    "messages",
                    JSONArray().apply {
                        put(JSONObject().put("role", "system").put("content", system))
                        put(JSONObject().put("role", "user").put("content", user))
                    }
                )
            }

            parseReplies(post(url, key, body.toString()), pack)
        }

    /** 设置页的「测试连接」：最小请求，只关心通不通 */
    suspend fun test(context: Context): String = withContext(Dispatchers.IO) {
        val url = Prefs.apiUrl(context)
        val key = Prefs.apiKey(context)
        val model = Prefs.model(context)
        if (key.isBlank()) throw IOException("Key 是空的")

        val body = JSONObject().apply {
            put("model", model)
            put("max_tokens", 8)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "user").put("content", "ping"))
            })
        }
        val raw = post(url, key, body.toString())
        "连接正常 ✓\n模型 $model 已响应（${raw.length} 字节）"
    }

    private fun pickCases(pack: ContentPack, text: String): List<ContentCase> =
        pack.cases
            .map { c -> c to c.keywords.count { it.isNotBlank() && text.contains(it, ignoreCase = true) } }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(2)
            .map { it.first }

    private fun buildSystem(pack: ContentPack, cases: List<ContentCase>): String = buildString {
        appendLine(pack.prompt.attitude)
        appendLine()
        appendLine(pack.prompt.judge)

        if (pack.prompt.moves.isNotBlank()) {
            appendLine()
            appendLine(pack.prompt.moves)
        }

        appendLine()
        appendLine(pack.prompt.voice)

        if (pack.prompt.banned.isNotEmpty()) {
            appendLine()
            appendLine("硬禁词，出现即重写：" + pack.prompt.banned.joinToString("、"))
        }
        if (pack.prompt.bannedSoft.isNotEmpty()) {
            appendLine(
                "位置禁词（可以用，但不能单独成句、不能当回应主体、不能连用两条）：" +
                    pack.prompt.bannedSoft.joinToString("、")
            )
        }
        if (pack.prompt.gate.isNotBlank()) {
            appendLine()
            appendLine(pack.prompt.gate)
        }

        appendLine()
        appendLine("下面是真人发出去的话，用来校准语感。学它们的长度、具体度和「不解释」的劲头，不要照抄内容：")
        pack.anchors.forEach { appendLine("· $it") }

        if (cases.isNotEmpty()) {
            appendLine()
            appendLine("类似场景里真人怎么回（只参考思路，不要照抄句子）：")
            cases.forEach { c ->
                appendLine("· " + c.intent.ifBlank { c.id })
                c.lines.take(4).forEach { appendLine("  - $it") }
            }
        }

        appendLine()
        appendLine("输出 ${pack.count} 条，level 依次是 ${pack.levels.joinToString(" / ")}。")
        appendLine("长度、标点、句式按上面 voice 的要求执行；超过 45 字一律不合格（接情绪除外）。")
        appendLine("三条必须用三个不同招式，开头三个字不能重复，不能是同义改写。")
        appendLine("只输出 JSON，不要 markdown 代码块，不要任何解释：")
        append("""{"replies":[{"level":"${pack.levels.firstOrNull() ?: "稳"}","text":"..."}]}""")
    }

    private fun buildUser(parsed: ParsedContext, persona: String): String = buildString {
        if (parsed.history.isNotEmpty()) {
            appendLine("最近几条：")
            parsed.history.forEach { appendLine(it) }
            appendLine()
        }
        if (parsed.isSelfLast) {
            appendLine("最近对话最后一条是我发的：「${parsed.target}」，结合上文帮我自然接话：")
        } else {
            appendLine("对方刚发来：「${parsed.target}」")
        }
        if (persona.isNotBlank()) {
            appendLine()
            appendLine("背景（只用来判断关系，不要写进回复）：")
            appendLine(persona.take(300))
        }
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

    private fun parseReplies(raw: String, pack: ContentPack): List<Reply> {
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
                val level = o.optString("level").trim()
                    .ifBlank { pack.levels.getOrElse(i) { "中" } }
                out.add(Reply(level, text))
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
