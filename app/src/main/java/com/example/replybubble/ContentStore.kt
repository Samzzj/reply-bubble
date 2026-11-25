package com.example.replybubble

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 内容包的加载顺序：内存 -> 缓存文件 -> APK 内置 -> 代码兜底。
 * 任何一层坏掉都能降级，永远不会空白。
 */
object ContentStore {

    private const val CACHE_FILE = "content_cache.json"
    private const val ASSET = "content.json"
    private const val PREF_VERSION = "content_version"
    private const val PREF_URL = "content_url"

    @Volatile
    private var memory: ContentPack? = null

    fun contentUrl(c: Context): String =
        c.getSharedPreferences("reply_prefs", Context.MODE_PRIVATE)
            .getString(PREF_URL, "").orEmpty()

    fun setContentUrl(c: Context, url: String) =
        c.getSharedPreferences("reply_prefs", Context.MODE_PRIVATE)
            .edit().putString(PREF_URL, url).apply()

    /** 当前生效的内容包 */
    fun current(context: Context): ContentPack {
        memory?.let { return it }

        val cache = File(context.filesDir, CACHE_FILE)
        if (cache.exists()) {
            runCatching { ContentPack.parse(cache.readText()) }
                .getOrNull()
                ?.let {
                    memory = it
                    return it
                }
        }

        val bundled = runCatching {
            context.assets.open(ASSET).bufferedReader().use { it.readText() }
        }.getOrNull()

        val pack = bundled?.let { runCatching { ContentPack.parse(it) }.getOrNull() }
            ?: ContentPack.fallback()
        memory = pack
        return pack
    }

    fun versionLabel(context: Context): String {
        val v = current(context)
        return if (v.version <= 0) "内置兜底" else "v${v.version} · ${v.name}"
    }

    /**
     * 拉取远端内容包。返回可直接展示给用户的结果文案。
     * 失败时不改动任何缓存。
     */
    suspend fun update(context: Context, url: String): String = withContext(Dispatchers.IO) {
        if (url.isBlank()) throw IOException("先填内容包地址")
        val text = httpGet(url)
        val remote = try {
            ContentPack.parse(text)
        } catch (e: Exception) {
            throw IOException("内容包格式不对：${e.message}")
        }
        remote.validate()?.let { throw IOException("内容包校验失败：$it") }

        val local = current(context)
        if (remote.version <= local.version) {
            return@withContext "已是最新（远端 v${remote.version}，当前 v${local.version}）"
        }

        // 原子替换：先写临时文件，成功了再 rename
        val tmp = File(context.filesDir, "$CACHE_FILE.tmp")
        val dst = File(context.filesDir, CACHE_FILE)
        tmp.writeText(text)
        if (!tmp.renameTo(dst)) {
            tmp.copyTo(dst, overwrite = true)
            tmp.delete()
        }
        memory = remote
        setContentUrl(context, url)
        "已更新到 v${remote.version}（${remote.cases.size} 个案例 / ${remote.anchors.size} 句锚点）"
    }

    private fun httpGet(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 20_000
            setRequestProperty("Cache-Control", "no-cache")
        }
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IOException("下载失败 HTTP $code")
            return body
        } finally {
            conn.disconnect()
        }
    }
}
