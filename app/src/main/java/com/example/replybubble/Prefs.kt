package com.example.replybubble

import android.content.Context

/**
 * 全部配置都放这里，读的时候直接传 Context，避免依赖 Application 初始化顺序。
 */
object Prefs {

    const val DEFAULT_URL = "https://api.deepseek.com/chat/completions"
    const val DEFAULT_MODEL = "deepseek-chat"

    private fun sp(c: Context) =
        c.applicationContext.getSharedPreferences("reply_prefs", Context.MODE_PRIVATE)

    fun apiUrl(c: Context): String =
        sp(c).getString("api_url", DEFAULT_URL).orEmpty().ifBlank { DEFAULT_URL }

    fun setApiUrl(c: Context, v: String) = sp(c).edit().putString("api_url", v).apply()

    fun model(c: Context): String =
        sp(c).getString("model", DEFAULT_MODEL).orEmpty().ifBlank { DEFAULT_MODEL }

    fun setModel(c: Context, v: String) = sp(c).edit().putString("model", v).apply()

    fun persona(c: Context): String = sp(c).getString("persona", "").orEmpty()

    fun setPersona(c: Context, v: String) = sp(c).edit().putString("persona", v).apply()

    fun apiKey(c: Context): String = SecureKey.get(c).orEmpty()

    fun setApiKey(c: Context, v: String) = SecureKey.put(c, v)

    /** 上一次被复制走的回复，用来避免「拿自己的回复当对方消息」再请求一次。 */
    fun lastCopied(c: Context): String = sp(c).getString("last_copied", "").orEmpty()

    fun setLastCopied(c: Context, v: String) = sp(c).edit().putString("last_copied", v).apply()
}
