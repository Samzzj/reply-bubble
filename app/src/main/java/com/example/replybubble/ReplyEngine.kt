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

    suspend fun generate(
        context: Context,
        parsed: ParsedContext,
        avoid: List<String> = emptyList()
    ): List<Reply> =
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
            val user = buildUser(parsed, persona, avoid)
            val isAnthropic = url.contains("anthropic", ignoreCase = true)

            val body = JSONObject().apply {
                put("model", model)
                put("temperature", if (isAnthropic) 1.0 else 1.05)
                if (!isAnthropic) {
                    put("presence_penalty", 0.15)
                }
                put("max_tokens", 950)
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
            .map { c ->
                val hits = c.keywords.count { kw ->
                    kw.isNotBlank() &&
                        (kw.length >= 2 || text.length <= 8) &&
                        text.contains(kw, ignoreCase = true)
                }
                c to hits
            }
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
        appendLine("下面是真人发出去的话，仅用来感受口语短句节奏，严禁把里面无关的话题/物品（如查岗、开车、共享单车等）编进当前回复：")
        pack.anchors.shuffled().take(10).forEach { appendLine("· $it") }

        if (cases.isNotEmpty()) {
            appendLine()
            appendLine("相近场景思路参考（只学切入角度，不要照抄无关字眼）：")
            cases.forEach { c ->
                appendLine("· " + c.intent.ifBlank { c.id })
                c.lines.take(4).forEach { appendLine("  - $it") }
            }
        }

        appendLine()
        appendLine("【紧扣上下文硬规则】每一条回复都必须 100% 直接回应用户提供的【本次真实对话】里正在聊的事！严禁脱离上下文自说自话、严禁硬套上面示范句的无关情景。")
        appendLine("【视角与身份硬规则】你是「我」本人，正在微信上给「对方」发消息。每一条输出都必须是「我」发给「对方」的下一句话。")
        appendLine("严禁站在「对方」视角去回答、反问或吐槽「我」的话（即使最后一条是「我」刚发的问句、对方暂未回复，也只能替「我」继续对「对方」接话）。")
        appendLine("【去 AI 味硬规则】像真人在微信上单手随手打字：短碎口语为主，可以用空格断句、句尾不加句号；严禁每条都写成工整对仗的「前半句，后半句」或三段式金句；严禁「你问我这个，是…」「这么关心我…，要不…」等 AI 模板，禁止生造「一验」「应个急」这种书面凑词。")
        appendLine("5 条里前 2 条只要 4-12 字极简随口接（零端着、不用硬撩），中间 2 条带点生活气小调侃，最后 1 条最多轻递半步。")

        val targetCount = maxOf(pack.count, 5)
        val levelHint = if (pack.levels.size >= targetCount) {
            pack.levels.take(targetCount).joinToString(" / ")
        } else {
            "1 / 2 / 3 / 4 / 5（前两条极简随口，后面渐进）"
        }

        appendLine()
        appendLine("输出 $targetCount 条，level 依次是 $levelHint。")
        appendLine("长度、标点、口语节奏按上面要求执行；超过 35 字一律太啰嗦。")
        appendLine("${targetCount}条尽量拉开角度与长短，开头三个字不能重复。")
        appendLine("先用一句大白话填 plan 判断双方温度和该怎么顺口接，再写出真人口语感十足的 replies。只输出 JSON，不要 markdown 代码块，不要任何解释：")
        append("""{"plan":"双方关系温度与自然切入思路","replies":[{"level":"1","text":"..."}]}""")
    }

    private fun buildUser(
        parsed: ParsedContext,
        persona: String,
        avoid: List<String> = emptyList()
    ): String = buildString {
        if (parsed.history.isNotEmpty()) {
            appendLine("最近几条：")
            parsed.history.forEach { appendLine(it) }
            appendLine()
        }
        if (parsed.isSelfLast) {
            val lastOther = parsed.history.lastOrNull { it.startsWith("对方：") }
                ?.removePrefix("对方：")?.trim()
            val trailingSelf = ArrayList<String>()
            for (line in parsed.history.asReversed()) {
                if (line.startsWith("我：")) trailingSelf.add(line.removePrefix("我：").trim())
                else break
            }
            val selfTail = (trailingSelf.asReversed() + parsed.target)
                .filter { it.isNotBlank() }
                .joinToString(" ")
            appendLine("【当前状态：最后是「我」发的，对方暂未回复】")
            if (!lastOther.isNullOrBlank()) {
                appendLine("对方上一句说的是：「$lastOther」")
            }
            appendLine("紧接着「我」发了：「$selfTail」（可能是文字或表情包内容），对方目前还没回复。")
            appendLine("现在请严格站在「我」的立场，继续对「对方」自然接话（可顺着对方上一句继续聊，或顺着我发的内容/表情包自然带过或转场）；绝对禁止替「对方」回答我！")
        } else {
            appendLine("【当前状态：对方刚发来消息】")
            appendLine("对方刚发来：「${parsed.target}」")
            appendLine("请严格站在「我」的立场直接回复「对方」：")
        }
        if (persona.isNotBlank()) {
            appendLine()
            appendLine("背景（只用来判断关系，不要写进回复）：")
            appendLine(persona.take(300))
        }
        if (avoid.isNotEmpty()) {
            appendLine()
            appendLine("以下几句刚才生成过但不用，换完全不同的切入点、招式和句式，不要重复：")
            avoid.forEach { appendLine("- $it") }
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

            val arr = extractRepliesArray(content)
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

    /** 容错提取 replies 数组：即使前置 plan 字段里出现逗号或引号笔误，也不影响解析 replies */
    private fun extractRepliesArray(content: String): JSONArray {
        val start = content.indexOf('{')
        val end = content.lastIndexOf('}')
        val json = if (start >= 0 && end > start) content.substring(start, end + 1) else content
        runCatching { JSONObject(json).optJSONArray("replies") }.getOrNull()?.let { return it }

        val match = Regex(""""replies"\s*:\s*\[""").find(content)
            ?: throw IllegalArgumentException("未找到 replies 数组")
        val arrStart = content.indexOf('[', match.range.first)
        var depth = 0
        var inString = false
        var escaped = false
        var arrEnd = -1
        for (i in arrStart until content.length) {
            val ch = content[i]
            if (escaped) {
                escaped = false
                continue
            }
            if (inString) {
                if (ch == '\\') escaped = true
                else if (ch == '"') inString = false
                continue
            }
            when (ch) {
                '"' -> inString = true
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) {
                        arrEnd = i
                        break
                    }
                }
            }
        }
        if (arrEnd <= arrStart) throw IllegalArgumentException("replies 数组未闭合")
        val cleaned = content.substring(arrStart, arrEnd + 1)
            .replace(Regex(""",\s*([}\]])"""), "$1")
        return JSONArray(cleaned)
    }
}
