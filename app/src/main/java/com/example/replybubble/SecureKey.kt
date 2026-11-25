package com.example.replybubble

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 用 Android Keystore 里的 AES 密钥加密 API Key。
 * 密钥不出安全硬件，卸载 App 时一起销毁。
 */
object SecureKey {

    private const val ALIAS = "reply_key_alias"
    private const val PREF = "reply_secure"
    private const val NAME = "api_key"

    private fun keyStore(): KeyStore =
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun getOrCreateKey(): SecretKey {
        val ks = keyStore()
        val existing = ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun store(c: Context) =
        c.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun put(c: Context, value: String) {
        if (value.isEmpty()) {
            store(c).edit().remove(NAME).apply()
            return
        }
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            val payload = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) +
                ":" + Base64.encodeToString(encrypted, Base64.NO_WRAP)
            store(c).edit().putString(NAME, payload).apply()
        } catch (e: Exception) {
            // 极少见（比如厂商 ROM 的 Keystore 异常）。宁可存不进去，也不要崩。
            store(c).edit().remove(NAME).apply()
        }
    }

    fun get(c: Context): String? {
        val payload = store(c).getString(NAME, null) ?: return null
        val parts = payload.split(":", limit = 2)
        if (parts.size != 2) return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP))
            )
            String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }
}
