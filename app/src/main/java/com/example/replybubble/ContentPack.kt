package com.example.replybubble

import org.json.JSONArray
import org.json.JSONObject

data class ContentPrompt(
    val attitude: String,
    val judge: String,
    val voice: String,
    val banned: List<String>,
    // v3 内容包新增：招式库、自检闸门、位置禁词
    val moves: String = "",
    val gate: String = "",
    val bannedSoft: List<String> = emptyList()
)

data class ContentCase(
    val id: String,
    val keywords: List<String>,
    val tone: String,
    val strength: String,
    val intent: String,
    val lines: List<String>
)

/**
 * 云端内容包。App 只认这个结构，改内容不需要重新发版。
 */
data class ContentPack(
    val version: Int,
    val name: String,
    val prompt: ContentPrompt,
    val anchors: List<String>,
    val cases: List<ContentCase>,
    val count: Int,
    val levels: List<String>
) {

    /** 校验：远端推过来的包必须先过这一关才允许替换 */
    fun validate(): String? = when {
        version <= 0 -> "version 必须大于 0"
        prompt.attitude.isBlank() -> "prompt.attitude 为空"
        prompt.voice.isBlank() -> "prompt.voice 为空"
        anchors.size < 3 -> "anchors 少于 3 句"
        count !in 1..5 -> "contract.count 必须在 1..5"
        levels.isEmpty() -> "contract.levels 为空"
        else -> null
    }

    companion object {

        fun fallback() = ContentPack(
            version = 0,
            name = "内置兜底",
            prompt = ContentPrompt(
                attitude = "不跪舔，不端着，真诚优先。",
                judge = "先判断对方是在求安慰、试探、拒绝还是闲聊，再决定推还是收。",
                voice = "短。给动作，不给建议。不解释，不总结。",
                banned = listOf("我理解你的感受", "你可以尝试", "建议你")
            ),
            anchors = listOf(
                "先别想那么多，出来，我请你吃顿好的。",
                "行，那你先忙，我不打扰了。",
                "睡了，明天说。"
            ),
            cases = emptyList(),
            count = 3,
            levels = listOf("稳", "中", "进")
        )

        /**
         * 宽松解析：缺字段就用默认值，绝不因为一个字段缺失直接崩。
         * 严格校验交给 [validate]。
         */
        fun parse(text: String): ContentPack {
            val o = JSONObject(text)
            val p = o.optJSONObject("prompt") ?: JSONObject()
            val c = o.optJSONObject("contract") ?: JSONObject()

            val cases = ArrayList<ContentCase>()
            val arr = o.optJSONArray("cases")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val x = arr.optJSONObject(i) ?: continue
                    val lines = x.optJSONArray("lines")?.let { a ->
                        (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotBlank() } }
                    } ?: emptyList()
                    if (lines.isEmpty()) continue
                    cases.add(
                        ContentCase(
                            id = x.optString("id"),
                            keywords = x.optJSONArray("kw")?.let { a ->
                                (0 until a.length()).map { a.optString(it) }
                            } ?: emptyList(),
                            tone = x.optString("tone"),
                            strength = x.optString("strength"),
                            intent = x.optString("intent"),
                            lines = lines
                        )
                    )
                }
            }

            return ContentPack(
                version = o.optInt("version", 0),
                name = o.optString("name", "未命名内容包"),
                prompt = ContentPrompt(
                    attitude = p.optString("attitude"),
                    judge = p.optString("judge"),
                    voice = p.optString("voice"),
                    banned = p.optJSONArray("banned")?.let { a ->
                        (0 until a.length()).map { a.optString(it) }
                    } ?: emptyList(),
                    moves = p.optString("moves"),
                    gate = p.optString("gate"),
                    bannedSoft = p.optJSONArray("banned_soft")?.let { a ->
                        (0 until a.length()).map { a.optString(it) }
                    } ?: emptyList()
                ),
                anchors = o.optJSONArray("anchors")?.let { a ->
                    (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
                } ?: emptyList(),
                cases = cases,
                count = c.optInt("count", 3),
                // v3 用 tiers，旧版用 levels，两个都认
                levels = (c.optJSONArray("levels") ?: c.optJSONArray("tiers"))?.let { a ->
                    (0 until a.length()).map { a.optString(it) }
                } ?: listOf("稳", "中", "进")
            )
        }
    }
}
